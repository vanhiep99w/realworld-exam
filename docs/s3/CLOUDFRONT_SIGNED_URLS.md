# CloudFront + Signed URLs

## Mục lục
1. [Tổng quan](#tổng-quan)
2. [So sánh với S3 Presigned URL](#so-sánh-với-s3-presigned-url)
3. [So sánh với Transfer Acceleration](#so-sánh-với-transfer-acceleration)
4. [Cách hoạt động](#cách-hoạt-động)
5. [Signed URL vs Signed Cookies](#signed-url-vs-signed-cookies)
6. [Pricing](#pricing)
7. [Implementation](#implementation)
8. [Tài liệu tham khảo](#tài-liệu-tham-khảo)

---

## Tổng quan

CloudFront + Signed URLs là giải pháp kết hợp CDN với authentication để:
- **Tăng tốc download** qua edge locations gần user
- **Bảo vệ private content** chỉ người có signed URL mới access được
- **Cache content** giảm load và cost cho S3

```
┌────────┐     ┌────────────────┐     ┌─────────┐
│ Client │ ───▶│   CloudFront   │────▶│   S3    │
│ (VN)   │     │  (Edge - SG)   │     │(US-East)│
└────────┘     └────────────────┘     └─────────┘
     │              │
     │              ├── Cache content gần user → nhanh hơn
     │              └── Verify signature → chỉ authorized access
     │
     └── Signed URL: chỉ người có URL mới access được
```

### Đặc điểm chính

| Đặc điểm | Mô tả |
|----------|-------|
| **Caching** | Content được cache ở edge, giảm latency |
| **Authentication** | Dùng RSA key pair (không phải AWS credentials) |
| **Geo-restriction** | Block/allow theo country |
| **IP restriction** | Giới hạn IP được access |
| **Expiry** | URL có thời hạn |

---

## So sánh với S3 Presigned URL

| | S3 Presigned URL | CloudFront Signed URL |
|--|------------------|----------------------|
| **Tốc độ** | Download từ 1 region | Download từ edge gần nhất |
| **Cache** | ❌ Không | ✅ Có (giảm cost S3 requests) |
| **Geo-restriction** | ❌ Không | ✅ Có (block theo country) |
| **IP restriction** | ❌ Không | ✅ Có |
| **Ký bằng** | AWS credentials (access key) | RSA key pair (riêng) |
| **Setup** | Đơn giản | Phức tạp hơn (tạo key pair, distribution) |
| **Use case** | Upload, occasional download | Video streaming, global delivery |

---

## So sánh với Transfer Acceleration

Cả hai đều dùng CloudFront Edge, nhưng mục đích khác:

| | Transfer Acceleration | CloudFront + Signed URLs |
|--|----------------------|--------------------------|
| **Mục đích chính** | Tăng tốc **UPLOAD** | Tăng tốc **DOWNLOAD** + Auth |
| **Caching** | ❌ Không cache | ✅ Cache ở edge |
| **Authentication** | Dùng S3 presigned URL | Dùng CloudFront signed URL (RSA key) |
| **Setup** | Bật 1 config trên bucket | Tạo CloudFront distribution |
| **Geo/IP restriction** | ❌ Không | ✅ Có |
| **Code change** | Gần như không (thêm 1 flag) | Phải đổi signing logic |

```
Transfer Acceleration (UPLOAD focus):
┌────────┐     ┌───────────┐     ┌─────────┐
│ Client │────▶│ Edge (SG) │────▶│   S3    │  ← File đi thẳng vào S3
└────────┘     └───────────┘     └─────────┘
                    │
                    └── Không cache, chỉ optimize network path

CloudFront + Signed URL (DOWNLOAD focus):
┌────────┐     ┌───────────┐     ┌─────────┐
│ Client │────▶│ CloudFront│────▶│   S3    │
└────────┘     │   (Edge)  │     └─────────┘
               └───────────┘
                    │
                    └── Cache file, lần sau không cần gọi S3
```

**Khi nào dùng gì:**

| Use Case | Dùng |
|----------|------|
| Upload file lớn từ xa | Transfer Acceleration |
| Video streaming, download nhiều | CloudFront + Signed URL |
| Cả upload + download global | Dùng cả hai! |

---

## Cách hoạt động

### Flow chi tiết

```
┌──────────┐    1. Request access     ┌──────────────┐
│  Client  │ ────────────────────────▶│   Backend    │
│          │                          │ (Spring Boot)│
└──────────┘                          └──────────────┘
     │                                       │
     │                                       │ 2. Generate Signed URL
     │                                       │    (dùng Private Key)
     │                                       ▼
     │                               ┌──────────────┐
     │  3. Return Signed URL         │  Private Key │
     │◀──────────────────────────────│  (stored in  │
     │                               │   backend)   │
     │                               └──────────────┘
     │
     │  4. Access content với Signed URL
     ▼
┌──────────────┐    5. Verify signature    ┌─────────┐
│  CloudFront  │ ◀─────────────────────────│ Public  │
│   (Edge)     │      (dùng Public Key)    │   Key   │
└──────────────┘                           └─────────┘
     │
     │  6. Fetch from origin (nếu không cache)
     ▼
┌─────────┐
│   S3    │  ← Private bucket (chỉ CloudFront access được)
└─────────┘
```

### Signed URL format

```
https://d1234.cloudfront.net/videos/movie.mp4
  ?Expires=1737100800
  &Signature=abc123...xyz
  &Key-Pair-Id=APKAXXXX
```

| Param | Mô tả |
|-------|-------|
| `Expires` | Unix timestamp - URL hết hạn lúc nào |
| `Signature` | Chữ ký RSA (từ private key) |
| `Key-Pair-Id` | ID của key pair trong CloudFront |

---

## Signed URL vs Signed Cookies

| | Signed URL | Signed Cookies |
|--|------------|----------------|
| **Use case** | 1 file cụ thể | Nhiều files (whole folder) |
| **Ví dụ** | Download 1 video | Xem cả course (nhiều videos) |
| **Cách dùng** | Mỗi file 1 URL riêng | Set cookie 1 lần, access tất cả |
| **Khi nào** | Đơn giản, 1 file | Phức tạp, nhiều files |

**Ví dụ Signed Cookies:**
```
User mua course → Set cookie cho path /courses/123/*
→ User access được tất cả videos trong course đó
→ Không cần generate URL cho từng video
```

---

## Pricing

### So sánh với S3 thuần

| | S3 thuần | CloudFront |
|--|----------|------------|
| **Data transfer OUT** | $0.09/GB | $0.085/GB (rẻ hơn!) |
| **Requests (GET)** | $0.0004/1000 | $0.01/10000 |

### Ví dụ: 1 file 100MB, download 1000 lần/tháng

```
S3 thuần:
├── Data: 100MB × 1000 = 100GB × $0.09 = $9.00
├── Requests: 1000 × $0.0004 = $0.40
└── Total: $9.40

S3 + CloudFront (90% cache hit):
├── S3 Data: 100GB × 10% = 10GB × $0.09 = $0.90
├── CF Data: 100GB × $0.085 = $8.50
├── Requests: ~$0.10
└── Total: $9.50 (gần bằng, nhưng NHANH hơn)

S3 + CloudFront (99% cache hit - video streaming):
├── S3 Data: 100GB × 1% = 1GB × $0.09 = $0.09
├── CF Data: 100GB × $0.085 = $8.50
└── Total: $8.60 (RẺ hơn + NHANH hơn)
```

### Kết luận pricing

| Scenario | Rẻ hơn |
|----------|--------|
| Ít download, mỗi file download 1-2 lần | S3 thuần |
| Nhiều download cùng file | CloudFront (cache hit cao) |
| Video streaming | CloudFront chắc chắn rẻ hơn |

---

## Implementation

### Setup cần gì

1. **Tạo RSA key pair** (public + private key)
2. **Upload public key lên CloudFront**
3. **Tạo CloudFront distribution** → S3 origin
4. **Cấu hình S3 bucket** chỉ cho CloudFront access (OAC)
5. **Backend dùng private key** để sign URL

### Backend Changes (Spring Boot)

```
be/src/main/java/com/seft/learn/example/
├── config/
│   └── CloudFrontConfig.java           # CloudFront signer config
├── controller/
│   └── CloudFrontController.java       # API endpoints
├── service/
│   └── CloudFrontSignedUrlService.java # Generate signed URLs
└── dto/
    └── SignedUrlResponse.java          # Response DTO
```

### Code Example

```java
// S3 Presigned URL (hiện tại)
S3Presigner presigner = S3Presigner.create();
PresignedGetObjectRequest presignedRequest = presigner.presignGetObject(...);
return presignedRequest.url();

// CloudFront Signed URL (mới)
CloudFrontUrlSigner signer = CloudFrontUrlSigner.builder()
    .privateKey(privateKey)          // RSA private key (file .pem)
    .keyPairId("APKAXXXX")           // CloudFront key pair ID
    .build();

String signedUrl = signer.getSignedUrl(
    "https://d1234.cloudfront.net/videos/movie.mp4",
    Instant.now().plus(1, ChronoUnit.HOURS)  // expiry
);
```

### Thay đổi cần làm

| Phần | Thay đổi |
|------|----------|
| **Dependency** | Thêm `aws-cloudfront` SDK |
| **Config** | Thêm private key path, key pair ID, CloudFront domain |
| **Code** | Thay `S3Presigner` → `CloudFrontUrlSigner` |
| **S3 bucket** | Chuyển sang private, chỉ cho CloudFront access (OAC) |
| **AWS Console** | Setup CloudFront distribution, key pair |

---

## Use Cases

### 1. Video Streaming
**Scenario:** Platform học online như Udemy, Coursera.

**Tại sao cần CloudFront:**
- Videos lớn (GB), download từ S3 trực tiếp chậm
- Cache ở edge → user VN xem video từ Singapore, không phải US
- Signed URL → chỉ user đã mua course mới xem được

### 2. Paid Content Download
**Scenario:** Bán ebook, software, digital assets.

**Tại sao cần CloudFront:**
- Bảo vệ content → không share link được (có expiry)
- Geo-restriction → chỉ bán ở một số nước
- Nhanh hơn cho global users

### 3. Private Media Gallery
**Scenario:** Ứng dụng lưu trữ ảnh/video private.

**Tại sao cần CloudFront:**
- Mỗi user chỉ xem được ảnh của mình
- Performance tốt với nhiều ảnh nhỏ (cache hit cao)

---

## Khi nào KHÔNG cần CloudFront

- **Users chỉ ở 1 region** - S3 presigned URL đủ
- **Upload là chính** - Dùng Transfer Acceleration thay thế
- **File nhỏ, ít download** - Không đáng setup phức tạp
- **Budget hạn chế, traffic thấp** - S3 thuần rẻ hơn

---

## Tài liệu tham khảo

| Chủ đề | Link |
|--------|------|
| **CloudFront + S3** | https://docs.aws.amazon.com/AmazonCloudFront/latest/DeveloperGuide/DownloadDistS3AndCustomOrigins.html |
| **Signed URLs** | https://docs.aws.amazon.com/AmazonCloudFront/latest/DeveloperGuide/private-content-signed-urls.html |
| **Signed Cookies** | https://docs.aws.amazon.com/AmazonCloudFront/latest/DeveloperGuide/private-content-signed-cookies.html |
| **Key Pairs** | https://docs.aws.amazon.com/AmazonCloudFront/latest/DeveloperGuide/private-content-trusted-signers.html |
| **OAC (Origin Access Control)** | https://docs.aws.amazon.com/AmazonCloudFront/latest/DeveloperGuide/private-content-restricting-access-to-s3.html |

---

*Ngày tạo: 2026-01-17*
*Status: 📋 Documented (Not Implemented)*
*Project: realworld-exam*
