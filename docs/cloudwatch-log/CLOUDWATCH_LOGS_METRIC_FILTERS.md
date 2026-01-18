# CloudWatch Logs - Metric Filters

## Mục lục
1. [Tổng quan](#tổng-quan)
2. [Khái niệm](#khái-niệm)
3. [Filter Pattern Syntax](#filter-pattern-syntax)
4. [Use Cases](#use-cases)
5. [Tạo Metric Filter (AWS Console & CLI)](#tạo-metric-filter)
6. [Pricing (Chi phí)](#pricing-chi-phí)
7. [Tài liệu tham khảo](#tài-liệu-tham-khảo)

---

## Tổng quan

Metric Filters cho phép bạn tìm kiếm và lọc log data, sau đó **biến đổi thành CloudWatch Metrics** để graph hoặc đặt alarm.

```
┌─────────────────┐      ┌──────────────────┐      ┌─────────────────────┐
│   Log Group     │─────►│  Metric Filter   │─────►│  CloudWatch Metric  │
│  (log events)   │      │  (pattern match) │      │  (count/value)      │
└─────────────────┘      └──────────────────┘      └─────────────────────┘
                                                            │
                                                            ▼
                                                   ┌─────────────────────┐
                                                   │  CloudWatch Alarm   │
                                                   │  (SNS/Slack/Email)  │
                                                   └─────────────────────┘
```

### Metric Filters vs Logs Insights

| | Metric Filters | Logs Insights |
|--|----------------|---------------|
| **Mục đích** | Tạo CloudWatch Metrics | Query/analyze logs |
| **Tính chất** | Real-time, continuous | Ad-hoc, on-demand |
| **Output** | Metrics → Alarms → Notifications | Query results (table/chart) |
| **Dữ liệu cũ** | ❌ Không xử lý logs trước khi tạo filter | ✅ Query được tất cả logs trong retention |
| **Use case** | Monitoring, alerting | Debugging, investigation |

### Đặc điểm quan trọng

| Đặc điểm | Mô tả |
|----------|-------|
| **Không retroactive** | Chỉ xử lý logs SAU khi tạo filter, không áp dụng cho logs cũ |
| **Real-time** | CloudWatch aggregate và report metric values mỗi phút |
| **Log class** | Chỉ hỗ trợ log groups ở Standard log class |
| **Số lượng** | Tối đa 100 metric filters/log group |

---

## Khái niệm

### Các thành phần của Metric Filter

| Thành phần | Mô tả | Ví dụ |
|------------|-------|-------|
| **Filter Pattern** | Pattern để match log events | `ERROR`, `[ip, user, status=404]` |
| **Metric Name** | Tên metric sẽ được tạo | `ErrorCount` |
| **Metric Namespace** | Namespace chứa metric | `MyApp/Production` |
| **Metric Value** | Giá trị increment khi match | `1` hoặc `$bytes` (từ log field) |
| **Default Value** | Giá trị khi không có match (nên set = 0) | `0` |
| **Dimensions** | Key-value pairs để phân loại metric | `{"Environment": "prod"}` |

### Metric Value vs Default Value

```
Minute 1: 2 logs match     → metric = 2 (value × matches)
Minute 2: 0 logs match     → metric = 0 (default value)
Minute 3: không có log nào → không report (khác với 0!)
```

> **Best practice**: Luôn set default value = 0 để tránh "spotty metrics" (gaps trong data)

---

## Filter Pattern Syntax

### 1. Simple Text Matching

Match bất kỳ log nào chứa text:

```
ERROR                    # Match logs chứa "ERROR"
"Exception"              # Match logs chứa "Exception"  
?ERROR                   # Match "ERROR" hoặc "error" (case-insensitive)
```

### 2. Multiple Terms

```
ERROR Exception          # AND: chứa cả "ERROR" VÀ "Exception"
?error ?warning          # OR ngầm định khi có nhiều terms
```

### 3. Exclude Terms

```
ERROR -DEBUG             # Chứa "ERROR" nhưng KHÔNG chứa "DEBUG"
```

### 4. Space-Delimited Log Events

Cho logs có format cố định như Apache access log:

```
127.0.0.1 - frank [10/Oct/2000:13:55:36 -0700] "GET /page.html HTTP/1.0" 404 2326
```

Filter pattern:

```
[ip, id, user, timestamp, request, status_code=404, size]
```

Các operator hỗ trợ:
- `=`, `!=` : so sánh bằng/khác
- `<`, `>`, `<=`, `>=` : so sánh số
- `*` : wildcard

### 5. JSON Log Events

Cho structured JSON logs:

```json
{"level": "ERROR", "message": "Connection failed", "code": 500}
```

Filter pattern:

```
{ $.level = "ERROR" }                           # Match level = ERROR
{ $.code >= 400 && $.code < 500 }               # Match 4xx errors
{ $.level = "ERROR" && $.message = "*failed*" } # AND với wildcard
```

---

## Use Cases

### 1. Count Application Errors

**Scenario:** Đếm số lỗi ERROR trong application logs

```
Filter Pattern: ERROR
Metric Name: ErrorCount
Metric Value: 1
Default Value: 0
```

**Real-world example:** E-commerce site track số lượng exceptions để biết khi nào cần scale up hoặc rollback deployment.

### 2. Monitor HTTP 4xx/5xx Responses

**Scenario:** Theo dõi API response codes

```
# Cho JSON logs: {"statusCode": 500, "path": "/api/users"}
Filter Pattern: { $.statusCode >= 400 }
Metric Name: HttpErrorCount
```

**Real-world example:** API Gateway logs → Metric Filter → Alarm khi error rate > 5%

### 3. Track Specific Business Events

**Scenario:** Đếm số lần user login failed

```
Filter Pattern: "LOGIN_FAILED"
Metric Name: LoginFailureCount
```

**Real-world example:** Security team đặt alarm khi có >10 login failures/phút từ 1 IP (brute force detection)

### 4. Extract Numeric Values

**Scenario:** Track latency từ logs

```
# Log: {"latency": 250, "endpoint": "/api/orders"}
Filter Pattern: { $.latency = * }
Metric Value: $.latency
Metric Name: APILatency
Unit: Milliseconds
```

---

## Tạo Metric Filter

### AWS Console

1. **CloudWatch** → **Log groups** → Chọn log group
2. **Actions** → **Create metric filter**
3. Nhập **Filter Pattern**, test với sample logs
4. **Next** → Nhập Filter Name, Metric Details
5. Set **Default Value = 0**
6. **Create metric filter**

### AWS CLI

**Ví dụ 1: Count tất cả log events**

```bash
aws logs put-metric-filter \
  --log-group-name MyApp/access.log \
  --filter-name EventCount \
  --filter-pattern " " \
  --metric-transformations \
    metricName=MyAppEventCount,metricNamespace=MyNamespace,metricValue=1,defaultValue=0
```

**Ví dụ 2: Count HTTP 404 errors**

```bash
aws logs put-metric-filter \
  --log-group-name MyApp/access.log \
  --filter-name HTTP404Errors \
  --filter-pattern '[ip, id, user, timestamp, request, status_code=404, size]' \
  --metric-transformations \
    metricName=ApacheNotFoundErrorCount,metricNamespace=MyNamespace,metricValue=1
```

**Ví dụ 3: JSON logs - count ERROR level**

```bash
aws logs put-metric-filter \
  --log-group-name /app/my-service \
  --filter-name ErrorLevelCount \
  --filter-pattern '{ $.level = "ERROR" }' \
  --metric-transformations \
    metricName=ErrorLogCount,metricNamespace=MyApp,metricValue=1,defaultValue=0
```

### Verify Metric Filter

```bash
aws logs describe-metric-filters --log-group-name MyApp/access.log
```

---

## Kết hợp với CloudWatch Alarms

Sau khi có metric, tạo alarm để notify:

```
┌─────────────────────────────────────────────────────────────┐
│  Metric: ErrorCount                                         │
│  Condition: > 10 trong 5 phút                               │
│  Action: SNS Topic → Slack/PagerDuty/Email                  │
└─────────────────────────────────────────────────────────────┘
```

```bash
aws cloudwatch put-metric-alarm \
  --alarm-name HighErrorRate \
  --metric-name ErrorLogCount \
  --namespace MyApp \
  --statistic Sum \
  --period 300 \
  --threshold 10 \
  --comparison-operator GreaterThanThreshold \
  --evaluation-periods 1 \
  --alarm-actions arn:aws:sns:us-east-1:123456789:my-topic
```

---

## Pricing (Chi phí)

### Metric Filter vs Custom Metric

```
┌─────────────────────────────────────────────────────────────────┐
│  LOG EVENTS (dữ liệu thô)                                       │
├─────────────────────────────────────────────────────────────────┤
│  10:00:01  INFO  User login success                             │
│  10:00:02  ERROR Connection timeout                  ← match!   │
│  10:00:03  INFO  User logout                                    │
│  10:00:04  ERROR Database failed                     ← match!   │
│  10:00:05  ERROR Null pointer exception              ← match!   │
└─────────────────────────────────────────────────────────────────┘
                                │
                                ▼
┌─────────────────────────────────────────────────────────────────┐
│  METRIC FILTER = "Công thức đếm"                    → FREE      │
│                                                                 │
│  Pattern: "ERROR"                                               │
│  Hành động: Mỗi lần thấy "ERROR" → cộng 1                       │
└─────────────────────────────────────────────────────────────────┘
                                │
                                ▼
┌─────────────────────────────────────────────────────────────────┐
│  CUSTOM METRIC = "Kết quả đếm"                      → $0.30/thg │
│                                                                 │
│  ErrorCount = 3                                                 │
│  (Con số này dùng để vẽ graph, đặt alarm)                       │
└─────────────────────────────────────────────────────────────────┘
```

| | Metric Filter | Custom Metric |
|--|---------------|---------------|
| **Là gì** | Công thức/luật đếm | Kết quả/con số |
| **Ví dụ** | "Đếm mỗi log có chữ ERROR" | `ErrorCount = 3` |
| **Dùng để** | Định nghĩa cách đếm | Vẽ graph, đặt alarm |
| **Phí** | **Miễn phí** | **$0.30/metric/tháng** |

> **Tóm lại:** Tạo công thức (Metric Filter) = FREE. Khi công thức tạo ra giá trị thật (Custom Metric) = mất phí.

### Metrics Storage (Lưu trữ)

**Không mất phí lưu trữ riêng!** Phí $0.30/metric/tháng đã bao gồm lưu trữ.

| Resolution | Retention | Phí lưu trữ |
|------------|-----------|-------------|
| < 60 giây (high-res) | 3 giờ | Miễn phí |
| 1 phút | 15 ngày | Miễn phí |
| 5 phút | 63 ngày | Miễn phí |
| 1 giờ | **15 tháng** | Miễn phí |

> Data tự động aggregate: sau 15 ngày (1 phút → 5 phút), sau 63 ngày (5 phút → 1 giờ)

### So sánh với Logs Storage

| | Metrics | Logs |
|--|---------|------|
| **Phí lưu trữ riêng** | ❌ Không (đã bao gồm) | ✅ $0.03/GB/tháng |
| **Retention** | Tự động 15 tháng | Tự chọn (1 ngày → vĩnh viễn) |
| **Xóa được không** | ❌ Tự expire sau 15 tháng | ✅ Có thể xóa |

### Chi phí với Dimensions

Mỗi unique dimension value = 1 custom metric riêng = thêm $0.30/tháng

| Cấu hình | Số metrics | Chi phí/tháng |
|----------|------------|---------------|
| `ErrorCount` (không dimension) | 1 | $0.30 |
| `ErrorCount` + dimension `Environment` (dev, staging, prod) | 3 | $0.90 |
| `ErrorCount` + dimension `UserId` (1000 users) | 1000 | $300 😱 |

```
Không có dimension:
  ErrorCount = 150  ← 1 metric duy nhất

Có dimension Environment:
  ErrorCount {Environment=dev}     = 50   ← metric #1
  ErrorCount {Environment=staging} = 30   ← metric #2  
  ErrorCount {Environment=prod}    = 70   ← metric #3
```

> **⚠️ Best practice:** Tránh dùng high-cardinality fields (userId, requestId, IP) làm dimensions.

---

## Lưu ý quan trọng

| ⚠️ Lưu ý | Chi tiết |
|----------|----------|
| **Limit 1000 dimensions** | AWS sẽ disable filter nếu tạo quá 1000 unique name/value pairs |
| **Unit không đổi được** | Assign đúng unit ngay từ đầu, thay đổi sau sẽ không có effect |
| **Testing** | Filter pattern preview chỉ show 50 dòng đầu tiên |

---

## Tài liệu tham khảo

| Chủ đề | Link |
|--------|------|
| **Creating metrics from log events** | https://docs.aws.amazon.com/AmazonCloudWatch/latest/logs/MonitoringLogData.html |
| **Filter pattern syntax** | https://docs.aws.amazon.com/AmazonCloudWatch/latest/logs/FilterAndPatternSyntax.html |
| **Creating metric filters examples** | https://docs.aws.amazon.com/AmazonCloudWatch/latest/logs/MonitoringPolicyExamples.html |
| **CloudWatch Alarms** | https://docs.aws.amazon.com/AmazonCloudWatch/latest/monitoring/AlarmThatSendsEmail.html |

---

*Ngày tạo: 2026-01-17*
*Project: realworld-exam*
