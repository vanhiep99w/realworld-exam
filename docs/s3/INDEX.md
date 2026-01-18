# S3 Learning Roadmap

Danh sách các chủ đề S3 có thể học, sắp xếp theo độ khó và tính thực tế.

---

## ✅ Đã Implement

| # | Topic | Description | Docs |
|---|-------|-------------|------|
| 1 | **Presigned URL (PUT/POST/GET)** | Upload/download trực tiếp từ browser | [S3_PRESIGNED_URL.md](./S3_PRESIGNED_URL.md) |
| 2 | **Multipart Upload** | Upload file lớn theo từng phần 5MB | [LARGE_DATA_EXPORT.md](./LARGE_DATA_EXPORT.md) |
| 3 | **S3 Lifecycle Policies** | Tự động xóa/chuyển storage class | [S3_LIFECYCLE_POLICIES.md](./S3_LIFECYCLE_POLICIES.md) |
| 4 | **S3 Versioning** | Lưu nhiều version của object | [S3_VERSIONING.md](./S3_VERSIONING.md) |
| 5 | **CORS & Preflight** | Cross-origin resource sharing | [CORS_PREFLIGHT.md](./CORS_PREFLIGHT.md) |
| 6 | **S3 CORS Configuration** | CORS config cho S3 bucket | [S3_CORS.md](./S3_CORS.md) |
| 7 | **S3 Bucket Policies** | IAM policies cho bucket | [S3_BUCKET_POLICIES.md](./S3_BUCKET_POLICIES.md) |
| 8 | **S3 Event Notifications** | Trigger Lambda/SQS/SNS khi có event | [S3_EVENT_NOTIFICATIONS.md](./S3_EVENT_NOTIFICATIONS.md) |
| 9 | **S3 Select** | Query CSV/JSON trực tiếp trên S3 | [S3_SELECT.md](./S3_SELECT.md) |

---

## 📋 Đã Document (Chưa Implement)

| # | Topic | Description | Docs |
|---|-------|-------------|------|
| 10 | **S3 Transfer Acceleration** | Upload nhanh hơn qua CloudFront edge | [S3_TRANSFER_ACCELERATION.md](./S3_TRANSFER_ACCELERATION.md) |
| 11 | **S3 Object Lock** | WORM (Write Once Read Many) | [S3_OBJECT_LOCK.md](./S3_OBJECT_LOCK.md) |
| 12 | **S3 Replication** | Copy objects sang bucket khác (SRR/CRR) | [S3_REPLICATION.md](./S3_REPLICATION.md) |
| 13 | **CloudFront + Signed URLs** | CDN + authentication cho private content | [CLOUDFRONT_SIGNED_URLS.md](./CLOUDFRONT_SIGNED_URLS.md) |
| 14 | **S3 Access Points** | Simplified permissions + Multi-Region routing | [S3_ACCESS_POINTS.md](./S3_ACCESS_POINTS.md) |
| 15 | **S3 Batch Operations** | Bulk operations trên millions objects (Ops/DevOps) | [S3_BATCH_OPERATIONS.md](./S3_BATCH_OPERATIONS.md) |
| 16 | **S3 Glacier Deep Archive** | Lowest cost archival storage + Lifecycle | [S3_GLACIER_DEEP_ARCHIVE.md](./S3_GLACIER_DEEP_ARCHIVE.md) |

--- 

## ⏸️ Pending (Không cần doc/impl)

| # | Topic | Description | Use Case |
|---|-------|-------------|----------|
| 17 | **S3 Inventory** | Report về objects trong bucket | Audit, compliance, cost analysis |
| 18 | **S3 Cross-Region Replication** | DR/backup sang region khác | Disaster recovery, data residency |
| 19 | **S3 Object Lambda** | Transform data on-the-fly | Redact PII, resize images on download |

---

## 🎯 Recommended Learning Path

```
┌─────────────────────────────────────────────────────────────┐
│  Week 1-2: Foundation                                       │
│  ✅ Presigned URLs (done)                                   │
│  ✅ Multipart Upload (done)                                 │
│  → Lifecycle Policies                                       │
│  → Versioning                                               │
└─────────────────────────────────────────────────────────────┘
                              │
                              ▼
┌─────────────────────────────────────────────────────────────┐
│  Week 3-4: Event-Driven Architecture                        │
│  → S3 Event Notifications + SQS                             │
│  → S3 Event Notifications + Lambda                          │
│  → Image processing pipeline                                │
└─────────────────────────────────────────────────────────────┘
                              │
                              ▼
┌─────────────────────────────────────────────────────────────┐
│  Week 5-6: Performance & Analytics                          │
│  → S3 Select                                                │
│  → Transfer Acceleration                                    │
│  → S3 Inventory                                             │
└─────────────────────────────────────────────────────────────┘
                              │
                              ▼
┌─────────────────────────────────────────────────────────────┐
│  Week 7-8: CDN & Global Distribution                        │
│  → CloudFront + S3                                          │
│  → Signed URLs/Cookies                                      │
│  → Cross-Region Replication                                 │
└─────────────────────────────────────────────────────────────┘
```

---

## 📖 Chi Tiết Từng Topic

### 3. S3 Lifecycle Policies
```yaml
Rules:
  - Transition to STANDARD_IA after 30 days
  - Transition to GLACIER after 90 days  
  - Delete after 365 days
  - Delete incomplete multipart uploads after 7 days
```

**Practical example:** Auto-delete export files sau 7 ngày

---

### 7. S3 Event Notifications

```
┌─────────┐    s3:ObjectCreated    ┌─────────┐    ┌──────────────┐
│   S3    │ ──────────────────────▶│   SQS   │───▶│ Spring Boot  │
│ Bucket  │                        │  Queue  │    │   Listener   │
└─────────┘                        └─────────┘    └──────────────┘
                                                         │
                                                         ▼
                                                  ┌──────────────┐
                                                  │ Process File │
                                                  │ (resize,     │
                                                  │  scan, etc.) │
                                                  └──────────────┘
```

**Practical example:** Upload image → Lambda resize → Save thumbnail

---

### 8. S3 Select

```sql
-- Query CSV trực tiếp trên S3 (không download)
SELECT s.id, s.email 
FROM S3Object s 
WHERE s.created_at > '2026-01-01'
LIMIT 100
```

**Practical example:** Preview 100 rows từ exported CSV

---

### 13. CloudFront + Signed URLs

```
┌────────┐     ┌────────────────┐     ┌─────────┐
│ Client │ ───▶│   CloudFront   │────▶│   S3    │
│        │     │  (Edge Cache)  │     │ (Origin)│
└────────┘     └────────────────┘     └─────────┘
     │
     └── Signed URL với expiry + IP restriction
```

**Practical example:** Video streaming với geographic restriction

---

## 🔗 Official Documentation

| Topic | AWS Docs |
|-------|----------|
| Lifecycle | https://docs.aws.amazon.com/AmazonS3/latest/userguide/object-lifecycle-mgmt.html |
| Versioning | https://docs.aws.amazon.com/AmazonS3/latest/userguide/Versioning.html |
| Event Notifications | https://docs.aws.amazon.com/AmazonS3/latest/userguide/EventNotifications.html |
| S3 Select | https://docs.aws.amazon.com/AmazonS3/latest/userguide/selecting-content-from-objects.html |
| Transfer Acceleration | https://docs.aws.amazon.com/AmazonS3/latest/userguide/transfer-acceleration.html |
| CloudFront + S3 | https://docs.aws.amazon.com/AmazonCloudFront/latest/DeveloperGuide/DownloadDistS3AndCustomOrigins.html |
| Cross-Region Replication | https://docs.aws.amazon.com/AmazonS3/latest/userguide/replication.html |
| Batch Operations | https://docs.aws.amazon.com/AmazonS3/latest/userguide/batch-ops.html |
| Object Lambda | https://docs.aws.amazon.com/AmazonS3/latest/userguide/transforming-objects.html |

---

## 💡 Next Step Recommendation

**Start with:** S3 Event Notifications + SQS/Lambda

Lý do:
- Rất practical (image processing, file validation, notifications)
- Giới thiệu event-driven architecture
- Có thể test với LocalStack
- Builds on what you already know (upload flow)

---

*Document created: 2026-01-15*
*Project: realworld-exam*
