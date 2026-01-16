# S3 CORS Configuration

Cấu hình CORS cho S3 bucket để cho phép browser upload/download trực tiếp.

---

## Tại sao S3 cần CORS?

Khi browser upload file trực tiếp lên S3 (qua presigned URL), đó là **cross-origin request**:

```
┌─────────────────────────────────────────────────────────────────┐
│  Browser upload qua Presigned URL                               │
├─────────────────────────────────────────────────────────────────┤
│                                                                 │
│  https://myapp.com                 https://bucket.s3.amazon...  │
│  ┌──────────────┐    PUT file     ┌──────────────┐             │
│  │   Browser    │ ────────────▶   │      S3      │             │
│  └──────────────┘                 └──────────────┘             │
│                                                                 │
│  → Khác origin = Cần CORS config trên S3                        │
│                                                                 │
└─────────────────────────────────────────────────────────────────┘
```

---

## S3 CORS Flow

```
┌─────────────────────────────────────────────────────────────────┐
│  Có CORS config trên S3                                         │
├─────────────────────────────────────────────────────────────────┤
│                                                                 │
│  Browser              Preflight (OPTIONS)              S3       │
│  ┌──────┐  ─────────────────────────────────────▶  ┌──────┐    │
│  │      │  ◀─────────────────────────────────────  │      │    │
│  │      │        "OK, myapp.com allowed"           │      │    │
│  │      │                                          │      │    │
│  │      │  ─────────── PUT file.jpg ───────────▶  │      │    │
│  │      │  ◀─────────── 200 OK ─────────────────  │      │    │
│  └──────┘                 ✅                       └──────┘    │
└─────────────────────────────────────────────────────────────────┘
```

---

## CORS Configuration

### JSON Format

```json
{
  "CORSRules": [
    {
      "ID": "AllowUploadFromMyApp",
      "AllowedOrigins": ["https://myapp.com", "https://staging.myapp.com"],
      "AllowedMethods": ["GET", "PUT", "POST", "DELETE"],
      "AllowedHeaders": ["*"],
      "ExposeHeaders": ["ETag", "x-amz-meta-custom"],
      "MaxAgeSeconds": 3600
    }
  ]
}
```

### Config Options

| Field | Ý nghĩa | Ví dụ |
|-------|---------|-------|
| **AllowedOrigins** | Domains được phép | `["https://myapp.com"]` hoặc `["*"]` |
| **AllowedMethods** | HTTP methods được phép | `["GET", "PUT", "POST"]` |
| **AllowedHeaders** | Request headers client được gửi | `["*"]` hoặc `["Content-Type"]` |
| **ExposeHeaders** | Response headers client được đọc | `["ETag"]` |
| **MaxAgeSeconds** | Cache preflight bao lâu | `3600` (1 giờ) |

---

## Dev vs Production

```json
// Development (LocalStack) - lỏng hơn
{
  "CORSRules": [{
    "AllowedOrigins": ["*"],
    "AllowedMethods": ["GET", "PUT", "POST", "DELETE", "HEAD"],
    "AllowedHeaders": ["*"],
    "ExposeHeaders": ["ETag"],
    "MaxAgeSeconds": 3000
  }]
}
```

```json
// Production - strict
{
  "CORSRules": [{
    "AllowedOrigins": ["https://myapp.com"],
    "AllowedMethods": ["GET", "PUT"],
    "AllowedHeaders": ["Content-Type", "x-amz-meta-*"],
    "ExposeHeaders": ["ETag"],
    "MaxAgeSeconds": 3600
  }]
}
```

---

## Khi nào cần S3 CORS?

| Scenario | Cần CORS? |
|----------|-----------|
| ✅ Browser upload qua presigned URL | Có |
| ✅ Frontend fetch files từ S3 | Có |
| ✅ JavaScript đọc response headers từ S3 | Có |
| ❌ Backend gọi S3 (không qua browser) | Không |
| ❌ CloudFront proxy S3 | Không (CF xử lý) |

---

## AWS CLI Commands

### Tạo CORS config

```bash
# Tạo file cors.json
cat > cors.json << 'EOF'
{
  "CORSRules": [{
    "AllowedOrigins": ["http://localhost:3000"],
    "AllowedMethods": ["GET", "PUT", "POST", "DELETE", "HEAD"],
    "AllowedHeaders": ["*"],
    "ExposeHeaders": ["ETag"],
    "MaxAgeSeconds": 3000
  }]
}
EOF

# Apply to bucket
aws s3api put-bucket-cors \
  --bucket my-bucket \
  --cors-configuration file://cors.json
```

### Xem CORS config

```bash
aws s3api get-bucket-cors --bucket my-bucket
```

### Xóa CORS config

```bash
aws s3api delete-bucket-cors --bucket my-bucket
```

---

## LocalStack

```bash
# Apply CORS to LocalStack bucket
aws --endpoint-url=http://localhost:4566 s3api put-bucket-cors \
  --bucket my-bucket \
  --cors-configuration file://cors.json

# Verify
aws --endpoint-url=http://localhost:4566 s3api get-bucket-cors \
  --bucket my-bucket
```

---

## Common Issues

| Lỗi | Nguyên nhân | Fix |
|-----|-------------|-----|
| `No 'Access-Control-Allow-Origin'` | CORS chưa config hoặc origin sai | Thêm origin vào AllowedOrigins |
| `Method PUT not allowed` | Thiếu method trong AllowedMethods | Thêm PUT vào config |
| `Header X-Custom not allowed` | AllowedHeaders không có header đó | Thêm header hoặc dùng `*` |
| Preflight liên tục | MaxAgeSeconds quá nhỏ | Tăng lên 3600 |
| Không đọc được ETag | Thiếu ExposeHeaders | Thêm `"ETag"` vào ExposeHeaders |

---

## Presigned URL + CORS

Khi dùng presigned URL để upload, cần đảm bảo:

1. **S3 bucket có CORS config** cho origin của frontend
2. **AllowedMethods** bao gồm PUT (hoặc POST nếu dùng POST policy)
3. **AllowedHeaders** bao gồm Content-Type và các headers cần thiết

```
┌─────────────────────────────────────────────────────────────────┐
│  Presigned URL Upload Flow                                      │
├─────────────────────────────────────────────────────────────────┤
│                                                                 │
│  1. Frontend request presigned URL từ Backend                   │
│  2. Backend generate presigned URL, trả về Frontend             │
│  3. Browser gửi OPTIONS (preflight) → S3 check CORS             │
│  4. S3 trả về OK → Browser gửi PUT với file                     │
│  5. Upload thành công                                           │
│                                                                 │
└─────────────────────────────────────────────────────────────────┘
```

---

## Liên quan

- [CORS & Preflight Request](./CORS_PREFLIGHT.md) - Giải thích chi tiết về CORS và Preflight
- [S3 Presigned URL](./S3_PRESIGNED_URL.md) - Upload/download qua presigned URL

---

## Official Documentation

- [CORS Configuration](https://docs.aws.amazon.com/AmazonS3/latest/userguide/cors.html)
- [Configuring CORS](https://docs.aws.amazon.com/AmazonS3/latest/userguide/ManageCorsUsing.html)

---

*Document created: 2026-01-15*
*Project: realworld-exam*
