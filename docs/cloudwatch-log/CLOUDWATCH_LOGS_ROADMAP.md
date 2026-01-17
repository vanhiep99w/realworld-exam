# CloudWatch Logs Learning Roadmap

Danh sách các chủ đề CloudWatch Logs có thể học, sắp xếp theo độ khó và tính thực tế.

---

## ✅ Đã Implement

| # | Topic | Description | Docs |
|---|-------|-------------|------|
| - | - | - | - |

---

## 📋 Đã Document (Chưa Implement)

| # | Topic | Description | Docs |
|---|-------|-------------|------|
| - | - | - | - |

--- 

## 📚 Chưa Implement - Theo Thứ Tự Học

### Level 1: Foundation (⭐)

| # | Topic | Description | Use Case |
|---|-------|-------------|----------|
| 1 | **Log Groups & Streams** | Cấu trúc cơ bản của CloudWatch Logs | Organize logs theo application/environment |
| 2 | **Push Logs từ Application** | Gửi logs từ Spring Boot → CloudWatch | Centralized logging |
| 3 | **Log Insights Query** | Query logs với SQL-like syntax | Debug, search errors |
| 4 | **Retention Policies** | Tự động xóa logs sau X ngày | Cost optimization |

### Level 2: Intermediate (⭐⭐)

| # | Topic | Description | Use Case |
|---|-------|-------------|----------|
| 5 | **Metric Filters** | Tạo metrics từ log patterns | Count errors, track specific events |
| 6 | **CloudWatch Alarms** | Alert khi metric vượt threshold | PagerDuty, Slack notifications |
| 7 | **Subscription Filters** | Stream logs to Lambda/Kinesis/S3 | Real-time processing, long-term storage |
| 8 | **Log Format & Structured Logging** | JSON logs, MDC context | Better searchability |

### Level 3: Advanced (⭐⭐⭐)

| # | Topic | Description | Use Case |
|---|-------|-------------|----------|
| 9 | **Cross-Account Logging** | Aggregate logs từ multiple accounts | Enterprise, multi-account setup |
| 10 | **CloudWatch Logs Agent** | Push logs từ EC2/on-premise | Legacy apps, system logs |
| 11 | **Contributor Insights** | Top-N analysis trên logs | Find top users, top errors |
| 12 | **Export to S3** | Archive logs to S3 | Long-term retention, compliance |

---

## 🎯 Recommended Learning Path

```
┌─────────────────────────────────────────────────────────────┐
│  Week 1: Foundation                                         │
│  → Log Groups & Streams                                     │
│  → Push Logs từ Spring Boot                                 │
│  → Log Insights Query                                       │
└─────────────────────────────────────────────────────────────┘
                              │
                              ▼
┌─────────────────────────────────────────────────────────────┐
│  Week 2: Monitoring & Alerting                              │
│  → Metric Filters                                           │
│  → CloudWatch Alarms                                        │
│  → Structured Logging (JSON)                                │
└─────────────────────────────────────────────────────────────┘
                              │
                              ▼
┌─────────────────────────────────────────────────────────────┐
│  Week 3: Integration & Advanced                             │
│  → Subscription Filters (Lambda/S3)                         │
│  → Retention Policies                                       │
│  → Export to S3                                             │
└─────────────────────────────────────────────────────────────┘
```

---

## 📖 Chi Tiết Từng Topic

### 1. Log Groups & Streams

```
┌─────────────────────────────────────────────────────────────┐
│  Log Group: /app/my-service/production                      │
│  ├── Log Stream: instance-001                               │
│  │   ├── 2026-01-17 10:00:00 INFO  Starting app...          │
│  │   └── 2026-01-17 10:00:01 INFO  App started              │
│  ├── Log Stream: instance-002                               │
│  └── Log Stream: instance-003                               │
└─────────────────────────────────────────────────────────────┘

Log Group = container (theo app/env)
Log Stream = source (theo instance/container)
```

---

### 2. Push Logs từ Spring Boot

```xml
<!-- pom.xml -->
<dependency>
    <groupId>ca.pjer</groupId>
    <artifactId>logback-awslogs-appender</artifactId>
</dependency>
```

```yaml
# application.yml
logging:
  config: classpath:logback-cloudwatch.xml
```

---

### 3. Log Insights Query

```sql
-- Tìm tất cả errors trong 1 giờ qua
fields @timestamp, @message
| filter @message like /ERROR/
| sort @timestamp desc
| limit 100

-- Count errors by type
fields @message
| filter @message like /Exception/
| stats count(*) by @message
```

---

### 5. Metric Filters

```
Log Group → Metric Filter → CloudWatch Metric

Pattern: "ERROR"
Metric: ErrorCount
Namespace: MyApp

Mỗi log chứa "ERROR" → metric +1
```

---

### 6. CloudWatch Alarms

```
Metric: ErrorCount > 10 trong 5 phút
  │
  ▼
Action: SNS → Slack/PagerDuty/Email
```

---

## 🔗 Official Documentation

| Topic | AWS Docs |
|-------|----------|
| CloudWatch Logs | https://docs.aws.amazon.com/AmazonCloudWatch/latest/logs/WhatIsCloudWatchLogs.html |
| Log Insights | https://docs.aws.amazon.com/AmazonCloudWatch/latest/logs/AnalyzingLogData.html |
| Metric Filters | https://docs.aws.amazon.com/AmazonCloudWatch/latest/logs/MonitoringLogData.html |
| Subscription Filters | https://docs.aws.amazon.com/AmazonCloudWatch/latest/logs/SubscriptionFilters.html |
| Alarms | https://docs.aws.amazon.com/AmazonCloudWatch/latest/monitoring/AlarmThatSendsEmail.html |

---

## 💡 Next Step Recommendation

**Start with:** Log Groups + Push Logs từ Spring Boot

Lý do:
- Foundation cho tất cả features khác
- Có thể test với LocalStack
- Practical ngay cho debugging

---

*Document created: 2026-01-17*
*Project: realworld-exam*
