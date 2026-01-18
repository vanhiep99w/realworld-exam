# CloudWatch Alarms

## Mục lục
1. [Tổng quan](#tổng-quan)
2. [Alarm States](#alarm-states)
3. [Alarm Types](#alarm-types)
4. [Cách Alarm đánh giá (Evaluation)](#cách-alarm-đánh-giá)
5. [Alarm Actions](#alarm-actions)
6. [Missing Data Handling](#missing-data-handling)
7. [Tạo Alarm (Console & CLI)](#tạo-alarm)
8. [Pricing](#pricing)
9. [Tài liệu tham khảo](#tài-liệu-tham-khảo)

---

## Tổng quan

CloudWatch Alarms là **1 feature** trong Amazon CloudWatch (không phải service riêng), theo dõi metrics và **tự động thực hiện actions** khi metric vượt ngưỡng (threshold).

```
┌─────────────────────────────────────────────────────────────────┐
│                    Amazon CloudWatch (1 service)                │
├─────────────────────────────────────────────────────────────────┤
│  ┌──────────┐  ┌──────────┐  ┌──────────┐  ┌──────────┐        │
│  │ Metrics  │  │  Alarms  │  │   Logs   │  │Dashboards│  ...   │
│  │(feature) │  │(feature) │  │(feature) │  │(feature) │        │
│  └──────────┘  └──────────┘  └──────────┘  └──────────┘        │
└─────────────────────────────────────────────────────────────────┘
```

```
┌─────────────────┐      ┌─────────────────┐      ┌─────────────────────┐
│  CloudWatch     │      │  CloudWatch     │      │  Actions            │
│  Metric         │─────►│  Alarm          │─────►│  - SNS (Email/Slack)│
│  (ErrorCount=15)│      │  (threshold=10) │      │  - Lambda           │
└─────────────────┘      └─────────────────┘      │  - Auto Scaling     │
                                                  │  - EC2 actions      │
                                                  └─────────────────────┘
```

### Nguồn Metrics cho Alarms

Alarms nhận input từ **CloudWatch Metrics**, có thể đến từ **3 nguồn**:

```
                                    ┌─────────────────┐
                                    │ CloudWatch      │
                                    │ Alarms          │
                                    └────────▲────────┘
                                             │
                                    ┌────────┴────────┐
                                    │ CloudWatch      │
                                    │ Metrics         │
                                    └────────▲────────┘
                                             │
         ┌───────────────────────────────────┼───────────────────────────────────┐
         │                                   │                                   │
┌────────┴────────┐             ┌────────────┴────────────┐          ┌───────────┴───────────┐
│ Cách 1: Từ Logs │             │ Cách 2: AWS Services    │          │ Cách 3: Custom App    │
│                 │             │ (tự động gửi metrics)   │          │ (PutMetricData API)   │
│ Logs            │             │                         │          │                       │
│   ↓             │             │ EC2 → CPUUtilization    │          │ App gọi API gửi số    │
│ Metric Filter   │             │ RDS → Connections       │          │ trực tiếp             │
│   ↓             │             │ Lambda → Errors         │          │                       │
│ Custom Metric   │             │                         │          │                       │
└─────────────────┘             └─────────────────────────┘          └───────────────────────┘
```

| Nguồn | Ví dụ | Cần Metric Filter? |
|-------|-------|-------------------|
| **CloudWatch Logs** | Đếm ERROR trong logs | ✅ Cần |
| **AWS Services** | EC2 CPU, RDS connections | ❌ Tự động |
| **Custom App** | OrderCount, latency | ❌ Gọi API trực tiếp |

> **Logs → Alarm** phải qua trung gian **Metric Filter** để chuyển logs thành metrics trước.

### Cách 2: AWS Services (Tự động)

AWS Services **tự động gửi metrics** mà không cần cấu hình:

```
┌─────────────────┐                      ┌─────────────────┐
│  EC2 Instance   │ ── tự động gửi ──►   │ CloudWatch      │
│                 │    mỗi 5 phút        │ Metrics         │
│  CPU = 75%      │                      │                 │
└─────────────────┘                      └─────────────────┘
```

| Loại | Tần suất | Chi phí |
|------|----------|---------|
| **Basic Monitoring** | 5 phút/lần | **Miễn phí** |
| **Detailed Monitoring** | 1 phút/lần | Có phí (~$2.10/instance/tháng) |

**Ví dụ metrics tự động (miễn phí):**

| AWS Service | Metrics | Ví dụ giá trị |
|-------------|---------|---------------|
| **EC2** | CPUUtilization, NetworkIn, DiskReadOps | CPU = 75% |
| **RDS** | DatabaseConnections, ReadLatency | Connections = 50 |
| **Lambda** | Invocations, Errors, Duration | Errors = 3 |
| **S3** | NumberOfObjects, BucketSizeBytes | Size = 10GB |
| **SQS** | NumberOfMessagesReceived | Messages = 100 |
| **ELB** | RequestCount, HTTPCode_5XX | 5XX = 10 |

### Cách 3: Custom App (PutMetricData API)

App gọi API gửi số **trực tiếp** đến CloudWatch, không qua logs:

```
┌─────────────────────────────────────────────────────────────────┐
│  Your Application                                               │
│                                                                 │
│  orderCount = 50                                                │
│  responseTime = 230ms                                           │
│                                                                 │
│  → Gọi API: cloudwatch.putMetricData(OrderCount = 50)           │
└───────────────────────────┬─────────────────────────────────────┘
                            │
                            ▼  (gọi API, không qua logs)
                  ┌─────────────────┐
                  │ CloudWatch      │
                  │ Metrics         │
                  │ OrderCount = 50 │
                  └─────────────────┘
```

**Ví dụ gửi metric bằng AWS CLI:**

```bash
aws cloudwatch put-metric-data \
  --namespace "MyApp/Business" \
  --metric-name "ActiveUsers" \
  --value 150 \
  --unit Count

aws cloudwatch put-metric-data \
  --namespace "MyApp/Business" \
  --metric-name "Revenue" \
  --value 5000.50 \
  --unit None \
  --dimensions Currency=USD
```

**Use cases cho Custom Metrics:**

| Metric | Mô tả | Tại sao không dùng logs? |
|--------|-------|--------------------------|
| **OrderCount** | Số đơn hàng/phút | Cần số chính xác |
| **Revenue** | Doanh thu real-time | Là số tiền, không phải text |
| **ActiveUsers** | Số users online | Từ memory, không có trong logs |
| **ResponseTime** | Latency API | Đo chính xác ms |

**Khi nào dùng cách nào?**

| Logs + Metric Filter | PutMetricData API |
|---------------------|-------------------|
| ✓ Đã có logs sẵn | ✓ Cần số chính xác |
| ✓ Đếm events đơn giản | ✓ Data không có trong logs |
| ✓ Không muốn sửa code | ✓ Business metrics phức tạp |

### Flow với Metric Filter

```
┌──────────┐    ┌───────────────┐    ┌────────────┐    ┌─────────┐    ┌────────────┐
│  Logs    │───►│ Metric Filter │───►│  Metric    │───►│  Alarm  │───►│  SNS/Slack │
│  ERROR   │    │ pattern=ERROR │    │ ErrorCount │    │  >10    │    │  Email     │
└──────────┘    └───────────────┘    └────────────┘    └─────────┘    └────────────┘
```

---

## Alarm States

Alarm có **3 trạng thái**:

| State | Màu | Mô tả |
|-------|-----|-------|
| **OK** | 🟢 Xanh | Metric nằm trong ngưỡng cho phép |
| **ALARM** | 🔴 Đỏ | Metric vượt ngưỡng → trigger actions |
| **INSUFFICIENT_DATA** | ⚪ Xám | Không đủ data để đánh giá (vừa tạo alarm, hoặc metric không có data) |

### Ví dụ chuyển trạng thái

```
Threshold: CPU > 80%

Timeline:
┌─────────┬─────────┬─────────┬─────────┬─────────┬─────────┐
│ 10:00   │ 10:01   │ 10:02   │ 10:03   │ 10:04   │ 10:05   │
│ CPU=50% │ CPU=85% │ CPU=90% │ CPU=70% │ (no data)│ CPU=60% │
│         │         │         │         │         │         │
│ 🟢 OK   │ 🔴 ALARM│ 🔴 ALARM│ 🟢 OK   │ ⚪ INSUF │ 🟢 OK   │
└─────────┴─────────┴─────────┴─────────┴─────────┴─────────┘
              │                    │          │
              ▼                    ▼          ▼
         Action:              Action:    (giữ nguyên
         Send alert!          Send OK!   hoặc tùy config)
```

### Khi nào chuyển state?

```
┌──────────────────────┐
│  INSUFFICIENT_DATA   │  ← Mới tạo alarm, chưa có data
│      (ban đầu)       │
└──────────┬───────────┘
           │ có data
           ▼
    ┌──────────────┐         metric > threshold        ┌──────────────┐
    │              │ ─────────────────────────────────►│              │
    │    🟢 OK     │                                   │  🔴 ALARM    │
    │              │◄───────────────────────────────── │              │
    └──────────────┘         metric <= threshold       └──────────────┘
           │                                                  │
           │              ┌──────────────────────┐            │
           └─────────────►│  INSUFFICIENT_DATA   │◄───────────┘
              không có    │   (mất data)         │   không có
              data        └──────────────────────┘   data
```

---

## Alarm Types

### 1. Metric Alarm

Theo dõi **1 metric** hoặc math expression:

```
Metric: ErrorCount
Condition: > 10 trong 5 phút
Action: Send SNS notification
```

### 2. Composite Alarm

Kết hợp **nhiều alarms** với logic AND/OR:

```
CompositeAlarm = Alarm1 AND Alarm2 AND Alarm3

Chỉ trigger khi TẤT CẢ 3 alarms đều ALARM
→ Giảm "alarm noise" (false positives)
```

| | Metric Alarm | Composite Alarm |
|--|--------------|-----------------|
| **Theo dõi** | 1 metric | Nhiều alarms khác |
| **Logic** | Threshold | AND/OR expression |
| **Use case** | Đơn giản | Phức tạp, giảm noise |
| **EC2/AutoScaling actions** | ✅ Có | ❌ Không |

---

## Cách Alarm đánh giá

### 3 thông số quan trọng

| Thông số | Mô tả | Ví dụ |
|----------|-------|-------|
| **Period** | Khoảng thời gian cho mỗi data point | 60 seconds (1 phút) |
| **Evaluation Periods** | Số periods gần nhất để đánh giá | 3 periods |
| **Datapoints to Alarm** | Số datapoints phải breach để trigger | 2 datapoints |

### Ví dụ: "2 out of 3" Alarm

```
Cấu hình:
- Period: 1 phút
- Evaluation Periods: 3
- Datapoints to Alarm: 2
- Threshold: > 10

Timeline:
┌─────────┬─────────┬─────────┬─────────┬─────────┬─────────┐
│ Phút 1  │ Phút 2  │ Phút 3  │ Phút 4  │ Phút 5  │ Phút 6  │
│ val=5   │ val=15  │ val=12  │ val=8   │ val=6   │ val=20  │
│   OK    │ BREACH! │ BREACH! │   OK    │   OK    │ BREACH! │
└─────────┴─────────┴─────────┴─────────┴─────────┴─────────┘
                ↓
        Phút 3: xét [1,2,3] → 2/3 breach → ALARM!
        Phút 4: xét [2,3,4] → 2/3 breach → ALARM (vẫn giữ)
        Phút 5: xét [3,4,5] → 1/3 breach → OK
        Phút 6: xét [4,5,6] → 1/3 breach → OK
```

> **"M out of N"**: Datapoints to Alarm (M) / Evaluation Periods (N)

---

## Alarm Actions

Khi alarm thay đổi state, có thể trigger các actions:

| Action | Mô tả | Use Case |
|--------|-------|----------|
| **SNS Topic** | Gửi notification đến subscribers | Email, SMS, Slack, PagerDuty |
| **Lambda** | Invoke Lambda function | Custom automation |
| **EC2 Actions** | Stop, Terminate, Reboot, Recover instance | Cost saving, recovery |
| **Auto Scaling** | Scale in/out | Handle load changes |
| **Systems Manager** | Create OpsItem/Incident | Incident management |

### Ví dụ cấu hình Actions

```
┌─────────────────────────────────────────────────────────────┐
│  Alarm: HighErrorRate                                       │
├─────────────────────────────────────────────────────────────┤
│  OK → ALARM:                                                │
│    → SNS: ops-alerts (Slack notification)                   │
│    → Lambda: create-jira-ticket                             │
│                                                             │
│  ALARM → OK:                                                │
│    → SNS: ops-alerts (Recovery notification)                │
│                                                             │
│  Any → INSUFFICIENT_DATA:                                   │
│    → SNS: ops-alerts (Warning: no data)                     │
└─────────────────────────────────────────────────────────────┘
```

> **Lưu ý:** Actions chỉ trigger khi **state thay đổi**, không lặp lại nếu state giữ nguyên (trừ Auto Scaling actions).

---

## Missing Data Handling

Khi metric không có data, alarm xử lý thế nào?

| Option | Hành vi | Use Case |
|--------|---------|----------|
| **missing** | Giữ nguyên state hiện tại | Default, an toàn |
| **notBreaching** | Coi như data = OK | Metric thỉnh thoảng mới có |
| **breaching** | Coi như data = ALARM | Critical monitoring |
| **ignore** | Không đánh giá | Hiếm dùng |

```
Ví dụ với "breaching":

┌─────────┬─────────┬─────────┐
│ Phút 1  │ Phút 2  │ Phút 3  │
│ val=15  │ (no data)│ (no data)│
│ BREACH! │ BREACH! │ BREACH! │  ← missing = breaching
└─────────┴─────────┴─────────┘
        → 3/3 breach → ALARM!
```

---

## Tạo Alarm

### AWS Console

1. **CloudWatch** → **Alarms** → **Create alarm**
2. **Select metric** → Chọn metric cần monitor
3. **Specify metric and conditions**:
   - Statistic: Average, Sum, Maximum...
   - Period: 1 minute, 5 minutes...
   - Threshold: Greater than X
4. **Configure actions**: Chọn SNS topic
5. **Name and description**
6. **Create alarm**

### AWS CLI

```bash
# Tạo alarm cho ErrorCount metric
aws cloudwatch put-metric-alarm \
  --alarm-name HighErrorRate \
  --alarm-description "Alarm when errors exceed 10 per 5 minutes" \
  --metric-name ErrorCount \
  --namespace MyApp \
  --statistic Sum \
  --period 300 \
  --threshold 10 \
  --comparison-operator GreaterThanThreshold \
  --evaluation-periods 1 \
  --alarm-actions arn:aws:sns:us-east-1:123456789:ops-alerts \
  --ok-actions arn:aws:sns:us-east-1:123456789:ops-alerts \
  --treat-missing-data missing
```

### Ví dụ: Alarm cho Metric từ Metric Filter

```bash
# 1. Tạo Metric Filter (từ logs)
aws logs put-metric-filter \
  --log-group-name /app/my-service \
  --filter-name ErrorFilter \
  --filter-pattern "ERROR" \
  --metric-transformations \
    metricName=ErrorCount,metricNamespace=MyApp,metricValue=1,defaultValue=0

# 2. Tạo Alarm cho metric đó
aws cloudwatch put-metric-alarm \
  --alarm-name AppErrorAlarm \
  --metric-name ErrorCount \
  --namespace MyApp \
  --statistic Sum \
  --period 300 \
  --threshold 10 \
  --comparison-operator GreaterThanThreshold \
  --evaluation-periods 1 \
  --alarm-actions arn:aws:sns:us-east-1:123456789:my-topic
```

### Verify Alarm

```bash
# List alarms
aws cloudwatch describe-alarms --alarm-names HighErrorRate

# Get alarm history
aws cloudwatch describe-alarm-history --alarm-name HighErrorRate
```

---

## Pricing

| Thành phần | Chi phí |
|------------|---------|
| **Standard Alarm** (1 phút resolution) | $0.10/alarm/tháng |
| **High Resolution Alarm** (10-30 giây) | $0.30/alarm/tháng |
| **Composite Alarm** | $0.50/alarm/tháng |
| **SNS notifications** | Xem SNS pricing |

### Ví dụ tính phí

```
5 Standard Alarms = 5 × $0.10 = $0.50/tháng
2 Composite Alarms = 2 × $0.50 = $1.00/tháng
─────────────────────────────────────────────
Total = $1.50/tháng
```

### Free Tier

- **10 alarm metrics** miễn phí (không áp dụng high-resolution)

---

## Best Practices

| Practice | Lý do |
|----------|-------|
| **Set OK actions** | Biết khi nào issue được resolve |
| **Use Composite Alarms** | Giảm false positives |
| **Set appropriate periods** | Tránh flapping (ALARM ↔ OK liên tục) |
| **Use "M out of N"** | Tránh trigger do spike nhất thời |
| **Handle missing data** | Tránh INSUFFICIENT_DATA không mong muốn |

---

## Tài liệu tham khảo

| Chủ đề | Link |
|--------|------|
| **Using CloudWatch Alarms** | https://docs.aws.amazon.com/AmazonCloudWatch/latest/monitoring/AlarmThatSendsEmail.html |
| **Alarm Actions** | https://docs.aws.amazon.com/AmazonCloudWatch/latest/monitoring/AlarmActions.html |
| **Composite Alarms** | https://docs.aws.amazon.com/AmazonCloudWatch/latest/monitoring/Create_Composite_Alarm.html |
| **CloudWatch Pricing** | https://aws.amazon.com/cloudwatch/pricing/ |

---

*Ngày tạo: 2026-01-17*
*Project: realworld-exam*
