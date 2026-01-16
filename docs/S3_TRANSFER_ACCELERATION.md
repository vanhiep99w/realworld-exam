# S3 Transfer Acceleration

## Overview

S3 Transfer Acceleration là bucket-level feature giúp tăng tốc upload/download files qua khoảng cách địa lý xa bằng cách sử dụng **CloudFront Edge Locations**.

## Giải thích đơn giản

**Vấn đề:** Bạn ở Việt Nam, bucket S3 ở US. Upload file qua internet công cộng → **chậm, hay mất gói**.

**Giải pháp Transfer Acceleration:**

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                     ❌ KHÔNG CÓ Transfer Acceleration                        │
└─────────────────────────────────────────────────────────────────────────────┘

┌────────┐                                                    ┌─────────┐
│ User   │═══════════════ Public Internet ═══════════════════▶│   S3    │
│   VN   │            (chậm, hay mất gói, ~400ms)             │ US-East │
└────────┘                                                    └─────────┘


┌─────────────────────────────────────────────────────────────────────────────┐
│                      ✅ CÓ Transfer Acceleration                             │
└─────────────────────────────────────────────────────────────────────────────┘

┌────────┐         ┌───────────────┐                          ┌─────────┐
│ User   │════════▶│   CloudFront  │══════ AWS Backbone ═════▶│   S3    │
│   VN   │ (ngắn)  │ Edge Singapore│    (nhanh, ổn định)      │ US-East │
└────────┘         └───────────────┘                          └─────────┘
```

**Cách hoạt động:**
1. AWS có **Edge Locations** (server) ở khắp nơi (Singapore, Tokyo, HK...)
2. Bạn upload lên Edge gần nhất (Singapore) → **nhanh vì gần**
3. Từ Edge → S3 US đi qua **mạng riêng của AWS** → **nhanh và ổn định**

**Ví dụ tốc độ:**

| Cách | Thời gian upload 100MB |
|------|------------------------|
| Bình thường (VN → US) | ~40 giây |
| Acceleration (VN → SG Edge → US) | ~15 giây |

## When to Use

| Scenario | Benefit |
|----------|---------|
| Users upload từ xa (VN → US bucket) | Giảm 50-500% thời gian upload |
| Upload files lớn (GB+) | Tận dụng optimized network path |
| Global users, centralized bucket | Tất cả users có speed tương đương |

## When NOT to Use

- Users cùng region với bucket (không cải thiện)
- Download nhiều hơn upload (dùng CloudFront CDN thay thế)
- Bucket name chứa dấu `.` (không support)

## How It Works

### 1. Enable Transfer Acceleration trên Bucket

```java
// AWS SDK v2
s3Client.putBucketAccelerateConfiguration(
    PutBucketAccelerateConfigurationRequest.builder()
        .bucket("my-bucket")
        .accelerateConfiguration(AccelerateConfiguration.builder()
            .status(BucketAccelerateStatus.ENABLED)
            .build())
        .build()
);
```

### 2. Sử dụng Accelerate Endpoint

| Type | Endpoint Format |
|------|-----------------|
| Standard | `bucket-name.s3.us-east-1.amazonaws.com` |
| **Accelerate** | `bucket-name.s3-accelerate.amazonaws.com` |
| Accelerate Dual-stack (IPv6) | `bucket-name.s3-accelerate.dualstack.amazonaws.com` |

### 3. Configure S3 Client với Acceleration

```java
// Option 1: Tạo separate client cho acceleration
S3Client acceleratedClient = S3Client.builder()
    .region(Region.US_EAST_1)
    .serviceConfiguration(S3Configuration.builder()
        .accelerateModeEnabled(true)  // ← Key config
        .build())
    .build();

// Option 2: Tạo Presigner với acceleration endpoint
S3Presigner acceleratedPresigner = S3Presigner.builder()
    .region(Region.US_EAST_1)
    .serviceConfiguration(S3Configuration.builder()
        .accelerateModeEnabled(true)
        .build())
    .build();
```

## Presigned URL với Transfer Acceleration

**Có thể dùng presigned URL policies như S3 bình thường không?**

✅ **CÓ** - Hoàn toàn giống nhau! Chỉ khác **endpoint**.

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                    Presigned URL Flow - Giống nhau 100%                      │
└─────────────────────────────────────────────────────────────────────────────┘

┌──────────────┐     Generate presigned URL      ┌─────────────────────────┐
│ Backend Java │ ───────────────────────────────▶│ URL + Signature         │
└──────────────┘                                 │ + Expiry + Policies     │
                                                 └───────────┬─────────────┘
                                                             │
                                                             ▼
                                                 ┌─────────────────────────┐
                                                 │       Browser           │
                                                 └─────────────────────────┘
```

**Chỉ khác endpoint:**

| Type | URL Generated |
|------|---------------|
| Normal | `https://my-bucket.s3.us-east-1.amazonaws.com/file.pdf?X-Amz-Signature=...` |
| **Accelerated** | `https://my-bucket.s3-accelerate.amazonaws.com/file.pdf?X-Amz-Signature=...` |

**Code so sánh:**

```java
// Normal presigner (code hiện tại)
S3Presigner normalPresigner = S3Presigner.builder()
    .region(Region.US_EAST_1)
    .build();

// Accelerated presigner (chỉ thêm 1 config)
S3Presigner acceleratedPresigner = S3Presigner.builder()
    .region(Region.US_EAST_1)
    .serviceConfiguration(S3Configuration.builder()
        .accelerateModeEnabled(true)  // ← Chỉ thêm dòng này
        .build())
    .build();
```

Tất cả policies (expiry, content-type, content-length, etc.) đều hoạt động **y hệt** S3 bình thường.

## Implementation Plan

### Backend Changes

```
be/src/main/java/com/seft/learn/example/
├── config/
│   └── S3Config.java              # Add accelerated S3Client bean
├── controller/
│   └── S3TransferAccelerationController.java  # New controller
├── service/
│   └── S3TransferAccelerationService.java     # New service
└── dto/
    └── TransferAccelerationStatusDto.java     # Response DTO
```

### API Endpoints

| Method | Endpoint | Description |
|--------|----------|-------------|
| `GET` | `/api/s3/transfer-acceleration/status` | Get acceleration status |
| `PUT` | `/api/s3/transfer-acceleration/enable` | Enable acceleration |
| `PUT` | `/api/s3/transfer-acceleration/disable` | Disable (suspend) |
| `GET` | `/api/s3/presigned-url/accelerated/put` | Get accelerated presigned PUT URL |
| `GET` | `/api/s3/presigned-url/accelerated/get` | Get accelerated presigned GET URL |

### Configuration

```yaml
# application.yml
aws:
  s3:
    transfer-acceleration:
      enabled: true  # Feature toggle
```

## Pricing

### Chi phí cơ bản

| Loại | Chi phí |
|------|---------|
| **Upload (IN)** | **$0.04/GB** |
| **Download (OUT)** | **$0.04/GB** + phí transfer thường |
| Nếu acceleration **không nhanh hơn** | **$0 (miễn phí)** |

### Ví dụ thực tế

| Hành động | Dung lượng | Chi phí Acceleration |
|-----------|------------|---------------------|
| Upload 1GB từ VN → US | 1GB | $0.04 |
| Upload 10GB từ VN → US | 10GB | $0.40 |
| Upload 100GB/tháng | 100GB | $4.00 |

### So sánh với S3 Standard

| | S3 Standard | S3 + Acceleration |
|--|-------------|-------------------|
| Upload 1GB | **$0** | **$0.04** |
| Storage 1GB/tháng | $0.023 | $0.023 (giống) |
| Download 1GB | $0.09 | $0.09 + $0.04 = **$0.13** |

### Khi nào đáng tiền?

- ✅ Users ở xa bucket (VN → US, EU → Asia)
- ✅ Upload files lớn, cần tốc độ
- ❌ Users cùng region với bucket (không cải thiện, phí thừa)

> ⚠️ AWS tự động bypass acceleration nếu không nhanh hơn standard transfer → **không tính phí**.

## Limitations

1. **Bucket name không được chứa `.`** (phải DNS-compliant)
2. **Không support các operations**: `ListBuckets`, `CreateBucket`, `DeleteBucket`
3. **Không support cross-region CopyObject**
4. **Mất ~20 phút** sau khi enable để thấy performance improvement
5. **LocalStack limitation**: LocalStack không fully support Transfer Acceleration

## Testing với LocalStack

### LocalStack Support

| API | LocalStack Support |
|-----|-------------------|
| `PutBucketAccelerateConfiguration` | ✅ Community |
| `GetBucketAccelerateConfiguration` | ✅ Community |

### Giới hạn của LocalStack

LocalStack chỉ **lưu trạng thái** (Enabled/Suspended), **không thực sự route qua Edge Locations** vì chạy local.

```java
// ✅ Có thể test enable/disable status
s3Client.putBucketAccelerateConfiguration(...);  // Works
s3Client.getBucketAccelerateConfiguration(...);  // Works

// ⚠️ Presigned URL với accelerate endpoint sẽ vẫn 
// point về localhost, không có speed benefit
```

### Ví dụ Test

```java
// Test enable acceleration
s3Client.putBucketAccelerateConfiguration(
    PutBucketAccelerateConfigurationRequest.builder()
        .bucket("my-bucket")
        .accelerateConfiguration(AccelerateConfiguration.builder()
            .status(BucketAccelerateStatus.ENABLED)
            .build())
        .build()
);

// Verify status
GetBucketAccelerateConfigurationResponse response = 
    s3Client.getBucketAccelerateConfiguration(
        GetBucketAccelerateConfigurationRequest.builder()
            .bucket("my-bucket")
            .build()
    );
assert response.status() == BucketAccelerateStatus.ENABLED;
```

### Kết luận Testing

| Test Type | LocalStack | AWS Thật |
|-----------|------------|----------|
| API logic (enable/disable) | ✅ OK | ✅ OK |
| Generate accelerated URL | ✅ OK (format đúng) | ✅ OK |
| **Performance thực tế** | ❌ Không có | ✅ Phải test ở đây |

## Speed Comparison Tool

AWS cung cấp tool để compare tốc độ:
https://s3-accelerate-speedtest.s3-accelerate.amazonaws.com/en/accelerate-speed-comparsion.html

## Official Documentation

- [S3 Transfer Acceleration](https://docs.aws.amazon.com/AmazonS3/latest/userguide/transfer-acceleration.html)
- [Getting Started](https://docs.aws.amazon.com/AmazonS3/latest/userguide/transfer-acceleration-getting-started.html)
- [Enabling Transfer Acceleration](https://docs.aws.amazon.com/AmazonS3/latest/userguide/transfer-acceleration-examples.html)
- [AWS SDK Java v2 - S3Configuration](https://sdk.amazonaws.com/java/api/latest/software/amazon/awssdk/services/s3/S3Configuration.html)

---

*Document created: 2026-01-16*
*Status: 📋 Documented (Not Implemented)*
*Project: realworld-exam*
