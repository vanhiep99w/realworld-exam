# S3 Lifecycle Policies

Tự động quản lý object lifecycle: chuyển storage class, xóa objects, cleanup multipart uploads.

---

## Overview

**Cơ chế hoạt động:** AWS chạy background job **1 lần/ngày** (không real-time), scan tất cả objects và apply rules dựa trên **tuổi của object** (creation date).

```
┌─────────────────────────────────────────────────────────────────────┐
│                         OBJECT LIFECYCLE                            │
├─────────────────────────────────────────────────────────────────────┤
│                                                                     │
│  Day 0          Day 30           Day 90           Day 365           │
│    │              │                │                │               │
│    ▼              ▼                ▼                ▼               │
│ ┌──────┐    ┌───────────┐    ┌─────────┐    ┌────────────┐         │
│ │UPLOAD│───▶│STANDARD_IA│───▶│ GLACIER │───▶│  DELETED   │         │
│ └──────┘    └───────────┘    └─────────┘    └────────────┘         │
│  $0.023/GB    $0.0125/GB      $0.004/GB       $0 (gone)            │
│                                                                     │
└─────────────────────────────────────────────────────────────────────┘
```

---

## Chi phí

**Lifecycle Policies miễn phí** - không tốn tiền để tạo hay chạy rules.

| Scenario | Không có Lifecycle | Có Lifecycle |
|----------|-------------------|--------------|
| 100GB exports, giữ mãi | $2.3/tháng mãi mãi | $0 sau 7 ngày |
| 1TB logs, 1 năm | $276/năm (STANDARD) | ~$48/năm (→ Glacier) |

**Lưu ý:** Nếu dùng Glacier rồi cần lấy lại data → tốn **retrieval fee** (~$0.01/GB).

---

## Khi nào nên dùng Lifecycle Policies?

### ✅ Phù hợp

| Use Case | Lý do |
|----------|-------|
| **Incomplete multipart uploads** | Rác hệ thống, không ai reference |
| **Old versions (với Versioning)** | DB chỉ link current version |
| **Logs, temp files** | Không link DB |
| **Export files không track trong DB** | Không có FK constraint |

### ❌ Không phù hợp

| Use Case | Vấn đề | Giải pháp |
|----------|--------|-----------|
| **User uploads có link DB** | Xóa S3 → orphan records trong DB | App tự quản lý xóa cả 2 |
| **Files có FK trong database** | Data inconsistency | Dùng S3 Event Notifications + App sync |

---

## Use Case 1: Cleanup Incomplete Multipart Uploads

**Vấn đề:** Upload file lớn bị fail giữa chừng → các parts đã upload **vẫn nằm trong S3** và **tốn tiền storage**.

```
┌─────────────────────────────────────────────────────┐
│  Upload 100MB file (20 parts x 5MB)                 │
├─────────────────────────────────────────────────────┤
│  Part 1 ✅  Part 2 ✅  Part 3 ✅  Part 4 ❌ FAIL    │
│                                                     │
│  → 15MB "rác" nằm trong S3, không thấy trong UI     │
│  → Vẫn bị charge $$$                                │
└─────────────────────────────────────────────────────┘
```

**Lifecycle rule:**
```json
{
  "ID": "CleanupIncompleteUploads",
  "Status": "Enabled",
  "Filter": {},
  "AbortIncompleteMultipartUpload": {
    "DaysAfterInitiation": 1
  }
}
```

---

## Use Case 2: Delete Old Versions

**Khi bật Versioning:** Mỗi lần overwrite file → version cũ vẫn được giữ lại.

```
┌─────────────────────────────────────────────────────┐
│  Object: avatar.jpg                                 │
├─────────────────────────────────────────────────────┤
│  Version 3 (current)  ← User thấy cái này          │
│  Version 2 (old)      ← Ẩn, vẫn tốn tiền           │
│  Version 1 (old)      ← Ẩn, vẫn tốn tiền           │
└─────────────────────────────────────────────────────┘
```

**Lifecycle rule:**
```json
{
  "ID": "DeleteOldVersions",
  "Status": "Enabled",
  "NoncurrentVersionExpiration": {
    "NoncurrentDays": 30
  }
}
```

→ DB chỉ link tới **current version**, xóa old versions không ảnh hưởng gì!

---

## Bucket Organization

Thường 1 bucket chứa nhiều loại files, dùng **prefixes** để phân loại:

```
my-app-bucket/
├── avatars/           ← User uploads (KHÔNG dùng lifecycle)
├── exports/           ← CSV exports  
├── documents/         ← PDF, docs
├── temp/              ← Temp files
└── logs/              ← Application logs
```

**Lifecycle rules theo prefix:**
```json
{
  "Rules": [
    {
      "ID": "DeleteTempFiles",
      "Filter": { "Prefix": "temp/" },
      "Status": "Enabled",
      "Expiration": { "Days": 1 }
    },
    {
      "ID": "DeleteLogs",
      "Filter": { "Prefix": "logs/" },
      "Status": "Enabled",
      "Expiration": { "Days": 90 }
    },
    {
      "ID": "CleanupIncompleteUploads",
      "Filter": {},
      "Status": "Enabled",
      "AbortIncompleteMultipartUpload": { "DaysAfterInitiation": 1 }
    }
  ]
}
```

---

## Xử lý khi S3 object có link DB

Nếu S3 objects được reference từ database records:

### Pattern 1: App quản lý lifecycle (Recommended)
```
App tự xóa: DELETE DB record → DELETE S3 object
Không dùng S3 Lifecycle cho data này!
```

### Pattern 2: S3 Event Notification + Sync
```
┌──────────┐  s3:ObjectRemoved   ┌─────────┐   ┌──────────┐
│    S3    │────────────────────▶│   SQS   │──▶│   App    │
└──────────┘                     └─────────┘   └──────────┘
                                                    │
                                                    ▼
                                              Update DB record
                                              (mark as deleted)
```

### Pattern 3: Soft Delete + Sync Job
```
Cron job hàng ngày:
1. Query DB lấy tất cả file references
2. Check S3 xem file còn không
3. Mark deleted trong DB nếu file không còn
```

---

## Testing với LocalStack

```bash
# Tạo lifecycle configuration
aws --endpoint-url=http://localhost:4566 s3api put-bucket-lifecycle-configuration \
  --bucket my-bucket \
  --lifecycle-configuration file://lifecycle.json

# Xem lifecycle configuration
aws --endpoint-url=http://localhost:4566 s3api get-bucket-lifecycle-configuration \
  --bucket my-bucket
```

**Lưu ý:** LocalStack không tự động execute lifecycle rules như AWS. Để test, cần trigger manually hoặc dùng LocalStack Pro.

---

## Official Documentation

- [Object Lifecycle Management](https://docs.aws.amazon.com/AmazonS3/latest/userguide/object-lifecycle-mgmt.html)
- [Lifecycle Configuration Elements](https://docs.aws.amazon.com/AmazonS3/latest/userguide/intro-lifecycle-rules.html)
- [Managing Multipart Uploads](https://docs.aws.amazon.com/AmazonS3/latest/userguide/mpuoverview.html#mpu-abort-incomplete-mpu-lifecycle-config)

---

*Document created: 2026-01-15*
*Project: realworld-exam*
