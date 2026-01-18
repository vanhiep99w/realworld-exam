# Export to S3 - Documentation

## Mục lục
1. [Tổng quan](#tổng-quan)
2. [So sánh với Subscription Filters](#so-sánh-với-subscription-filters)
3. [Cách hoạt động](#cách-hoạt-động)
4. [Cấu hình Export Task](#cấu-hình-export-task)
5. [Use Cases](#use-cases)
6. [Limitations](#limitations)
7. [Tài liệu tham khảo](#tài-liệu-tham-khảo)

---

## Tổng quan

**Export to S3** cho phép export log data từ CloudWatch Logs sang S3 bucket để:
- Long-term archival (lưu trữ lâu dài)
- Custom processing/analysis
- Compliance requirements
- Load vào các hệ thống khác (Athena, Redshift, etc.)

```
┌─────────────────────────────────────────────────────────────────────────┐
│                                                                          │
│   CloudWatch Logs                          Amazon S3                     │
│   ┌─────────────────┐                     ┌─────────────────┐           │
│   │  Log Group:     │                     │  Bucket:        │           │
│   │  /app/prod      │ ───Export Task───► │  my-logs-backup │           │
│   │                 │                     │                 │           │
│   │  - Stream 1     │                     │  /2026/01/18/   │           │
│   │  - Stream 2     │                     │    - 000000.gz  │           │
│   │  - Stream 3     │                     │    - 000001.gz  │           │
│   └─────────────────┘                     └─────────────────┘           │
│                                                                          │
└─────────────────────────────────────────────────────────────────────────┘
```

### Đặc điểm chính

| Đặc điểm | Giá trị |
|----------|---------|
| **Loại export** | One-time batch job (không phải real-time) |
| **Độ trễ** | Log data cần **12 giờ** mới available để export |
| **Timeout** | Export task timeout sau **24 giờ** |
| **Concurrent tasks** | Mỗi account chỉ **1 active export task** tại một thời điểm |
| **Encryption** | Hỗ trợ SSE-S3, SSE-KMS (không hỗ trợ DSSE-KMS) |
| **Cross-account** | Có thể export sang bucket ở account khác |

---

## So sánh với Subscription Filters

```
┌─────────────────────────────────────────────────────────────────────────┐
│                                                                          │
│   Export to S3                         Subscription Filters              │
│   ┌─────────────────┐                 ┌─────────────────┐               │
│   │                 │                 │                 │               │
│   │  One-time       │                 │  Real-time      │               │
│   │  Batch job      │                 │  Streaming      │               │
│   │                 │                 │                 │               │
│   │  Manual trigger │                 │  Automatic      │               │
│   │                 │                 │                 │               │
│   └─────────────────┘                 └─────────────────┘               │
│                                                                          │
│   Use case:                           Use case:                          │
│   - Backup logs cũ                    - Continuous archiving             │
│   - Ad-hoc export                     - Real-time processing             │
│   - Compliance audit                  - Stream to Lambda/Kinesis         │
│                                                                          │
└─────────────────────────────────────────────────────────────────────────┘
```

| Tính năng | Export to S3 | Subscription Filters |
|-----------|--------------|---------------------|
| **Timing** | One-time, manual | Continuous, automatic |
| **Delay** | 12h trước khi export được | Near real-time |
| **Use case** | Backup, compliance | Continuous archiving |
| **Destination** | S3 only | Lambda, Kinesis, S3 (via Firehose) |
| **Historical data** | ✅ Export logs cũ | ❌ Chỉ logs mới |

> ⚠️ **AWS khuyến nghị:** Dùng **Subscription Filters** cho continuous archiving, chỉ dùng Export to S3 cho ad-hoc backup hoặc historical data.

---

## Cách hoạt động

### Export Flow

```
┌─────────────────────────────────────────────────────────────────────────┐
│  Step 1: Tạo S3 Bucket với đúng permissions                             │
│                                                                          │
│   S3 Bucket Policy:                                                      │
│   - Allow logs.{region}.amazonaws.com to GetBucketAcl                   │
│   - Allow logs.{region}.amazonaws.com to PutObject                      │
│                                                                          │
└───────────────────────────────────┬─────────────────────────────────────┘
                                    │
                                    ▼
┌─────────────────────────────────────────────────────────────────────────┐
│  Step 2: Tạo Export Task                                                │
│                                                                          │
│   Specify:                                                               │
│   - Log Group name                                                       │
│   - Time range (from, to)                                               │
│   - Destination bucket                                                   │
│   - Destination prefix (optional)                                        │
│                                                                          │
└───────────────────────────────────┬─────────────────────────────────────┘
                                    │
                                    ▼
┌─────────────────────────────────────────────────────────────────────────┐
│  Step 3: CloudWatch Logs exports data                                   │
│                                                                          │
│   - Async process (có thể mất vài giây đến vài giờ)                     │
│   - Data được nén thành .gz files                                       │
│   - Files được upload vào S3 bucket                                     │
│                                                                          │
└───────────────────────────────────┬─────────────────────────────────────┘
                                    │
                                    ▼
┌─────────────────────────────────────────────────────────────────────────┐
│  Step 4: Check status và download                                       │
│                                                                          │
│   Status: PENDING → RUNNING → COMPLETED (hoặc FAILED)                   │
│                                                                          │
└─────────────────────────────────────────────────────────────────────────┘
```

### S3 Output Structure

```
s3://my-logs-bucket/
└── prefix/
    └── aws-logs-write-test    (test file - có thể xóa)
    └── export-task-id/
        ├── log-stream-1/
        │   ├── 000000.gz
        │   └── 000001.gz
        └── log-stream-2/
            └── 000000.gz
```

Mỗi `.gz` file chứa log events với format:
```
1705574400000 {"timestamp":"2026-01-18T10:00:00Z","message":"..."}
1705574401000 {"timestamp":"2026-01-18T10:00:01Z","message":"..."}
```

> ⚠️ **Lưu ý:** Logs trong file **không được sort theo thời gian**. Cần sort thủ công nếu cần.

---

## Cấu hình Export Task

### Sử dụng AWS CLI

```bash
# Tạo export task
aws logs create-export-task \
    --task-name "my-export-task" \
    --log-group-name "/app/production" \
    --from 1705536000000 \
    --to 1705622400000 \
    --destination "my-logs-bucket" \
    --destination-prefix "exports/2026-01-18"

# Response
{
    "taskId": "abc123-task-id"
}
```

### Kiểm tra status

```bash
aws logs describe-export-tasks \
    --task-id "abc123-task-id"

# Response
{
    "exportTasks": [
        {
            "taskId": "abc123-task-id",
            "taskName": "my-export-task",
            "logGroupName": "/app/production",
            "status": {
                "code": "COMPLETED",    # PENDING | RUNNING | COMPLETED | FAILED
                "message": "Completed successfully"
            }
        }
    ]
}
```

### Cancel export task

```bash
aws logs cancel-export-task \
    --task-id "abc123-task-id"
```

### S3 Bucket Policy (bắt buộc)

```json
{
    "Version": "2012-10-17",
    "Statement": [
        {
            "Sid": "AllowGetBucketAcl",
            "Effect": "Allow",
            "Principal": {
                "Service": "logs.ap-southeast-1.amazonaws.com"
            },
            "Action": "s3:GetBucketAcl",
            "Resource": "arn:aws:s3:::my-logs-bucket",
            "Condition": {
                "StringEquals": {
                    "aws:SourceAccount": ["123456789012"]
                }
            }
        },
        {
            "Sid": "AllowPutObject",
            "Effect": "Allow",
            "Principal": {
                "Service": "logs.ap-southeast-1.amazonaws.com"
            },
            "Action": "s3:PutObject",
            "Resource": "arn:aws:s3:::my-logs-bucket/*",
            "Condition": {
                "StringEquals": {
                    "s3:x-amz-acl": "bucket-owner-full-control",
                    "aws:SourceAccount": ["123456789012"]
                }
            }
        }
    ]
}
```

### Timestamp calculation

```
from/to = milliseconds since Jan 1, 1970 (Unix epoch)

Ví dụ: Export logs từ 2026-01-18 00:00:00 đến 2026-01-18 23:59:59

from = 1705536000000  (2026-01-18 00:00:00 UTC)
to   = 1705622399000  (2026-01-18 23:59:59 UTC)

# Tính trong bash:
date -d "2026-01-18 00:00:00 UTC" +%s%3N
```

---

## Use Cases

### 1. Compliance Archival

**Scenario:** Cần lưu logs 7 năm theo quy định compliance.

**Solution:**
```
┌─────────────────────────────────────────────────────────────────────────┐
│  CloudWatch Logs        Export to S3           S3 Lifecycle            │
│  (Retention: 30 days)    (Monthly)             (Glacier after 90 days) │
│                                                                          │
│  Logs 30 ngày  ──►  Export monthly  ──►  S3 Standard  ──►  Glacier     │
│                      backup                (90 days)       (7 years)    │
└─────────────────────────────────────────────────────────────────────────┘
```

### 2. Ad-hoc Investigation

**Scenario:** Cần export logs cũ để analyze incident xảy ra tuần trước.

**Real-world example:** Security team phát hiện suspicious activity, cần export 1 tuần logs để forensic analysis với external tools.

### 3. Cost Optimization

**Scenario:** CloudWatch Logs đắt hơn S3 cho long-term storage.

| Storage | Cost (approx) |
|---------|---------------|
| CloudWatch Logs | $0.03/GB/month |
| S3 Standard | $0.023/GB/month |
| S3 Glacier | $0.004/GB/month |

**Strategy:** Giữ logs trong CloudWatch 30 ngày, export sang S3 Glacier cho long-term.

### 4. Analytics với Athena

**Scenario:** Query logs với SQL using Amazon Athena.

```
Export to S3 ──► Create Athena Table ──► Run SQL Queries
```

---

## Limitations

| Limitation | Giá trị |
|------------|---------|
| **Delay** | 12 giờ trước khi logs available để export |
| **Timeout** | 24 giờ per export task |
| **Concurrent tasks** | 1 per account |
| **Cross-region** | ❌ Bucket phải cùng region với log group |
| **Encryption** | ❌ Không hỗ trợ DSSE-KMS |
| **Sorting** | ❌ Logs không được sort theo thời gian |
| **Real-time** | ❌ Không phải real-time (dùng Subscription Filters) |

### Khi nào KHÔNG nên dùng Export to S3

| Scenario | Nên dùng |
|----------|----------|
| Continuous archiving | **Subscription Filters** → Firehose → S3 |
| Real-time processing | **Subscription Filters** → Lambda |
| Near real-time analysis | **Log Insights Query** |
| Export logs mới (< 12h) | Chờ hoặc dùng **Subscription Filters** |

---

## Best Practices

### 1. Automate Export

Có 2 cách để tự động export hàng ngày:

```
┌─────────────────────────────────────────────────────────────────────────┐
│  Option 1: Lambda (Serverless)                                          │
│                                                                          │
│   EventBridge  ──►  Lambda  ──►  create-export-task API                 │
│   (Daily 2 AM)                                                           │
│                                                                          │
├─────────────────────────────────────────────────────────────────────────┤
│  Option 2: Server của mình (Spring Boot)                                │
│                                                                          │
│   @Scheduled   ──►  AWS SDK  ──►  create-export-task API                │
│   (cron)                                                                 │
│                                                                          │
└─────────────────────────────────────────────────────────────────────────┘
```

| | Lambda | Server của mình |
|---|--------|-----------------|
| **Cost** | Pay per invocation | Đã có server sẵn |
| **Setup** | Cần tạo Lambda + EventBridge | Chỉ cần `@Scheduled` |
| **Maintenance** | AWS quản lý | Tự quản lý |
| **Phù hợp khi** | Không có server 24/7 | Đã có Spring Boot chạy sẵn |

#### Option 1: Lambda + EventBridge

```python
# Lambda function
import boto3
from datetime import datetime, timedelta

def lambda_handler(event, context):
    client = boto3.client('logs')
    
    yesterday = datetime.utcnow() - timedelta(days=1)
    from_time = int(yesterday.replace(hour=0, minute=0, second=0).timestamp() * 1000)
    to_time = int(yesterday.replace(hour=23, minute=59, second=59).timestamp() * 1000)
    
    response = client.create_export_task(
        taskName=f'daily-export-{yesterday.strftime("%Y-%m-%d")}',
        logGroupName='/app/production',
        fromTime=from_time,
        toTime=to_time,
        destination='my-logs-bucket',
        destinationPrefix=f'logs/{yesterday.strftime("%Y/%m/%d")}'
    )
    
    return response['taskId']
```

#### Option 2: Spring Boot Scheduled Task

```java
@Service
@RequiredArgsConstructor
public class LogExportService {
    
    private final CloudWatchLogsClient logsClient;
    
    // Chạy mỗi ngày lúc 2 AM
    @Scheduled(cron = "0 0 2 * * *")
    public void exportDailyLogs() {
        Instant yesterday = Instant.now().minus(1, ChronoUnit.DAYS);
        long fromTime = yesterday.truncatedTo(ChronoUnit.DAYS).toEpochMilli();
        long toTime = fromTime + 86400000 - 1; // 24h - 1ms
        
        CreateExportTaskResponse response = logsClient.createExportTask(req -> req
            .taskName("daily-export-" + LocalDate.now().minusDays(1))
            .logGroupName("/app/production")
            .from(fromTime)
            .to(toTime)
            .destination("my-logs-bucket")
            .destinationPrefix("logs/" + LocalDate.now().minusDays(1))
        );
        
        log.info("Export task created: {}", response.taskId());
    }
}
```

### 2. Organize với Destination Prefix

```
s3://logs-bucket/
├── prod/
│   ├── 2026/01/18/
│   └── 2026/01/19/
└── staging/
    ├── 2026/01/18/
    └── 2026/01/19/
```

### 3. S3 Lifecycle Rules

```json
{
    "Rules": [
        {
            "ID": "Move to Glacier after 90 days",
            "Status": "Enabled",
            "Transitions": [
                {
                    "Days": 90,
                    "StorageClass": "GLACIER"
                }
            ],
            "Expiration": {
                "Days": 2555  // 7 years
            }
        }
    ]
}
```

---

## Tài liệu tham khảo

| Chủ đề | Link |
|--------|------|
| **Export Overview** | https://docs.aws.amazon.com/AmazonCloudWatch/latest/logs/S3Export.html |
| **Export via Console** | https://docs.aws.amazon.com/AmazonCloudWatch/latest/logs/S3ExportTasksConsole.html |
| **Export via CLI** | https://docs.aws.amazon.com/AmazonCloudWatch/latest/logs/S3ExportTasks.html |
| **CreateExportTask API** | https://docs.aws.amazon.com/AmazonCloudWatchLogs/latest/APIReference/API_CreateExportTask.html |
| **Subscription Filters (alternative)** | https://docs.aws.amazon.com/AmazonCloudWatch/latest/logs/Subscriptions.html |

---

*Ngày tạo: 2026-01-18*
*Project: realworld-exam*
