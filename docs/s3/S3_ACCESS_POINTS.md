# S3 Access Points - Documentation

## Mục lục
1. [Tổng quan](#tổng-quan)
2. [S3 Access Points (Permissions)](#s3-access-points-permissions)
3. [S3 Multi-Region Access Points (Performance)](#s3-multi-region-access-points-performance)
4. [So sánh](#so-sánh)
5. [Cấu hình](#cấu-hình)
6. [Tài liệu tham khảo](#tài-liệu-tham-khảo)

---

## Tổng quan

AWS có 2 loại Access Points với mục đích khác nhau:

| | S3 Access Points | S3 Multi-Region Access Points |
|--|------------------|------------------------------|
| **Mục đích** | Simplified permissions | Auto-route đến region gần nhất |
| **Scope** | 1 bucket, 1 region | Nhiều buckets, nhiều regions |
| **Use case** | Multi-tenant, VPC isolation | Global performance |
| **Pricing** | Miễn phí | Tốn thêm tiền ($0.0033/GB) |

---

## S3 Access Points (Permissions)

### Vấn đề

Bucket policy quá phức tạp khi nhiều apps/teams cùng dùng 1 bucket:

```json
// Bucket Policy truyền thống (nightmare):
{
  "Statement": [
    { "Principal": "app-a", "Action": "s3:GetObject", "Resource": "team-a/*" },
    { "Principal": "app-b", "Action": "s3:*", "Resource": "team-b/*" },
    { "Principal": "analytics", "Action": "s3:GetObject", "Resource": "*" },
    // ... 100+ statements, ai dám sửa?
  ]
}
```

### Giải pháp

Mỗi Access Point = 1 endpoint riêng + 1 policy riêng:

```
┌────────────────────────────────────────────────────────────┐
│                                                            │
│  App A ──▶ ap-team-a.s3-accesspoint.us-east-1.amazonaws.com│
│            Policy: GetObject on team-a/*                   │
│                              │                             │
│  App B ──▶ ap-team-b...      │                             │
│            Policy: * on team-b/*                           │
│                              ▼                             │
│                        ┌──────────┐                        │
│                        │  Bucket  │                        │
│                        └──────────┘                        │
└────────────────────────────────────────────────────────────┘
```

### Đặc điểm

| Đặc điểm | Mô tả |
|----------|-------|
| **Endpoint riêng** | Mỗi AP có URL riêng |
| **Policy riêng** | Dễ quản lý, tách biệt |
| **VPC restriction** | Có thể giới hạn chỉ access từ VPC |
| **Naming** | `arn:aws:s3:region:account:accesspoint/name` |
| **Limit** | 10,000 access points per region per account |

### VPC Restriction

Có thể giới hạn access point chỉ cho phép từ VPC:

```
┌─────────────────────────────────────────────────────────┐
│  VPC-123                                                │
│  ┌─────────┐     ┌─────────────────┐     ┌──────────┐  │
│  │ App     │────▶│ ap-internal     │────▶│  Bucket  │  │
│  └─────────┘     │ (VPC only)      │     └──────────┘  │
│                  └─────────────────┘                    │
└─────────────────────────────────────────────────────────┘
         │
         ▼
┌─────────────────┐
│  Public Internet│ ──X──▶ Không access được!
└─────────────────┘
```

### Use Cases

| Use Case | Mô tả |
|----------|-------|
| **Multi-tenant SaaS** | Mỗi tenant 1 access point, policy riêng |
| **Data lake** | Analytics team, ML team, BI team có access khác nhau |
| **VPC isolation** | Internal apps chỉ access từ private network |
| **Audit/Compliance** | Dễ track ai access gì qua access point nào |

---

## S3 Multi-Region Access Points (Performance)

### Vấn đề

Users ở nhiều nơi, muốn access nhanh nhất:

```
Không có Multi-Region AP:
┌────────────┐                    ┌──────────────┐
│  User VN   │ ────── slow ──────▶│  us-east-1   │
└────────────┘    (high latency)  └──────────────┘

┌────────────┐                    ┌──────────────┐
│  User US   │ ────── fast ──────▶│  us-east-1   │
└────────────┘                    └──────────────┘
```

### Giải pháp

1 endpoint duy nhất, AWS tự động route đến bucket gần nhất:

```
┌────────────┐     ┌─────────────────┐     ┌──────────────┐
│  User VN   │ ───▶│ Multi-Region AP │────▶│ ap-south-1   │ FAST
└────────────┘     │   (1 endpoint)  │     └──────────────┘
                   │                 │            ▲
                   │  Auto-routing   │     Replication
                   │                 │            ▼
┌────────────┐     │                 │     ┌──────────────┐
│  User US   │ ───▶│                 │────▶│  us-east-1   │ FAST
└────────────┘     └─────────────────┘     └──────────────┘
```

### Đặc điểm

| Đặc điểm | Mô tả |
|----------|-------|
| **1 endpoint** | Tất cả users dùng chung 1 URL |
| **Auto-routing** | AWS Internet Monitor chọn bucket tốt nhất |
| **Failover** | Tự động failover nếu 1 region down |
| **Cần Replication** | Phải setup CRR để sync data giữa buckets |
| **Pricing** | $0.0033/GB data transfer qua MRAP |

### Cách hoạt động

```
1. User request đến Multi-Region Access Point
2. AWS Internet Monitor đánh giá:
   - Latency đến từng region
   - Health của từng bucket
   - Network conditions
3. Route đến bucket tốt nhất
4. Nếu bucket đó down → failover sang bucket khác
```

### Use Cases

| Use Case | Mô tả |
|----------|-------|
| **Global apps** | Users ở nhiều countries cần low latency |
| **Active-active** | Cả 2 regions đều nhận traffic |
| **DR với auto-failover** | Region sập → tự động chuyển traffic |
| **Gaming** | Cần latency thấp nhất có thể |

### So sánh với CloudFront

| | Multi-Region AP | CloudFront |
|--|-----------------|------------|
| **Caching** | ❌ Không cache | ✅ Cache ở edge |
| **Upload** | ✅ Cả upload + download | ⚠️ Chủ yếu download |
| **Setup** | Phức tạp (replication) | Đơn giản hơn |
| **Use case** | Read/write global | Read-heavy, static content |

---

## So sánh

| | Access Points | Multi-Region Access Points |
|--|---------------|---------------------------|
| **Giải quyết** | Permissions phức tạp | Latency cho global users |
| **Số buckets** | 1 bucket | Nhiều buckets (nhiều regions) |
| **Cần Replication?** | ❌ Không | ✅ Có (để sync data) |
| **Pricing** | Miễn phí | $0.0033/GB data transfer |
| **Setup** | Đơn giản | Phức tạp (replication + failover) |
| **VPC restriction** | ✅ Có | ❌ Không |
| **Auto-routing** | ❌ Không | ✅ Có |

### Khi nào dùng gì?

| Scenario | Dùng |
|----------|------|
| Nhiều teams/apps cùng access 1 bucket | Access Points |
| Muốn restrict access từ VPC only | Access Points |
| Users ở nhiều countries, cần low latency | Multi-Region Access Points |
| Active-active multi-region setup | Multi-Region Access Points |
| Cả hai vấn đề | Dùng cả hai! |

### Thực tế

- **Access Points:** Phổ biến ở data lake, enterprise với nhiều teams
- **Multi-Region AP:** Ít phổ biến hơn vì:
  - CloudFront thường đủ cho download
  - Transfer Acceleration cho upload
  - Setup phức tạp + tốn tiền

---

## Cấu hình

### S3 Access Points

**Step 1: Tạo Access Point**

```bash
aws s3control create-access-point \
  --account-id 123456789012 \
  --name ap-team-a \
  --bucket my-bucket
```

**Step 2: Attach Policy**

```json
{
  "Version": "2012-10-17",
  "Statement": [
    {
      "Effect": "Allow",
      "Principal": {
        "AWS": "arn:aws:iam::123456789012:role/TeamARole"
      },
      "Action": ["s3:GetObject", "s3:PutObject"],
      "Resource": "arn:aws:s3:us-east-1:123456789012:accesspoint/ap-team-a/object/team-a/*"
    }
  ]
}
```

**Step 3: Sử dụng Access Point**

```java
// Thay vì bucket name, dùng Access Point ARN
S3Client s3 = S3Client.builder().build();

GetObjectRequest request = GetObjectRequest.builder()
    .bucket("arn:aws:s3:us-east-1:123456789012:accesspoint/ap-team-a")
    .key("team-a/file.txt")
    .build();
```

### S3 Multi-Region Access Points

**Step 1: Tạo buckets ở các regions**

```bash
aws s3 mb s3://my-bucket-us-east-1 --region us-east-1
aws s3 mb s3://my-bucket-ap-south-1 --region ap-south-1
```

**Step 2: Enable versioning (required for replication)**

```bash
aws s3api put-bucket-versioning \
  --bucket my-bucket-us-east-1 \
  --versioning-configuration Status=Enabled

aws s3api put-bucket-versioning \
  --bucket my-bucket-ap-south-1 \
  --versioning-configuration Status=Enabled
```

**Step 3: Setup Cross-Region Replication**

(See [S3_REPLICATION.md](./S3_REPLICATION.md) for details)

**Step 4: Create Multi-Region Access Point (AWS Console)**

```
S3 Console → Multi-Region Access Points → Create
→ Add buckets from different regions
→ AWS tự động tạo replication rules
```

**Step 5: Sử dụng MRAP**

```java
// 1 endpoint cho tất cả regions
String mrapArn = "arn:aws:s3::123456789012:accesspoint/mrap-name.mrap";

GetObjectRequest request = GetObjectRequest.builder()
    .bucket(mrapArn)
    .key("file.txt")
    .build();

// AWS tự động route đến bucket gần nhất
```

---

## Tài liệu tham khảo

| Chủ đề | Link |
|--------|------|
| **S3 Access Points** | https://docs.aws.amazon.com/AmazonS3/latest/userguide/access-points.html |
| **Access Points VPC** | https://docs.aws.amazon.com/AmazonS3/latest/userguide/access-points-vpc.html |
| **Multi-Region Access Points** | https://docs.aws.amazon.com/AmazonS3/latest/userguide/MultiRegionAccessPoints.html |
| **MRAP Failover** | https://docs.aws.amazon.com/AmazonS3/latest/userguide/MultiRegionAccessPointsFailover.html |

---

## Kết luận

- **S3 Access Points:** Dùng khi cần simplified permissions, multi-tenant, VPC isolation
- **S3 Multi-Region Access Points:** Dùng khi cần global performance, auto-failover
- **Thực tế:** Access Points phổ biến hơn, Multi-Region AP chỉ cần cho enterprise global apps
- **LocalStack:** Access Points có thể test được, Multi-Region AP không support

---

*Ngày tạo: 2026-01-17*
*Status: 📋 Documented (Not Implemented)*
*Project: realworld-exam*
