# Subscription Filters

## Mục lục
1. [Tổng quan](#tổng-quan)
2. [Destinations](#destinations)
3. [Filter Pattern](#filter-pattern)
4. [So sánh Subscription Filter vs Metric Filter](#so-sánh)
5. [Limits](#limits)
6. [Use Cases](#use-cases)
7. [Tạo Subscription Filter (CLI)](#tạo-subscription-filter)
8. [Tài liệu tham khảo](#tài-liệu-tham-khảo)

---

## Tổng quan

Subscription Filter cho phép **stream logs real-time** từ CloudWatch Logs đến các destinations khác để xử lý hoặc lưu trữ.

```
┌─────────────────┐                      ┌─────────────────────────────────┐
│  CloudWatch     │                      │  Destinations                   │
│  Log Group      │                      │                                 │
│                 │   Subscription       │  ┌─────────────────────────┐    │
│  ┌───────────┐  │   Filter             │  │ Lambda (xử lý real-time)│    │
│  │Log Stream │──┼──────────────────────┼─►│ Kinesis Data Streams    │    │
│  │Log Stream │  │   (filter pattern)   │  │ Kinesis Data Firehose   │    │
│  │Log Stream │  │                      │  │ OpenSearch Service      │    │
│  └───────────┘  │                      │  └─────────────────────────┘    │
└─────────────────┘                      └─────────────────────────────────┘
```

### Đặc điểm chính

| Đặc điểm | Mô tả |
|----------|-------|
| **Real-time** | Logs được stream ngay khi xuất hiện (near real-time) |
| **Filter Pattern** | Chỉ stream logs khớp pattern (hoặc tất cả nếu pattern trống) |
| **Format** | Data được **base64 encoded + gzip compressed** |
| **Limit** | **2 subscription filters** / log group (có thể tăng lên 5) |

---

## Destinations

### 4 loại Destinations

```
                         ┌─────────────────────────────────────────┐
                         │          Subscription Filter            │
                         └─────────────────┬───────────────────────┘
                                           │
         ┌─────────────────┬───────────────┼───────────────┬─────────────────┐
         ▼                 ▼               ▼               ▼                 │
┌─────────────────┐ ┌─────────────┐ ┌─────────────┐ ┌─────────────────────┐  │
│     Lambda      │ │   Kinesis   │ │   Kinesis   │ │    OpenSearch       │  │
│   (Function)    │ │Data Streams │ │Data Firehose│ │     Service         │  │
└────────┬────────┘ └──────┬──────┘ └──────┬──────┘ └──────────┬──────────┘  │
         │                 │               │                   │             │
         ▼                 ▼               ▼                   ▼             │
┌─────────────────┐ ┌─────────────┐ ┌─────────────┐ ┌─────────────────────┐  │
│ Custom Logic    │ │ Real-time   │ │ S3, Redshift│ │ Search & Analytics  │  │
│ Alert, Transform│ │ Analytics   │ │ Splunk, etc │ │ Kibana Dashboard    │  │
└─────────────────┘ └─────────────┘ └─────────────┘ └─────────────────────┘  │
                                                                             │
```

### So sánh Destinations

| Destination | Use Case | Latency | Chi phí | Complexity |
|-------------|----------|---------|---------|------------|
| **Lambda** | Custom processing, alerts, transform | ~ms | Per invocation | Thấp |
| **Kinesis Data Streams** | Real-time analytics, multiple consumers | ~ms | Per shard-hour | Trung bình |
| **Kinesis Data Firehose** | Đẩy vào S3/Redshift/Splunk | 60s buffer | Per GB | Thấp |
| **OpenSearch Service** | Log search, visualization | ~seconds | Per instance | Trung bình |

### Khi nào dùng destination nào?

```
Câu hỏi:                                    Destination:
┌─────────────────────────────────────┐
│ Cần xử lý custom logic?             │
│ (transform, filter, alert)          │──────────────────► Lambda
└─────────────────────────────────────┘

┌─────────────────────────────────────┐
│ Cần nhiều consumers cùng đọc?       │
│ (analytics + alerting + archiving)  │──────────────────► Kinesis Data Streams
└─────────────────────────────────────┘

┌─────────────────────────────────────┐
│ Cần archive to S3/Redshift/Splunk?  │
│ (không cần xử lý phức tạp)          │──────────────────► Kinesis Data Firehose
└─────────────────────────────────────┘

┌─────────────────────────────────────┐
│ Cần search/visualize logs?          │
│ (Kibana dashboards)                 │──────────────────► OpenSearch Service
└─────────────────────────────────────┘
```

---

## Filter Pattern

Filter pattern giống với [Metric Filter pattern](./METRIC_FILTERS.md) - dùng để chỉ stream những logs khớp pattern.

### Ví dụ patterns

| Pattern | Mô tả |
|---------|-------|
| `""` (empty) | Stream **tất cả** logs |
| `ERROR` | Logs chứa từ "ERROR" |
| `[ip, user, ...]` | Space-delimited format |
| `{ $.level = "ERROR" }` | JSON logs có level = ERROR |
| `{ $.statusCode >= 400 }` | HTTP errors |

### So sánh Filter Pattern

```
┌─────────────────────────────────────────────────────────────────────┐
│                         Log Group                                    │
│  ┌──────────────────────────────────────────────────────────────┐   │
│  │ INFO  - Request started                                       │   │
│  │ DEBUG - Connecting to database                                │   │
│  │ ERROR - Connection failed                         ◄─── Match  │   │
│  │ INFO  - Retrying...                                           │   │
│  │ ERROR - Retry failed                              ◄─── Match  │   │
│  │ INFO  - Request completed                                     │   │
│  └──────────────────────────────────────────────────────────────┘   │
└─────────────────────────────────────────────────────────────────────┘
                              │
                              │ filter-pattern = "ERROR"
                              ▼
┌─────────────────────────────────────────────────────────────────────┐
│                    Destination (Lambda/Kinesis)                      │
│  ┌──────────────────────────────────────────────────────────────┐   │
│  │ ERROR - Connection failed                                     │   │
│  │ ERROR - Retry failed                                          │   │
│  └──────────────────────────────────────────────────────────────┘   │
│                                                                      │
│  Chỉ nhận 2 log events khớp pattern                                 │
└─────────────────────────────────────────────────────────────────────┘
```

---

## So sánh

### Subscription Filter vs Metric Filter

```
                         CloudWatch Logs
                              │
        ┌─────────────────────┴─────────────────────┐
        │                                           │
        ▼                                           ▼
┌───────────────────┐                     ┌───────────────────┐
│   Metric Filter   │                     │Subscription Filter│
│                   │                     │                   │
│ Logs → Metric     │                     │ Logs → Stream     │
│ (số liệu thống kê)│                     │ (raw log data)    │
└─────────┬─────────┘                     └─────────┬─────────┘
          │                                         │
          ▼                                         ▼
┌───────────────────┐                     ┌───────────────────┐
│ CloudWatch Metrics│                     │ Lambda/Kinesis/   │
│ → Alarms          │                     │ Firehose/OpenSearch
│ → Dashboards      │                     │                   │
└───────────────────┘                     └───────────────────┘
```

| Tiêu chí | Metric Filter | Subscription Filter |
|----------|---------------|---------------------|
| **Output** | Metric (số) | Raw log data (text) |
| **Mục đích** | Thống kê, đếm, alarm | Stream, archive, analyze |
| **Ví dụ** | Đếm số lỗi/phút | Gửi log errors vào Slack |
| **Latency** | ~1 phút (aggregated) | Real-time |
| **Limit** | 100/log group | 2/log group |
| **Chi phí** | Miễn phí | Theo destination |

### Khi nào dùng cái nào?

| Scenario | Dùng |
|----------|------|
| Muốn **đếm** số errors/phút → tạo alarm | **Metric Filter** |
| Muốn **xem chi tiết** từng error → gửi vào Slack | **Subscription Filter** → Lambda |
| Muốn **archive** logs vào S3 | **Subscription Filter** → Firehose |
| Muốn **search** logs với Kibana | **Subscription Filter** → OpenSearch |

---

## Limits

| Resource | Default Limit | Có thể tăng? |
|----------|---------------|--------------|
| Subscription filters / log group | 2 | ✅ Lên tối đa 5 |
| Filter pattern length | 1024 characters | ❌ |

### Throttling

Khi destination không xử lý kịp:
- **Kinesis**: Retries lên đến 24 giờ, sau đó drop
- **Lambda**: Retries với exponential backoff
- **Firehose**: Buffer và retry

---

## Use Cases

### 1. Real-time Error Alerting

**Scenario:** Gửi Slack notification ngay khi có ERROR

```
Log Group → Subscription Filter (ERROR) → Lambda → Slack Webhook
```

**Real-world:** Production monitoring - nhận alert trong vài giây thay vì chờ alarm evaluation (1-5 phút).

### 2. Centralized Log Aggregation

**Scenario:** Tập trung logs từ nhiều accounts vào S3

```
┌──────────────────┐     ┌──────────────────┐     ┌──────────────────┐
│ Account A        │     │ Account B        │     │ Account C        │
│ Log Group        │     │ Log Group        │     │ Log Group        │
└────────┬─────────┘     └────────┬─────────┘     └────────┬─────────┘
         │                        │                        │
         └────────────────────────┼────────────────────────┘
                                  │
                                  ▼
                         ┌──────────────────┐
                         │ Kinesis Firehose │
                         │ (Central Account)│
                         └────────┬─────────┘
                                  │
                                  ▼
                         ┌──────────────────┐
                         │       S3         │
                         │  (Central Bucket)│
                         └──────────────────┘
```

**Real-world:** Công ty có 50+ AWS accounts, cần compliance logging tập trung.

### 3. Log Analytics với OpenSearch

**Scenario:** Search và visualize application logs

```
Log Group → Subscription Filter → OpenSearch → Kibana Dashboard
```

**Real-world:** Debug production issues bằng cách search logs theo correlation ID, visualize error trends.

### 4. Stream Processing với Kinesis

**Scenario:** Real-time analytics trên access logs

```
Log Group → Subscription Filter → Kinesis Data Streams → Kinesis Analytics → Dashboard
```

**Real-world:** E-commerce site tracking real-time metrics: requests/second, top URLs, geographic distribution.

---

## Tạo Subscription Filter

### CLI: Stream to Lambda

```bash
# Tạo subscription filter → Lambda
aws logs put-subscription-filter \
  --log-group-name "/app/my-service" \
  --filter-name "ErrorsToLambda" \
  --filter-pattern "ERROR" \
  --destination-arn "arn:aws:lambda:us-east-1:123456789:function:ProcessErrors"
```

### CLI: Stream to Kinesis Firehose

```bash
# Tạo subscription filter → Firehose (cần IAM role)
aws logs put-subscription-filter \
  --log-group-name "/app/my-service" \
  --filter-name "AllLogsToS3" \
  --filter-pattern "" \
  --destination-arn "arn:aws:firehose:us-east-1:123456789:deliverystream/logs-to-s3" \
  --role-arn "arn:aws:iam::123456789:role/CWLtoFirehoseRole"
```

### CLI: Stream to Kinesis Data Streams

```bash
# Tạo subscription filter → Kinesis (cần IAM role)
aws logs put-subscription-filter \
  --log-group-name "/app/my-service" \
  --filter-name "AllLogsToKinesis" \
  --filter-pattern "" \
  --destination-arn "arn:aws:kinesis:us-east-1:123456789:stream/my-stream" \
  --role-arn "arn:aws:iam::123456789:role/CWLtoKinesisRole"
```

### Xem Subscription Filters

```bash
aws logs describe-subscription-filters \
  --log-group-name "/app/my-service"
```

### Xóa Subscription Filter

```bash
aws logs delete-subscription-filter \
  --log-group-name "/app/my-service" \
  --filter-name "ErrorsToLambda"
```

---

## Data Format

Logs được gửi đến destination ở format **base64 + gzip**. Lambda cần decode:

```javascript
// Lambda handler để decode logs từ subscription filter
exports.handler = async (event) => {
    // Decode base64
    const payload = Buffer.from(event.awslogs.data, 'base64');
    
    // Decompress gzip
    const zlib = require('zlib');
    const result = zlib.gunzipSync(payload);
    
    // Parse JSON
    const logData = JSON.parse(result.toString('utf8'));
    
    console.log('Log Group:', logData.logGroup);
    console.log('Log Stream:', logData.logStream);
    console.log('Log Events:', logData.logEvents);
    
    // logEvents là array: [{id, timestamp, message}, ...]
    for (const event of logData.logEvents) {
        console.log('Message:', event.message);
    }
};
```

**Structure của decoded data:**

```json
{
    "messageType": "DATA_MESSAGE",
    "owner": "123456789012",
    "logGroup": "/app/my-service",
    "logStream": "i-1234567890abcdef0",
    "subscriptionFilters": ["ErrorsToLambda"],
    "logEvents": [
        {
            "id": "36051234567890123456789012345678901234567890",
            "timestamp": 1642345678901,
            "message": "ERROR - Connection failed"
        }
    ]
}
```

---

## Pricing

Subscription Filter bản thân **miễn phí**, nhưng trả tiền cho:

| Component | Chi phí |
|-----------|---------|
| **Lambda invocations** | $0.20/1M requests + duration |
| **Kinesis Data Streams** | $0.015/shard-hour + $0.014/1M PUT |
| **Kinesis Firehose** | $0.029/GB ingested |
| **OpenSearch** | Instance hours + storage |
| **Data Transfer** | Cross-region/cross-account fees |

---

## Tài liệu tham khảo

| Chủ đề | Link |
|--------|------|
| **Subscription Filters** | https://docs.aws.amazon.com/AmazonCloudWatch/latest/logs/SubscriptionFilters.html |
| **Cross-Account Subscriptions** | https://docs.aws.amazon.com/AmazonCloudWatch/latest/logs/CrossAccountSubscriptions.html |
| **Filter Pattern Syntax** | https://docs.aws.amazon.com/AmazonCloudWatch/latest/logs/FilterAndPatternSyntax.html |

---

*Ngày tạo: 2026-01-18*
*Project: realworld-exam*
