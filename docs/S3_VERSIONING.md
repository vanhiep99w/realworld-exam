# S3 Versioning

Lưu tất cả versions của object khi bị overwrite/delete → có thể rollback.

---

## Overview

**Mục đích:** Safety net ở infrastructure level, bảo vệ data khỏi bị mất do overwrite/delete nhầm.

```
┌─────────────────────────────────────────────────────────────────┐
│  avatar.jpg được upload 3 lần                                   │
├─────────────────────────────────────────────────────────────────┤
│                                                                 │
│  Upload 1        Upload 2         Upload 3                      │
│     │               │                │                          │
│     ▼               ▼                ▼                          │
│  ┌──────┐       ┌──────┐        ┌──────┐                       │
│  │ v1   │       │ v2   │        │ v3   │  ← Current            │
│  │(old) │       │(old) │        │      │                       │
│  └──────┘       └──────┘        └──────┘                       │
│                                                                 │
│  GET avatar.jpg → trả về v3                                     │
│  GET avatar.jpg?versionId=xxx → trả về version cụ thể          │
└─────────────────────────────────────────────────────────────────┘
```

---

## Versioning States

Bucket có **3 trạng thái**, một khi đã bật thì không thể quay về Unversioned:

```
┌─────────────────────────────────────────────────────────────────┐
│                     VERSIONING STATES                           │
├─────────────────────────────────────────────────────────────────┤
│                                                                 │
│  ┌──────────────┐    Enable    ┌──────────────┐                │
│  │  Unversioned │ ───────────▶ │   Enabled    │                │
│  │  (default)   │              │              │                │
│  └──────────────┘              └──────────────┘                │
│         │                            │                          │
│         │                            │ Suspend                  │
│         │                            ▼                          │
│         │                      ┌──────────────┐                │
│         │                      │  Suspended   │                │
│         │                      │              │                │
│         │                      └──────────────┘                │
│         │                            │                          │
│         └────────── ✗ ───────────────┘                         │
│              Không thể quay về Unversioned!                     │
└─────────────────────────────────────────────────────────────────┘
```

| State | Behavior |
|-------|----------|
| **Unversioned** | Mặc định, upload mới → ghi đè cũ (mất luôn) |
| **Enabled** | Mỗi upload tạo version mới, version cũ vẫn giữ |
| **Suspended** | Objects mới không tạo version, objects cũ vẫn còn |

---

## Delete Marker

Khi versioning **Enabled**, DELETE không xóa thật mà tạo **Delete Marker**:

```
┌─────────────────────────────────────────────────────────────────┐
│  Trước DELETE                                                   │
├─────────────────────────────────────────────────────────────────┤
│                                                                 │
│  avatar.jpg                                                     │
│  ├── Version: abc123 (current) ← GET trả về cái này            │
│  ├── Version: def456                                            │
│  └── Version: ghi789                                            │
│                                                                 │
└─────────────────────────────────────────────────────────────────┘

                    DELETE avatar.jpg
                           │
                           ▼

┌─────────────────────────────────────────────────────────────────┐
│  Sau DELETE                                                     │
├─────────────────────────────────────────────────────────────────┤
│                                                                 │
│  avatar.jpg                                                     │
│  ├── Delete Marker (current) ← GET trả về 404!                 │
│  ├── Version: abc123          ← Data vẫn còn!                  │
│  ├── Version: def456          ← Data vẫn còn!                  │
│  └── Version: ghi789          ← Data vẫn còn!                  │
│                                                                 │
└─────────────────────────────────────────────────────────────────┘
```

**Delete Marker là gì?**
- Một "version đặc biệt" đánh dấu object đã bị xóa
- Không chứa data, chỉ là marker
- Khi GET → S3 thấy Delete Marker → trả về 404

---

## Cách Restore

### Case A: Restore sau khi bị overwrite

```
Tình huống: Upload file mới ghi đè file cũ, muốn lấy lại bản cũ

┌─────────────────────────────────────────────────────────────────┐
│  avatar.jpg                                                     │
│  ├── Version: NEW111 (current) ← File mới (sai)                │
│  └── Version: OLD222           ← File cũ (muốn lấy lại)        │
└─────────────────────────────────────────────────────────────────┘
```

**Cách 1: Copy version cũ thành version mới**
```bash
aws s3api copy-object \
  --bucket my-bucket \
  --key avatar.jpg \
  --copy-source "my-bucket/avatar.jpg?versionId=OLD222"
```

**Cách 2: Xóa version mới (permanent delete)**
```bash
aws s3api delete-object \
  --bucket my-bucket \
  --key avatar.jpg \
  --version-id NEW111
```

### Case B: Restore sau khi bị DELETE (có Delete Marker)

```
Tình huống: Ai đó DELETE file, muốn khôi phục

┌─────────────────────────────────────────────────────────────────┐
│  avatar.jpg                                                     │
│  ├── Delete Marker: DEL999 (current) ← GET → 404               │
│  └── Version: abc123                  ← Data vẫn còn           │
└─────────────────────────────────────────────────────────────────┘
```

**Giải pháp: Xóa Delete Marker**
```bash
aws s3api delete-object \
  --bucket my-bucket \
  --key avatar.jpg \
  --version-id DEL999
```

→ File "sống lại"!

---

## Quản lý Old Versions

**Mặc định:** S3 giữ **tất cả versions vĩnh viễn** → tốn rất nhiều tiền storage!

**Giải pháp:** Dùng **Lifecycle Policy** để tự động xóa old versions:

```json
{
  "Rules": [{
    "ID": "DeleteOldVersions",
    "Status": "Enabled",
    "NoncurrentVersionExpiration": {
      "NoncurrentDays": 30,
      "NewerNoncurrentVersions": 3
    }
  }]
}
```

| Strategy | Lifecycle Rule | Use Case |
|----------|---------------|----------|
| Time-based | Xóa versions > 30 ngày | General purpose |
| Count-based | Giữ 5 versions gần nhất | Frequent updates |
| Hybrid | Giữ 3 versions HOẶC 7 ngày | Balance cost/safety |

→ **Best practice:** Luôn bật Lifecycle khi dùng Versioning!

---

## Ai quản lý Versioning?

| Layer | Responsibility |
|-------|---------------|
| **Ops/DevOps** | Bật versioning, set lifecycle, restore khi có sự cố |
| **App** | Upload/download bình thường, **không cần biết** versioning tồn tại |

```
┌─────────────────────────────────────────────────────────────────┐
│  App code vẫn như cũ:                                           │
│                                                                 │
│  s3.putObject("avatar.jpg", data)   // Upload bình thường       │
│  s3.getObject("avatar.jpg")         // Get bình thường          │
│  s3.deleteObject("avatar.jpg")      // Delete bình thường       │
│                                                                 │
│  → S3 tự động giữ versions phía sau, app không cần care        │
└─────────────────────────────────────────────────────────────────┘
```

**Khi nào app CẦN quản lý versions?**
- CMS cần "revision history" (như Google Docs)
- Compliance app cần audit trail
- File collaboration với "view previous versions"

→ Những cases này thường **tự build version tracking trong DB** thay vì dùng S3 versioning.

---

## CLI Commands

| Muốn làm gì | Command |
|-------------|---------|
| Bật versioning | `aws s3api put-bucket-versioning --bucket my-bucket --versioning-configuration Status=Enabled` |
| Xem tất cả versions | `aws s3api list-object-versions --bucket my-bucket` |
| Download version cụ thể | `aws s3api get-object --bucket my-bucket --key file.txt --version-id xxx output.txt` |
| Restore bằng copy | `aws s3api copy-object --copy-source "bucket/key?versionId=xxx" ...` |
| Xóa vĩnh viễn 1 version | `aws s3api delete-object --version-id xxx ...` |
| Undelete (xóa Delete Marker) | `aws s3api delete-object --version-id <delete-marker-id> ...` |

---

## Official Documentation

- [Using Versioning in S3 Buckets](https://docs.aws.amazon.com/AmazonS3/latest/userguide/Versioning.html)
- [Working with Delete Markers](https://docs.aws.amazon.com/AmazonS3/latest/userguide/DeleteMarker.html)
- [Managing Object Versions](https://docs.aws.amazon.com/AmazonS3/latest/userguide/manage-objects-versioned-bucket.html)

---

*Document created: 2026-01-15*
*Project: realworld-exam*
