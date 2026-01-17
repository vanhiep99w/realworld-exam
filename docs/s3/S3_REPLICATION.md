# S3 Replication - Documentation

## Mục lục
1. [Tổng quan](#tổng-quan)
2. [Khái niệm](#khái-niệm)
3. [Use Cases](#use-cases)
4. [So sánh với Backup](#so-sánh-với-backup)
5. [Cấu hình](#cấu-hình)
6. [Tài liệu tham khảo](#tài-liệu-tham-khảo)

---

## Tổng quan

S3 Replication tự động copy objects từ source bucket sang destination bucket. Có 2 loại:
- **SRR (Same-Region Replication):** Copy trong cùng region
- **CRR (Cross-Region Replication):** Copy sang region khác (phổ biến hơn cho DR)

```
┌─────────────────┐         Async Copy          ┌─────────────────┐
│  Source Bucket  │ ──────────────────────────▶ │   Dest Bucket   │
│  (uploads here) │                             │ (replica here)  │
└─────────────────┘                             └─────────────────┘
         │                                               │
         └────────── Versioning: REQUIRED ──────────────┘
```

### Đặc điểm chính

| Đặc điểm | Mô tả |
|----------|-------|
| **Versioning** | Bắt buộc ở cả 2 buckets |
| **Async** | Có độ trễ (thường < 15 phút) |
| **Filter** | Có thể filter theo prefix hoặc tags |
| **Delete** | Có thể sync delete markers (optional) |
| **IAM** | Cần IAM role để S3 có quyền copy |

---

## Khái niệm

### Delete Marker Replication

Khi xóa object ở source, có 2 behavior tùy config:

```
┌─────────────────────────────────────────────────────────────┐
│  DeleteMarkerReplication: Enabled                           │
│  → Xóa ở source → Delete marker sync sang dest              │
│  → File "biến mất" ở cả 2 buckets                           │
├─────────────────────────────────────────────────────────────┤
│  DeleteMarkerReplication: Disabled (default)                │
│  → Xóa ở source → Dest vẫn giữ file                         │
│  → Dùng cho BACKUP (source bị xóa nhầm, dest vẫn còn)       │
└─────────────────────────────────────────────────────────────┘
```

### Multi-Source Replication

Nhiều source buckets có thể replicate vào 1 destination:

```
bucket-app-a ──┐
               ├──▶ bucket-central (destination)
bucket-app-b ──┘
```

**⚠️ Lưu ý key conflict:** Dùng prefix khác nhau để tránh ghi đè:

```
bucket-central/
├── app-a/
│   └── logs/error.log   ← từ bucket-app-a
└── app-b/
    └── logs/error.log   ← từ bucket-app-b
```

### Existing Objects

Replication chỉ apply cho objects mới sau khi enable. Objects cũ không được copy tự động → phải dùng S3 Batch Operations nếu muốn copy existing objects.

---

## Use Cases

### 1. Disaster Recovery (Cross-Region)
**Scenario:** Backup data sang region khác phòng khi cả region sập.

**Tại sao cần multi-region?** AWS region có thể sập:

| Sự kiện | Năm | Impact |
|---------|-----|--------|
| us-east-1 outage | 2017 | S3 down 4+ giờ, hàng nghìn website sập |
| us-east-1 outage | 2021 | AWS console, Lambda, nhiều services down |
| ap-northeast-1 (Tokyo) | 2019 | Cooling system fail, EC2 down |

```
Nếu chỉ có 1 region:
┌─────────────────────────────────────────┐
│  us-east-1 sập → toàn bộ business stop  │
└─────────────────────────────────────────┘

Nếu có 2 regions:
┌──────────────┐         ┌──────────────┐
│  us-east-1   │ ──CRR─▶ │  eu-west-1   │
│   (DOWN)     │         │  (còn data)  │
└──────────────┘         └──────────────┘
                         → Failover, business tiếp tục
```

**Khi nào cần multi-region:**

| Scenario | Cần multi-region? |
|----------|-------------------|
| Blog cá nhân | ❌ Không |
| SaaS startup giai đoạn đầu | ❌ Không (chấp nhận downtime) |
| E-commerce có revenue | ⚠️ Tùy (tính cost vs risk) |
| Banking/Healthcare | ✅ Bắt buộc (compliance) |
| Enterprise SLA 99.99% | ✅ Bắt buộc |

**Cost vs Risk:** Multi-region tốn ~2x cost. Phải tự hỏi: "Nếu down 4 giờ, mất bao nhiêu tiền/uy tín?"

### 2. Cross-Account Backup
**Scenario:** Copy data sang AWS account khác để bảo vệ khỏi account bị hack.

**Real-world example:** Bank yêu cầu data phải có backup ở account riêng biệt. Nếu production account bị compromised, backup account (với restricted access) vẫn an toàn.

### 3. Log Aggregation
**Scenario:** Gom logs từ nhiều buckets vào 1 bucket central.

**Real-world example:** Mỗi team có bucket riêng, DevOps team cần aggregate tất cả logs vào 1 bucket để chạy analytics với Athena. Tuy nhiên, CloudWatch Logs hoặc ELK thường được dùng thay thế cho use case này.

---

## So sánh với Backup

**Replication ≠ Backup**

```
┌─────────────────────────────────────────────────────────────┐
│  REPLICATION (sync realtime)                                │
│                                                             │
│  Source: file.txt ──DELETE──▶ Dest: file.txt cũng bị xóa!  │
│                                                             │
│  → Lỗi ở source = lỗi ở dest                                │
│  → Hacker xóa source = dest cũng mất                        │
└─────────────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────────────┐
│  BACKUP (point-in-time snapshot)                            │
│                                                             │
│  Source: file.txt ──DELETE──▶ Backup: vẫn còn file cũ!     │
│                                                             │
│  Backup lúc 2AM hôm qua: [file.txt v1] ← vẫn còn            │
└─────────────────────────────────────────────────────────────┘
```

### Chiến lược backup cho dự án nhỏ

| Phase | Approach | Mô tả |
|-------|----------|-------|
| **Phase 1** | Versioning + Lifecycle | Bật versioning, xóa old versions sau 30 ngày |
| **Phase 2** | Backup script | Thêm cron job sync hàng đêm khi có paying customers |
| **Phase 3** | Cross-Region Replication | Setup CRR khi scale lớn hoặc có compliance requirement |

### So sánh approaches

| | S3 Replication | Backup Script | Versioning |
|--|----------------|---------------|------------|
| **Realtime** | ✅ Yes (async) | ❌ Daily/hourly | ✅ Every change |
| **Setup** | Medium | Low | Very low |
| **Bảo vệ khỏi xóa nhầm** | ❌ (nếu sync delete) | ✅ | ✅ |
| **Bảo vệ khỏi region sập** | ✅ (CRR) | ✅ (nếu backup khác region) | ❌ |
| **Phù hợp** | Enterprise/DR | Startup/SMB | Mọi dự án |

---

## Cấu hình

### Step 1: Enable Versioning

```bash
# Source bucket
aws s3api put-bucket-versioning \
  --bucket source-bucket \
  --versioning-configuration Status=Enabled

# Destination bucket
aws s3api put-bucket-versioning \
  --bucket dest-bucket \
  --versioning-configuration Status=Enabled
```

### Step 2: Tạo IAM Role

```json
{
  "Version": "2012-10-17",
  "Statement": [
    {
      "Effect": "Allow",
      "Action": [
        "s3:GetReplicationConfiguration",
        "s3:ListBucket"
      ],
      "Resource": "arn:aws:s3:::source-bucket"
    },
    {
      "Effect": "Allow",
      "Action": [
        "s3:GetObjectVersionForReplication",
        "s3:GetObjectVersionAcl"
      ],
      "Resource": "arn:aws:s3:::source-bucket/*"
    },
    {
      "Effect": "Allow",
      "Action": [
        "s3:ReplicateObject",
        "s3:ReplicateDelete"
      ],
      "Resource": "arn:aws:s3:::dest-bucket/*"
    }
  ]
}
```

### Step 3: Tạo Replication Rule

```json
{
  "Rules": [
    {
      "ID": "ReplicateAll",
      "Status": "Enabled",
      "Priority": 1,
      "Filter": {
        "Prefix": ""
      },
      "Destination": {
        "Bucket": "arn:aws:s3:::dest-bucket"
      },
      "DeleteMarkerReplication": {
        "Status": "Disabled"
      }
    }
  ]
}
```

### Step 4: Apply config

```bash
aws s3api put-bucket-replication \
  --bucket source-bucket \
  --replication-configuration file://replication.json
```

---

## Tài liệu tham khảo

| Chủ đề | Link |
|--------|------|
| **S3 Replication Overview** | https://docs.aws.amazon.com/AmazonS3/latest/userguide/replication.html |
| **Same-Region Replication** | https://docs.aws.amazon.com/AmazonS3/latest/userguide/replication.html#srr-scenario |
| **Cross-Region Replication** | https://docs.aws.amazon.com/AmazonS3/latest/userguide/replication.html#crr-scenario |
| **Replication Configuration** | https://docs.aws.amazon.com/AmazonS3/latest/userguide/replication-add-config.html |

---

## Replication không phải để tăng tốc

**Lưu ý quan trọng:** Replication chỉ backup data, không tự động route traffic đến region gần nhất.

```
Replication:
┌──────────────┐         ┌──────────────┐
│  us-east-1   │ ──copy─▶│  ap-south-1  │
│  (primary)   │         │  (replica)   │
└──────────────┘         └──────────────┘
       │
       └── User VN vẫn phải tự chọn bucket nào để upload/download
           Không có auto-routing!
```

**Muốn auto-route đến region gần nhất? Dùng giải pháp khác:**

| Mục đích | Giải pháp | Cách hoạt động |
|----------|-----------|----------------|
| **Download nhanh** | CloudFront | Cache ở edge gần user |
| **Upload nhanh** | Transfer Acceleration | Route qua edge gần nhất |
| **Multi-region read/write** | S3 Multi-Region Access Points | Tự động route đến bucket gần nhất |

### S3 Multi-Region Access Points

Nếu muốn auto-route traffic, dùng **Multi-Region Access Points** kết hợp với Replication:

```
┌────────────┐                          ┌──────────────┐
│  User VN   │ ───▶ Access Point ───▶   │  ap-south-1  │ ← gần, tự chọn
└────────────┘          │               └──────────────┘
                        │                      ▲
                        │                      │ Replication
                        │                      ▼
┌────────────┐          │               ┌──────────────┐
│  User US   │ ───▶ Access Point ───▶   │  us-east-1   │ ← gần, tự chọn
└────────────┘                          └──────────────┘
```

- 1 endpoint duy nhất cho tất cả users
- AWS tự động route đến bucket gần nhất
- Replication giữ data sync giữa các buckets

---

## Kết luận

- **Dự án nhỏ/startup:** Versioning là đủ, không cần replication
- **Enterprise/Regulated:** CRR bắt buộc cho compliance (bank, healthcare)
- **Replication ≠ Backup:** Replication sync cả delete, backup giữ point-in-time snapshot
- **Replication ≠ Performance:** Không auto-route traffic, dùng CloudFront/Transfer Acceleration/Multi-Region Access Points
- **LocalStack:** Config được nhưng actual replication có thể không hoạt động đầy đủ

---

*Ngày tạo: 2026-01-17*
*Project: realworld-exam*
