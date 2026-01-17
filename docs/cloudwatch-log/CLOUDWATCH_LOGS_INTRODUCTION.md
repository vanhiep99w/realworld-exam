# CloudWatch Logs - Introduction

## Mục lục
1. [CloudWatch Logs là gì](#cloudwatch-logs-là-gì)
2. [Tại sao cần CloudWatch Logs](#tại-sao-cần-cloudwatch-logs)
3. [So sánh với ELK Stack](#so-sánh-với-elk-stack)
4. [So sánh với S3](#so-sánh-với-s3)
5. [Tài liệu tham khảo](#tài-liệu-tham-khảo)

---

## CloudWatch Logs là gì

CloudWatch Logs là dịch vụ **Centralized Logging** của AWS - gom tất cả logs từ nhiều servers/containers về một nơi duy nhất để search, query và alert.

```
┌──────────┐  ┌──────────┐  ┌──────────┐
│ Server 1 │  │ Server 2 │  │ Server 3 │
└────┬─────┘  └────┬─────┘  └────┬─────┘
     │             │             │
     └─────────────┼─────────────┘
                   ▼
         ┌─────────────────┐
         │ CloudWatch Logs │  ← Query, search, alert
         └─────────────────┘      từ 1 nơi duy nhất
```

---

## Tại sao cần CloudWatch Logs

### Vấn đề khi không có Centralized Logging

```
Không có CloudWatch Logs:
┌──────────┐  ┌──────────┐  ┌──────────┐
│ Server 1 │  │ Server 2 │  │ Server 3 │
│ logs.txt │  │ logs.txt │  │ logs.txt │
└──────────┘  └──────────┘  └──────────┘
     ↓             ↓             ↓
   SSH vào     SSH vào       SSH vào
   từng máy    từng máy      từng máy
   để xem      để xem        để xem
```

### Giải pháp với CloudWatch Logs

| Tình huống | Giải pháp |
|------------|-----------|
| User báo lỗi lúc 3h sáng | Query logs theo timestamp để debug |
| Muốn biết có bao nhiêu lỗi 500 hôm nay | Log Insights: `filter @message like /500/` |
| Alert khi có nhiều errors | Metric Filter → Alarm → Slack/PagerDuty |
| Giữ logs 90 ngày cho audit | Retention Policy tự động xóa logs cũ |

**Tóm lại:** Thay vì SSH vào từng server xem logs, bạn có 1 nơi duy nhất để search, query, và alert.

---

## So sánh với ELK Stack

| Tiêu chí | CloudWatch Logs | ELK Stack |
|----------|-----------------|-----------|
| **Setup** | Zero - managed service | Tự setup & maintain cluster |
| **Cost** | Pay per GB ingested + stored | Infra cost (EC2/RAM/Disk) + ops time |
| **Query** | Log Insights (đơn giản) | Lucene/KQL (powerful) |
| **Visualization** | Basic | Kibana rất mạnh |
| **Scalability** | AWS lo | Tự scale Elasticsearch |
| **Vendor lock-in** | AWS only | Portable |
| **Alerting** | CloudWatch Alarms | ElastAlert / Kibana Alerting |

### Khi nào dùng gì?

```
CloudWatch Logs                        ELK Stack
─────────────────                      ─────────────
✓ Đã dùng AWS                          ✓ Multi-cloud / on-premise
✓ Team nhỏ, không muốn ops             ✓ Query phức tạp, analytics
✓ Logs < 100GB/ngày                    ✓ Logs lớn, cần full-text search
✓ Tích hợp Lambda/ECS/EKS dễ           ✓ Cần Kibana dashboards đẹp
✓ Budget ổn định                       ✓ Đã có team vận hành ES
```

### Thực tế phổ biến

Nhiều team dùng **cả hai**:
- **CloudWatch Logs** cho operational logs (debug, errors)
- **ELK** cho analytics, business metrics, hoặc logs cần query phức tạp

CloudWatch Logs phù hợp khi bạn đã all-in AWS và muốn **đơn giản, không maintain infra**.

---

## So sánh với S3

Giống về cấu trúc phân cấp (Bucket/Object vs Log Group/Stream), nhưng **mục đích khác nhau**:

| | S3 | CloudWatch Logs |
|---|---|---|
| **Mục đích** | Lưu trữ file (object storage) | Lưu + query logs real-time |
| **Cấu trúc** | Bucket → Object | Log Group → Stream → Event |
| **Query** | S3 Select (query nội dung file) | Log Insights (query log events) |
| **Real-time** | Không | Có - xem logs ngay khi push |
| **Alerting** | Không | Metric Filter → Alarm |
| **Retention** | Manual / Lifecycle | Tự động theo policy |
| **Giá** | $0.023/GB/tháng | $0.50/GB ingested + $0.03/GB stored |

### S3 Select vs CloudWatch Logs Insights

```sql
-- S3 Select: Query 1 file CSV cụ thể
SELECT * FROM s3object WHERE status = 'error'
-- Phải biết trước file nào, file phải có format đúng (CSV/JSON/Parquet)

-- CloudWatch Logs Insights: Query tất cả logs
fields @timestamp, @message
| filter @message like /ERROR/
| sort @timestamp desc
-- Query cross tất cả streams, real-time, có alerting
```

**Tóm lại:** S3 là "kho chứa đồ", CloudWatch Logs là "bảng điều khiển giám sát" với khả năng search và alert real-time.

---

## Tài liệu tham khảo

| Chủ đề | Link |
|--------|------|
| **What is CloudWatch Logs** | https://docs.aws.amazon.com/AmazonCloudWatch/latest/logs/WhatIsCloudWatchLogs.html |
| **CloudWatch Logs Concepts** | https://docs.aws.amazon.com/AmazonCloudWatch/latest/logs/CloudWatchLogsConcepts.html |
| **LocalStack CloudWatch Logs** | https://docs.localstack.cloud/aws/services/logs/ |

---

*Ngày tạo: 2026-01-17*  
*Project: realworld-exam*
