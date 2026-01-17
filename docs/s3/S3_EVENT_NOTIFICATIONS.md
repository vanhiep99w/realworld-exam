# S3 Event Notifications

S3 tự động trigger Lambda/SQS/SNS/EventBridge khi có event (upload, delete, etc.)

---

## Overview

```
┌─────────────────────────────────────────────────────────────────┐
│  S3 Event Notifications Flow                                    │
├─────────────────────────────────────────────────────────────────┤
│                                                                 │
│  ┌──────┐  s3:ObjectCreated  ┌─────────────────────────────┐   │
│  │  S3  │ ──────────────────▶│  Lambda / SQS / SNS / EventBridge│
│  └──────┘                    └─────────────────────────────┘   │
│                                        │                        │
│     Events:                            ▼                        │
│     • s3:ObjectCreated:*        ┌──────────────┐               │
│     • s3:ObjectRemoved:*        │ Process file │               │
│     • s3:ObjectRestore:*        │ (resize, scan│               │
│     • s3:Replication:*          │  notify...)  │               │
│                                 └──────────────┘               │
└─────────────────────────────────────────────────────────────────┘
```

---

## Chi phí

**S3 Event Notifications bản thân miễn phí** - không tốn tiền để config.

**Tốn tiền ở destination:**

| Destination | Chi phí | Ví dụ (1M events/tháng) |
|-------------|---------|-------------------------|
| **Lambda** | $0.20/1M requests + compute time | ~$0.70 |
| **SQS** | $0.40/1M requests | ~$0.40 |
| **SNS** | $0.50/1M requests | ~$0.50 |
| **EventBridge** | $1.00/1M events | ~$1.00 |

→ **Rất rẻ**, thường rẻ hơn polling.

---

## Destinations

| Destination | Use Case | Đặc điểm |
|-------------|----------|----------|
| **Lambda** | Process file trực tiếp | Serverless, auto-scale |
| **SQS** | Queue để app poll | Decouple, retry, batch |
| **SNS** | Fan-out nhiều subscribers | Notify nhiều services |
| **EventBridge** | Complex routing | Filter, transform, nhiều targets |

---

## Event Types

| Event | Khi nào trigger |
|-------|-----------------|
| `s3:ObjectCreated:*` | PUT, POST, COPY, Multipart complete |
| `s3:ObjectCreated:Put` | Chỉ PUT |
| `s3:ObjectCreated:Post` | Chỉ POST |
| `s3:ObjectCreated:Copy` | Chỉ COPY |
| `s3:ObjectCreated:CompleteMultipartUpload` | Multipart complete |
| `s3:ObjectRemoved:*` | DELETE, DeleteMarkerCreated |
| `s3:ObjectRemoved:Delete` | Permanent delete |
| `s3:ObjectRemoved:DeleteMarkerCreated` | Delete với versioning |
| `s3:ObjectRestore:Completed` | Restore từ Glacier xong |
| `s3:Replication:*` | Replication events |

---

## Event Message Format

```json
{
  "Records": [
    {
      "eventVersion": "2.1",
      "eventSource": "aws:s3",
      "awsRegion": "ap-southeast-1",
      "eventTime": "2026-01-15T10:00:00.000Z",
      "eventName": "ObjectCreated:Put",
      "userIdentity": {
        "principalId": "EXAMPLE"
      },
      "s3": {
        "s3SchemaVersion": "1.0",
        "bucket": {
          "name": "my-bucket",
          "arn": "arn:aws:s3:::my-bucket"
        },
        "object": {
          "key": "uploads/image.jpg",
          "size": 1024000,
          "eTag": "abc123def456",
          "versionId": "xyz789"
        }
      }
    }
  ]
}
```

---

## Filter by Prefix/Suffix

Chỉ trigger cho files cụ thể:

```json
{
  "LambdaFunctionConfigurations": [
    {
      "Events": ["s3:ObjectCreated:*"],
      "Filter": {
        "Key": {
          "FilterRules": [
            { "Name": "prefix", "Value": "uploads/" },
            { "Name": "suffix", "Value": ".jpg" }
          ]
        }
      },
      "LambdaFunctionArn": "arn:aws:lambda:..."
    }
  ]
}
```

→ Chỉ trigger khi upload file `.jpg` vào folder `uploads/`

---

## Khi nào CẦN S3 Event Notifications?

### ❌ Không cần (App tự handle)

```
┌─────────────────────────────────────────────────────────────────┐
│  App control toàn bộ flow                                       │
├─────────────────────────────────────────────────────────────────┤
│                                                                 │
│  User upload → App nhận file → App upload S3 → App process      │
│                                    │                            │
│                                    └── App đã biết, tự xử lý!   │
│                                                                 │
└─────────────────────────────────────────────────────────────────┘
```

### ✅ Cần S3 Event Notifications

| Scenario | Tại sao cần |
|----------|-------------|
| **Presigned URL upload** | App không biết khi nào user upload xong |
| **Cross-account/external upload** | Service khác upload, app không biết |
| **Decouple services** | Service A upload, Service B process (microservices) |
| **Backup for FE confirm** | FE crash/đóng tab, cần backup mechanism |
| **Replication events** | S3 tự replicate, app không control |
| **Glacier restore** | Khi file được restore xong |

---

## Pattern 1: Image Resize Pipeline (Lambda)

```
┌────────┐  Upload   ┌────────┐  Trigger  ┌────────┐  Save   ┌────────┐
│ Client │ ────────▶ │   S3   │ ────────▶ │ Lambda │ ──────▶ │   S3   │
│        │  image    │ /orig/ │           │ resize │         │/thumbs/│
└────────┘           └────────┘           └────────┘         └────────┘
```

### Lambda Handler

```python
import boto3
from PIL import Image
import io

s3 = boto3.client('s3')

def handler(event, context):
    for record in event['Records']:
        bucket = record['s3']['bucket']['name']
        key = record['s3']['object']['key']
        
        # Download original
        response = s3.get_object(Bucket=bucket, Key=key)
        image = Image.open(response['Body'])
        
        # Resize
        image.thumbnail((200, 200))
        
        # Upload thumbnail
        buffer = io.BytesIO()
        image.save(buffer, 'JPEG')
        buffer.seek(0)
        
        thumb_key = key.replace('orig/', 'thumbs/')
        s3.put_object(Bucket=bucket, Key=thumb_key, Body=buffer)
        
    return {'statusCode': 200}
```

---

## Pattern 2: Virus Scan với SQS + Spring Boot

```
┌────────┐  Upload  ┌────────┐  Event  ┌────────┐  Poll   ┌────────────┐
│ Client │ ───────▶ │   S3   │ ──────▶ │  SQS   │ ──────▶ │Spring Boot │
└────────┘          └────────┘         └────────┘         │@SqsListener│
                                                          └────────────┘
                                                                │
                                                                ▼
                                                          Scan file,
                                                          update DB
```

### Spring Boot SQS Listener

```java
@Component
public class S3EventListener {

    @SqsListener("s3-upload-events")
    public void handleS3Event(String message) {
        S3EventNotification event = S3EventNotification.parseJson(message);
        
        for (S3EventNotification.S3EventNotificationRecord record : event.getRecords()) {
            String bucket = record.getS3().getBucket().getName();
            String key = record.getS3().getObject().getKey();
            
            // Process file
            processUploadedFile(bucket, key);
        }
    }
    
    private void processUploadedFile(String bucket, String key) {
        // 1. Download file from S3
        // 2. Scan for virus
        // 3. Update DB status
        // 4. Move to permanent location or delete if infected
    }
}
```

### Gradle dependency

```groovy
implementation 'io.awspring.cloud:spring-cloud-aws-starter-sqs'
```

---

## Pattern 3: FE Confirm + S3 Event Backup

Cho reliability khi FE có thể crash:

```
┌─────────────────────────────────────────────────────────────────┐
│  Hybrid Pattern                                                 │
├─────────────────────────────────────────────────────────────────┤
│                                                                 │
│  FE upload → S3 OK → FE confirm BE    ← Happy path (90%)        │
│                  │                                              │
│                  └→ S3 Event → SQS → BE  ← Backup nếu FE fail   │
│                                                                 │
│  BE check: "File này đã được confirm chưa?"                     │
│  → confirmed = true  → ignore S3 Event (duplicate)              │
│  → confirmed = false → process từ S3 Event                      │
│                                                                 │
└─────────────────────────────────────────────────────────────────┘
```

### Database Schema

```sql
CREATE TABLE uploads (
    id UUID PRIMARY KEY,
    s3_key VARCHAR(500) UNIQUE,
    status VARCHAR(20),  -- PENDING, CONFIRMED, PROCESSED
    confirmed_at TIMESTAMP,
    processed_at TIMESTAMP,
    created_at TIMESTAMP DEFAULT NOW()
);
```

### Backend Handler

```java
@Transactional
public void handleS3Event(String bucket, String key) {
    Optional<Upload> existing = uploadRepository.findByS3Key(key);
    
    if (existing.isPresent() && existing.get().getStatus() == CONFIRMED) {
        // Already confirmed by FE, skip
        log.info("File {} already confirmed, skipping", key);
        return;
    }
    
    // Process file (FE didn't confirm)
    processFile(bucket, key);
    uploadRepository.updateStatus(key, PROCESSED);
}

public void confirmUpload(String key) {
    uploadRepository.updateStatus(key, CONFIRMED);
    processFile(bucket, key);
}
```

---

## Pattern 4: Fan-out với SNS

Một upload trigger nhiều services:

```
                              ┌─────────────┐
                         ┌───▶│   Lambda    │──▶ Resize image
                         │    │  (resize)   │
┌────────┐  ┌────────┐  ┌┴──┐ └─────────────┘
│   S3   │─▶│  SNS   │─▶│   │
└────────┘  └────────┘  │   │ ┌─────────────┐
                        │   ├▶│     SQS     │──▶ Virus scan
                        │   │ │  (scan)     │
                        └┬──┘ └─────────────┘
                         │    ┌─────────────┐
                         └───▶│   Lambda    │──▶ Update search index
                              │  (index)    │
                              └─────────────┘
```

---

## AWS CLI Configuration

### Tạo SQS Queue

```bash
aws sqs create-queue --queue-name s3-upload-events
```

### Cấu hình S3 Event Notification

```bash
# notification.json
cat > notification.json << 'EOF'
{
  "QueueConfigurations": [
    {
      "QueueArn": "arn:aws:sqs:ap-southeast-1:123456789:s3-upload-events",
      "Events": ["s3:ObjectCreated:*"],
      "Filter": {
        "Key": {
          "FilterRules": [
            { "Name": "prefix", "Value": "uploads/" }
          ]
        }
      }
    }
  ]
}
EOF

aws s3api put-bucket-notification-configuration \
  --bucket my-bucket \
  --notification-configuration file://notification.json
```

### Xem configuration

```bash
aws s3api get-bucket-notification-configuration --bucket my-bucket
```

---

## LocalStack Testing

```bash
# Create queue
aws --endpoint-url=http://localhost:4566 sqs create-queue \
  --queue-name s3-upload-events

# Get queue ARN
QUEUE_ARN=$(aws --endpoint-url=http://localhost:4566 sqs get-queue-attributes \
  --queue-url http://localhost:4566/000000000000/s3-upload-events \
  --attribute-names QueueArn --query 'Attributes.QueueArn' --output text)

# Configure S3 notification
aws --endpoint-url=http://localhost:4566 s3api put-bucket-notification-configuration \
  --bucket my-bucket \
  --notification-configuration "{
    \"QueueConfigurations\": [{
      \"QueueArn\": \"$QUEUE_ARN\",
      \"Events\": [\"s3:ObjectCreated:*\"]
    }]
  }"

# Test upload
aws --endpoint-url=http://localhost:4566 s3 cp test.jpg s3://my-bucket/uploads/

# Check SQS for message
aws --endpoint-url=http://localhost:4566 sqs receive-message \
  --queue-url http://localhost:4566/000000000000/s3-upload-events
```

---

## SQS Queue Policy

SQS cần policy cho phép S3 gửi message:

```json
{
  "Version": "2012-10-17",
  "Statement": [
    {
      "Effect": "Allow",
      "Principal": {
        "Service": "s3.amazonaws.com"
      },
      "Action": "sqs:SendMessage",
      "Resource": "arn:aws:sqs:ap-southeast-1:123456789:s3-upload-events",
      "Condition": {
        "ArnLike": {
          "aws:SourceArn": "arn:aws:s3:::my-bucket"
        }
      }
    }
  ]
}
```

---

## Common Issues

| Issue | Nguyên nhân | Fix |
|-------|-------------|-----|
| Event không được gửi | Thiếu permissions | Add SQS/SNS policy cho S3 |
| Duplicate events | At-least-once delivery | Handle idempotent trong app |
| Event delay | SQS visibility timeout | Tune timeout settings |
| Missing events | Filter không match | Check prefix/suffix config |

---

## Best Practices

| Practice | Lý do |
|----------|-------|
| **Idempotent handlers** | S3 Events có thể duplicate |
| **DLQ (Dead Letter Queue)** | Catch failed messages |
| **Filter by prefix/suffix** | Tránh trigger không cần thiết |
| **Use SQS over Lambda** | Better error handling, retry |
| **Monitor với CloudWatch** | Track event delivery |

---

## So sánh với Polling

```
┌─────────────────────────────────────────────────────────────────┐
│  POLLING                                                        │
├─────────────────────────────────────────────────────────────────┤
│  App poll S3 mỗi 5 giây: "Có file mới không?"                   │
│  → 518,400 requests/tháng                                       │
│  → Tốn tiền S3 LIST + EC2 running 24/7                          │
│  → Delay 0-5 giây                                               │
└─────────────────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────────────────┐
│  EVENT NOTIFICATIONS                                            │
├─────────────────────────────────────────────────────────────────┤
│  S3 push event khi có file mới                                  │
│  → Chỉ tốn tiền khi có event thật                               │
│  → Near real-time (milliseconds)                                │
│  → Rẻ hơn và nhanh hơn                                          │
└─────────────────────────────────────────────────────────────────┘
```

---

## Official Documentation

- [S3 Event Notifications](https://docs.aws.amazon.com/AmazonS3/latest/userguide/EventNotifications.html)
- [Configuring Notifications](https://docs.aws.amazon.com/AmazonS3/latest/userguide/NotificationHowTo.html)
- [Event Message Structure](https://docs.aws.amazon.com/AmazonS3/latest/userguide/notification-content-structure.html)
- [EventBridge Integration](https://docs.aws.amazon.com/AmazonS3/latest/userguide/EventBridge.html)

---

*Document created: 2026-01-15*
*Project: realworld-exam*
