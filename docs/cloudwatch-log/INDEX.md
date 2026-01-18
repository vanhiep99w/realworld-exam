# CloudWatch Logs - Index

Danh sách các chủ đề CloudWatch Logs, sắp xếp theo độ khó và tính thực tế.

---

## 📋 Giới thiệu

| # | Topic | Description | Docs |
|---|-------|-------------|------|
| 0 | **Introduction** | CloudWatch Logs là gì, so sánh với ELK/S3 | [CLOUDWATCH_LOGS_INTRODUCTION.md](./CLOUDWATCH_LOGS_INTRODUCTION.md) |
| - | **Logging Best Practices** | Naming, levels, disk space, môi trường | [LOGGING_BEST_PRACTICES.md](./LOGGING_BEST_PRACTICES.md) |
| - | **MDC (Mapped Diagnostic Context)** | Thread-local context cho logging | [MDC.md](./MDC.md) |

--- 

## ✅ Đã Implement

| # | Topic | Description | Docs |
|---|-------|-------------|------|
| 1 | **Log Groups & Streams + Push Logs** | Cấu trúc cơ bản + Custom Logback Appender | [CLOUDWATCH_LOGS_GROUPS_STREAMS.md](./CLOUDWATCH_LOGS_GROUPS_STREAMS.md) |
| 2 | **Log Insights Query** | Query logs với SQL-like syntax | [CLOUDWATCH_LOGS_INSIGHTS.md](./CLOUDWATCH_LOGS_INSIGHTS.md) |

---

## 📖 Đã Document

| # | Topic | Description | Docs |
|---|-------|-------------|------|
| 3 | **Retention Policies** | Tự động xóa logs sau X ngày | [CLOUDWATCH_LOGS_RETENTION.md](./CLOUDWATCH_LOGS_RETENTION.md) |
| 4 | **Metric Filters** | Tạo metrics từ log patterns | [CLOUDWATCH_LOGS_METRIC_FILTERS.md](./CLOUDWATCH_LOGS_METRIC_FILTERS.md) |
| 5 | **CloudWatch Alarms** | Alert khi metric vượt threshold | [CLOUDWATCH_ALARMS.md](./CLOUDWATCH_ALARMS.md) |
| 6 | **Subscription Filters** | Stream logs to Lambda/Kinesis/S3 | [SUBSCRIPTION_FILTERS.md](./SUBSCRIPTION_FILTERS.md) |
| 7 | **Structured Logging** | JSON logs, MDC context | [STRUCTURED_LOGGING.md](./STRUCTURED_LOGGING.md) |
| 8 | **CloudWatch Logs Agent** | Push logs từ EC2/on-premise | [CLOUDWATCH_LOGS_AGENT.md](./CLOUDWATCH_LOGS_AGENT.md) |
| 9 | **Contributor Insights** | Top-N analysis trên logs | [CONTRIBUTOR_INSIGHTS.md](./CONTRIBUTOR_INSIGHTS.md) |
| 10 | **Export to S3** | Archive logs to S3 | [EXPORT_TO_S3.md](./EXPORT_TO_S3.md) |

---

## 📚 Chưa Document

| # | Topic | Description | Use Case |
|---|-------|-------------|----------|
| 11 | **Cross-Account Logging** | Aggregate logs từ multiple accounts | Enterprise, multi-account setup |

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
