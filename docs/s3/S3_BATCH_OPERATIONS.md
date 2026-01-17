# S3 Batch Operations - Documentation

## Mục lục
1. [Tổng quan](#tổng-quan)
2. [Supported Operations](#supported-operations)
3. [Cách hoạt động](#cách-hoạt-động)
4. [Use Cases](#use-cases)
5. [So sánh với Script thủ công](#so-sánh-với-script-thủ-công)
6. [Cấu hình](#cấu-hình)
7. [Pricing](#pricing)
8. [Tài liệu tham khảo](#tài-liệu-tham-khảo)

---

## Tổng quan

S3 Batch Operations cho phép thực hiện bulk operations trên hàng triệu/tỷ objects cùng lúc, được AWS managed.

```
┌──────────────────┐     ┌─────────────────┐     ┌──────────────────┐
│  Manifest file   │────▶│ Batch Operations│────▶│ 10M objects      │
│  (list objects)  │     │   (AWS managed) │     │ processed        │
└──────────────────┘     └─────────────────┘     └──────────────────┘
                                │
                                ├── Parallel processing
                                ├── Auto retry on failure
                                ├── Progress tracking
                                └── Completion report
```

### Đặc điểm chính

| Đặc điểm | Mô tả |
|----------|-------|
| **Scale** | Billions of objects, petabytes of data |
| **Managed** | AWS xử lý parallelism, retry, tracking |
| **Reporting** | Completion report cho mỗi object |
| **Priority** | Có thể set priority cho jobs |
| **Confirmation** | Có thể review trước khi run |

### Ai sử dụng?

| Người | Dùng Batch Operations? |
|-------|------------------------|
| Backend developer | ❌ Hiếm khi |
| DevOps/SRE | ✅ Thường xuyên |
| Data engineer | ✅ Migration, cleanup |
| Platform team | ✅ Compliance, tagging |

**Lưu ý:** Đây là infrastructure/ops task, không phải app logic. Thường chạy từ AWS Console hoặc CLI, không integrate vào app code.

---

## Supported Operations

| Operation | Mô tả | Use Case |
|-----------|-------|----------|
| **Copy** | Copy objects sang bucket khác | Migration, backup |
| **Invoke Lambda** | Chạy custom logic cho mỗi object | Transform, validate, process |
| **Replace tags** | Thay đổi tags hàng loạt | Compliance, cost allocation |
| **Delete** | Xóa hàng triệu objects | Cleanup old data |
| **Restore from Glacier** | Restore archived objects | Access archived data |
| **Replace ACL** | Thay đổi permissions | Security updates |
| **Replace metadata** | Update content-type, cache-control | Fix metadata issues |
| **Replicate** | Copy existing objects (cho replication) | Retroactive replication |

---

## Cách hoạt động

### Step 1: Tạo Manifest

Manifest = danh sách objects cần xử lý. Có 2 cách:

**Option A: S3 Inventory Report**
```
Bucket → Management → Inventory → Tạo report hàng ngày/tuần
→ Output: CSV với danh sách tất cả objects
```

**Option B: CSV tự tạo**
```csv
bucket-name,object-key
my-bucket,folder/file1.txt
my-bucket,folder/file2.txt
my-bucket,folder/file3.txt
```

### Step 2: Tạo Job

```
AWS Console → S3 → Batch Operations → Create Job
→ Chọn manifest
→ Chọn operation (copy, delete, tag, etc.)
→ Configure IAM role
→ Review & Create
```

### Step 3: Confirm & Run

```
Job created (status: Awaiting confirmation)
→ Review số objects, estimated time
→ Confirm to start
→ Job runs (status: Active)
→ Completion report generated
```

### Step 4: Review Report

```
Completion report (CSV):
bucket,key,status,error
my-bucket,file1.txt,succeeded,
my-bucket,file2.txt,succeeded,
my-bucket,file3.txt,failed,AccessDenied
```

---

## Use Cases

### 1. Migration sang bucket/region khác

**Scenario:** Copy toàn bộ 50TB data sang bucket mới.

```
Source: s3://old-bucket (us-east-1)
        │
        │ Batch Copy
        ▼
Dest:   s3://new-bucket (eu-west-1)
```

**Tại sao dùng Batch Operations:**
- Script tự viết → mấy tuần
- Batch Operations → vài giờ, parallel processing

### 2. Retroactive Replication

**Scenario:** Đã enable replication, nhưng cần copy existing objects (replication chỉ copy new objects).

```
Existing 10M objects ──Batch Copy──▶ Destination bucket
                                     (same as replication dest)
```

### 3. Cleanup old files

**Scenario:** Xóa tất cả files older than 1 year (lifecycle chỉ delete theo prefix, không flexible).

```
1. Query với S3 Inventory hoặc Athena → tạo manifest
2. Batch Delete → xóa millions files
```

### 4. Mass tagging cho compliance

**Scenario:** Thêm tag `data-classification=confidential` cho tất cả PII files.

```
1. Identify PII files → tạo manifest
2. Batch Replace Tags → add tag hàng loạt
3. Audit report → prove compliance
```

### 5. Glacier restore hàng loạt

**Scenario:** Cần restore 1 triệu files từ Glacier để audit.

```
1. List archived files → manifest
2. Batch Restore → restore tất cả (chọn retrieval tier)
3. Wait 3-12 hours → files available
```

### 6. Custom processing với Lambda

**Scenario:** Resize tất cả images cũ, hoặc scan files for malware.

```
┌──────────────┐     ┌─────────────────┐     ┌──────────────┐
│   Manifest   │────▶│ Batch Operations│────▶│   Lambda     │
│  (10M files) │     │   Invoke Lambda │     │ (your code)  │
└──────────────┘     └─────────────────┘     └──────────────┘
                                                    │
                                                    ▼
                                             ┌──────────────┐
                                             │ Process each │
                                             │ file (resize,│
                                             │ scan, etc.)  │
                                             └──────────────┘
```

---

## So sánh với Script thủ công

| | Script thủ công | Batch Operations |
|--|-----------------|------------------|
| **Setup** | Viết code, deploy | Console/CLI, vài clicks |
| **Parallelism** | Tự handle (threads, async) | AWS managed |
| **Retry** | Tự implement | Automatic |
| **Progress** | Tự track | Built-in dashboard |
| **Failure handling** | Tự implement | Completion report |
| **Scale** | Limited by your infra | Billions of objects |
| **Cost** | EC2/Lambda cost | $0.25/million objects |
| **Time** | Days/weeks | Hours |

### Khi nào dùng Script thủ công?

- < 10,000 objects (không đáng setup Batch Operations)
- Logic phức tạp cần debug step-by-step
- Cần real-time feedback
- Budget hạn chế

### Khi nào dùng Batch Operations?

- > 100,000 objects
- Standard operations (copy, delete, tag)
- Cần audit trail/report
- Không muốn manage infrastructure

---

## Cấu hình

### Tạo Job bằng AWS CLI

**Step 1: Tạo manifest file**

```csv
my-bucket,folder/file1.txt
my-bucket,folder/file2.txt
```

Upload lên S3:
```bash
aws s3 cp manifest.csv s3://manifest-bucket/manifest.csv
```

**Step 2: Tạo IAM Role**

```json
{
  "Version": "2012-10-17",
  "Statement": [
    {
      "Effect": "Allow",
      "Action": [
        "s3:GetObject",
        "s3:PutObject",
        "s3:DeleteObject"
      ],
      "Resource": [
        "arn:aws:s3:::source-bucket/*",
        "arn:aws:s3:::dest-bucket/*"
      ]
    },
    {
      "Effect": "Allow",
      "Action": [
        "s3:GetObject"
      ],
      "Resource": "arn:aws:s3:::manifest-bucket/manifest.csv"
    },
    {
      "Effect": "Allow",
      "Action": [
        "s3:PutObject"
      ],
      "Resource": "arn:aws:s3:::report-bucket/*"
    }
  ]
}
```

**Step 3: Tạo Job**

```bash
aws s3control create-job \
  --account-id 123456789012 \
  --confirmation-required \
  --operation '{"S3PutObjectCopy":{"TargetResource":"arn:aws:s3:::dest-bucket"}}' \
  --manifest '{"Spec":{"Format":"S3BatchOperations_CSV_20180820","Fields":["Bucket","Key"]},"Location":{"ObjectArn":"arn:aws:s3:::manifest-bucket/manifest.csv","ETag":"abc123"}}' \
  --report '{"Bucket":"arn:aws:s3:::report-bucket","Format":"Report_CSV_20180820","Enabled":true,"Prefix":"reports/","ReportScope":"AllTasks"}' \
  --priority 10 \
  --role-arn arn:aws:iam::123456789012:role/BatchOperationsRole
```

**Step 4: Confirm Job**

```bash
aws s3control update-job-status \
  --account-id 123456789012 \
  --job-id job-id-here \
  --requested-job-status Ready
```

**Step 5: Monitor**

```bash
aws s3control describe-job \
  --account-id 123456789012 \
  --job-id job-id-here
```

---

## Pricing

### Cost breakdown

| Item | Cost |
|------|------|
| **Job creation** | $0.25 per million objects |
| **S3 requests** | Standard S3 request pricing |
| **Data transfer** | Standard S3 data transfer pricing |
| **Lambda (if used)** | Standard Lambda pricing |

### Ví dụ

**Copy 10 million objects sang bucket khác:**
```
Batch Operations: 10M × $0.25/1M = $2.50
S3 PUT requests:  10M × $0.005/1000 = $50
S3 GET requests:  10M × $0.0004/1000 = $4
────────────────────────────────────────
Total: ~$56.50 (không tính data transfer)
```

**So với script chạy trên EC2:**
```
EC2 (m5.large, 3 days): 72h × $0.096/h = $6.91
S3 requests: ~$54
────────────────────────────────────────
Total: ~$61 + effort viết code + risk fail
```

---

## LocalStack Support

| API | LocalStack Support |
|-----|-------------------|
| `CreateJob` | ⚠️ Limited (Pro only) |
| `DescribeJob` | ⚠️ Limited (Pro only) |
| `UpdateJobStatus` | ⚠️ Limited (Pro only) |

**Kết luận:** Khó test local, thường test trên AWS với small dataset.

---

## Tài liệu tham khảo

| Chủ đề | Link |
|--------|------|
| **Batch Operations Overview** | https://docs.aws.amazon.com/AmazonS3/latest/userguide/batch-ops.html |
| **Creating Jobs** | https://docs.aws.amazon.com/AmazonS3/latest/userguide/batch-ops-create-job.html |
| **Operations** | https://docs.aws.amazon.com/AmazonS3/latest/userguide/batch-ops-operations.html |
| **IAM Permissions** | https://docs.aws.amazon.com/AmazonS3/latest/userguide/batch-ops-iam-role-policies.html |
| **Invoke Lambda** | https://docs.aws.amazon.com/AmazonS3/latest/userguide/batch-ops-invoke-lambda.html |

---

## Kết luận

- **Batch Operations** = infrastructure/ops task, không phải app logic
- **Dùng khi:** Migration, cleanup, tagging, restore hàng triệu objects
- **Không dùng cho:** < 10,000 objects, real-time operations
- **Backend developer:** Biết concept là đủ, nhờ DevOps khi cần
- **DevOps/Data engineer:** Tool rất hữu ích cho bulk operations

---

*Ngày tạo: 2026-01-17*
*Status: 📋 Documented (Infrastructure/Ops - Not App Code)*
*Project: realworld-exam*
