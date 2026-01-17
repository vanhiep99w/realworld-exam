# S3 Glacier Deep Archive - Documentation

## Mục lục
1. [Tổng quan](#tổng-quan)
2. [Storage Classes](#storage-classes)
3. [Pricing](#pricing)
4. [Lifecycle Strategy](#lifecycle-strategy)
5. [Restore Flow](#restore-flow)
6. [App Integration](#app-integration)
7. [Tài liệu tham khảo](#tài-liệu-tham-khảo)

---

## Tổng quan

S3 Glacier Deep Archive là storage class rẻ nhất của AWS cho long-term archival (lưu trữ lâu dài).

```
Storage Classes (giá từ cao → thấp):

┌──────────────────┐
│  S3 Standard     │  $0.023/GB  ← access thường xuyên
├──────────────────┤
│  S3 Standard-IA  │  $0.0125/GB ← access < 1 lần/tháng
├──────────────────┤
│  S3 Glacier IR   │  $0.004/GB  ← archive, restore vài phút
├──────────────────┤
│  S3 Glacier FR   │  $0.0036/GB ← archive, restore 3-5h
├──────────────────┤
│  Glacier Deep    │  $0.00099/GB ← RẺ NHẤT, restore 12-48h
└──────────────────┘
```

### Đặc điểm chính

| Đặc điểm | Mô tả |
|----------|-------|
| **Giá storage** | $0.00099/GB/tháng (~$1/TB/tháng) |
| **Retrieval time** | 12-48 giờ (Standard/Bulk) |
| **Min storage** | 180 ngày (tính phí nếu xóa sớm) |
| **Min object size** | 40KB (objects nhỏ hơn vẫn tính 40KB) |
| **Use case** | Data cần giữ 7-10 năm, hiếm khi access |

### Tại sao restore lâu?

```
Deep Archive = data lưu trên offline storage (tape-like)
             → AWS phải physically retrieve data
             → Không instant như SSD/HDD
```

---

## Storage Classes

### So sánh chi tiết

| Storage Class | Cost/GB/tháng | Retrieval Time | Min Storage | Use Case |
|---------------|---------------|----------------|-------------|----------|
| **Standard** | $0.023 | Instant | - | Access hàng ngày |
| **Standard-IA** | $0.0125 | Instant | 30 days | Access < 1 lần/tháng |
| **One Zone-IA** | $0.01 | Instant | 30 days | Non-critical, ít access |
| **Glacier Instant** | $0.004 | Milliseconds | 90 days | Archive, cần access ngay |
| **Glacier Flexible** | $0.0036 | 1 phút - 12 giờ | 90 days | Archive, chờ được vài giờ |
| **Deep Archive** | $0.00099 | 12-48 giờ | 180 days | Long-term, hiếm access |

### Retrieval Tiers (Glacier/Deep Archive)

| Storage Class | Tier | Time | Cost/GB |
|---------------|------|------|---------|
| **Glacier Flexible** | Expedited | 1-5 phút | $0.03 |
| | Standard | 3-5 giờ | $0.01 |
| | Bulk | 5-12 giờ | $0.0025 |
| **Deep Archive** | Standard | 12 giờ | $0.02 |
| | Bulk | 48 giờ | $0.0025 |

**Lưu ý:** Deep Archive không có Expedited tier.

---

## Pricing

### Storage cost (1TB/tháng)

| Storage Class | Cost/tháng |
|---------------|------------|
| S3 Standard | $23.00 |
| S3 Standard-IA | $12.50 |
| S3 Glacier Flexible | $3.60 |
| **S3 Deep Archive** | **$0.99** |

### Retrieval cost

| Storage Class | Retrieval/GB |
|---------------|--------------|
| Standard | Free |
| Standard-IA | $0.01 |
| Glacier Instant | $0.03 |
| Glacier Flexible | $0.01-0.03 |
| Deep Archive (Standard) | $0.02 |
| Deep Archive (Bulk) | $0.0025 |

### Ví dụ chi phí

**Lưu 10TB trong 1 năm:**

| Storage Class | Storage Cost | Retrieval (1 lần, 100GB) | Total |
|---------------|--------------|--------------------------|-------|
| Standard | $2,760 | Free | $2,760 |
| Deep Archive | $119 | $2 (Bulk) | $121 |
| **Tiết kiệm** | | | **$2,639 (96%)** |

---

## Lifecycle Strategy

### Cách phổ biến nhất: Lifecycle Rules

```
┌──────────────┐   30 days   ┌──────────────┐   90 days   ┌──────────────┐
│  S3 Standard │ ──────────▶ │  Standard-IA │ ──────────▶ │   Glacier    │
└──────────────┘   (auto)    └──────────────┘   (auto)    │   Flexible   │
                                                          └──────────────┘
                                                                 │
                                                            365 days
                                                                 ▼
                                                          ┌──────────────┐
                                                          │ Deep Archive │
                                                          └──────────────┘
                                                                 │
                                                            7 years
                                                                 ▼
                                                          ┌──────────────┐
                                                          │   Deleted    │
                                                          └──────────────┘
```

### Ví dụ theo Use Case

**1. User uploads (avatars, documents):**
```yaml
Lifecycle:
  - 0-30 days: Standard (user hay access)
  - 30-180 days: Standard-IA (ít access hơn)
  - 180+ days: Glacier Flexible
  # Không dùng Deep Archive (user có thể cần lại nhanh)
```

**2. Logs:**
```yaml
Lifecycle:
  - 0-7 days: Standard (debug, monitoring)
  - 7-30 days: Standard-IA
  - 30-90 days: Glacier Flexible
  - 90-365 days: Deep Archive
  - 365+ days: Delete
```

**3. Database Backup:**
```yaml
Lifecycle:
  - 0-7 days: Standard (quick restore)
  - 7-30 days: Standard-IA
  - 30-365 days: Glacier Flexible
  - 365+ days: Deep Archive (compliance 7 năm)
```

**4. Video/Media (streaming):**
```yaml
Lifecycle:
  - 0-90 days: Standard (mới upload, hay xem)
  - 90-365 days: Standard-IA
  - 365+ days: Glacier Instant (vẫn cần stream được ngay)
  # Không dùng Flexible/Deep vì user cần xem ngay
```

### Setup Lifecycle Rule

```json
{
  "Rules": [
    {
      "ID": "ArchiveOldFiles",
      "Status": "Enabled",
      "Filter": { "Prefix": "uploads/" },
      "Transitions": [
        { "Days": 30, "StorageClass": "STANDARD_IA" },
        { "Days": 90, "StorageClass": "GLACIER" },
        { "Days": 365, "StorageClass": "DEEP_ARCHIVE" }
      ],
      "Expiration": { "Days": 2555 }
    }
  ]
}
```

### Tips

| Tip | Lý do |
|-----|-------|
| Min 30 days trước khi move to IA | IA có min storage 30 days |
| Min 90 days trước khi move to Glacier | Glacier có min 90 days |
| Deep Archive min 180 days | Min storage 180 days |
| Tính toán retrieval cost | Nếu access thường xuyên, IA/Glacier đắt hơn Standard |

---

## Restore Flow

### Restore không phải move storage class

```
┌─────────────────────────────────────────────────────────────────────┐
│  Trước restore:                                                     │
│                                                                     │
│  file.zip [DEEP_ARCHIVE] ← không access được                       │
│                                                                     │
├─────────────────────────────────────────────────────────────────────┤
│  Sau restore (12-48h):                                              │
│                                                                     │
│  file.zip [DEEP_ARCHIVE] ← vẫn ở Glacier (không đổi)               │
│      │                                                              │
│      └──▶ [Temporary copy] ← có thể download (7 ngày)              │
│                                                                     │
├─────────────────────────────────────────────────────────────────────┤
│  Sau 7 ngày:                                                        │
│                                                                     │
│  file.zip [DEEP_ARCHIVE] ← vẫn ở đây                               │
│  [Temporary copy] ← tự động xóa                                    │
│                                                                     │
└─────────────────────────────────────────────────────────────────────┘
```

### Request Restore

```java
s3.restoreObject(r -> r
    .bucket(bucket)
    .key(key)
    .restoreRequest(rr -> rr
        .days(7)  // Temp copy available trong 7 ngày
        .glacierJobParameters(g -> g.tier(Tier.STANDARD))  // 12h
    )
);
```

### Muốn move về Standard thật sự?

```java
// Phải restore trước, rồi mới copy được
CopyObjectRequest copy = CopyObjectRequest.builder()
    .sourceBucket(bucket)
    .sourceKey(key)
    .destinationBucket(bucket)
    .destinationKey(key)
    .storageClass(StorageClass.STANDARD)
    .build();

s3.copyObject(copy);
```

---

## App Integration

### Vấn đề: Presigned URL / CloudFront không hoạt động với Glacier

```
User click presigned URL → Object ở DEEP_ARCHIVE
                         → S3 return 403 InvalidObjectState
                         → User không download được!
```

### Giải pháp: App phải check storage class

```java
public DownloadResponse getDownloadUrl(String bucket, String key) {
    // 1. Check storage class
    HeadObjectResponse head = s3.headObject(r -> r.bucket(bucket).key(key));
    
    StorageClass storageClass = head.storageClass();
    
    // 2. Nếu ở Glacier/Deep Archive
    if (storageClass == StorageClass.GLACIER || 
        storageClass == StorageClass.DEEP_ARCHIVE) {
        
        String restoreStatus = head.restore();
        
        // Chưa request restore
        if (restoreStatus == null) {
            return DownloadResponse.builder()
                .status("ARCHIVED")
                .message("File đang lưu trữ. Click 'Restore' để khôi phục (12-48h)")
                .build();
        }
        
        // Đang restore
        if (restoreStatus.contains("ongoing-request=\"true\"")) {
            return DownloadResponse.builder()
                .status("RESTORING")
                .message("Đang khôi phục, vui lòng quay lại sau...")
                .build();
        }
        
        // Đã restore xong
        if (restoreStatus.contains("ongoing-request=\"false\"")) {
            return DownloadResponse.builder()
                .status("READY")
                .url(generatePresignedUrl(bucket, key))
                .build();
        }
    }
    
    // 3. Standard/IA → download ngay
    return DownloadResponse.builder()
        .status("READY")
        .url(generatePresignedUrl(bucket, key))
        .build();
}
```

### Làm sao App biết storage class?

**Option A: Query S3 trực tiếp (realtime)**
```java
HeadObjectResponse head = s3.headObject(r -> r.bucket(bucket).key(key));
StorageClass storageClass = head.storageClass();
```
- ✅ Chính xác 100%
- ❌ Thêm 1 API call mỗi lần

**Option B: Tính theo thời gian (đơn giản)**
```java
public String getStorageClass(Document doc) {
    long daysSinceUpload = ChronoUnit.DAYS.between(doc.uploadedAt, LocalDate.now());
    
    if (daysSinceUpload > 365) return "DEEP_ARCHIVE";
    if (daysSinceUpload > 90) return "GLACIER";
    if (daysSinceUpload > 30) return "STANDARD_IA";
    return "STANDARD";
}
```
- ✅ Không cần call S3, nhanh
- ❌ Phải hardcode lifecycle rules

**Option C: S3 Event Notification (chính xác)**
```
S3 Lifecycle transition → S3 Event → SQS/Lambda → Update DB
```
- ✅ Chính xác, realtime
- ❌ Setup phức tạp

**Option D: Hybrid (thực tế nhất)**
```java
public DownloadResponse download(Long docId) {
    Document doc = documentRepo.findById(docId);
    
    // 1. Estimate từ DB trước
    String estimatedClass = estimateStorageClass(doc);
    
    // 2. Nếu có thể archived → verify với S3
    if (estimatedClass.contains("GLACIER")) {
        HeadObjectResponse head = s3.headObject(...);
        updateStorageClassIfChanged(doc, head.storageClass());
        return handleArchivedObject(doc, head);
    }
    
    // 3. Nếu STANDARD → generate URL luôn
    return generatePresignedUrl(doc);
}
```

### Recommend theo project size

| Project size | Approach |
|--------------|----------|
| Nhỏ, ít files | Query S3 mỗi lần (đơn giản) |
| Trung bình | Tính theo thời gian + verify khi cần |
| Lớn, enterprise | S3 Event → sync DB realtime |

---

## Use Cases

| Use Case | Mô tả |
|----------|-------|
| **Compliance** | Giữ data 7-10 năm theo luật (healthcare, finance) |
| **Backup tapes replacement** | Thay thế băng từ truyền thống |
| **Media archives** | Raw footage, master copies |
| **Scientific data** | Research data lưu vĩnh viễn |
| **Audit logs** | Logs cần giữ lâu nhưng hiếm khi access |

---

## Tài liệu tham khảo

| Chủ đề | Link |
|--------|------|
| **Storage Classes** | https://docs.aws.amazon.com/AmazonS3/latest/userguide/storage-class-intro.html |
| **Glacier Deep Archive** | https://docs.aws.amazon.com/AmazonS3/latest/userguide/storage-class-intro.html#sc-glacier-deep-archive |
| **Restoring Objects** | https://docs.aws.amazon.com/AmazonS3/latest/userguide/restoring-objects.html |
| **Lifecycle Configuration** | https://docs.aws.amazon.com/AmazonS3/latest/userguide/object-lifecycle-mgmt.html |

---

## Kết luận

- **Deep Archive** = rẻ nhất, nhưng restore 12-48h
- **Lifecycle Rules** = cách phổ biến nhất để tự động archive (DevOps setup)
- **App phải handle** storage class khi generate presigned URL / download
- **Dùng cho:** Data hiếm access (1-2 lần/năm), cần giữ lâu (7+ năm)
- **Không dùng cho:** Data cần access nhanh, user-facing content

---

*Ngày tạo: 2026-01-17*
*Status: 📋 Documented (Not Implemented)*
*Project: realworld-exam*
