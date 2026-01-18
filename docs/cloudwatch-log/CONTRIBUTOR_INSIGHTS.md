# CloudWatch Contributor Insights - Documentation

## Mục lục
1. [Tổng quan](#tổng-quan)
2. [Cách hoạt động](#cách-hoạt-động)
3. [Rule Syntax](#rule-syntax)
4. [Ví dụ thực tế](#ví-dụ-thực-tế)
5. [Use Cases](#use-cases)
6. [Pricing](#pricing)
7. [Tài liệu tham khảo](#tài-liệu-tham-khảo)

---

## Tổng quan

**Contributor Insights** là tính năng phân tích logs để tìm **Top-N contributors** - những "thủ phạm" chính gây ra vấn đề hoặc chiếm nhiều resources nhất.

```
┌─────────────────────────────────────────────────────────────────────────┐
│  Ví dụ: "Ai đang gửi nhiều requests nhất?"                              │
│                                                                          │
│   Logs Input:                          Contributor Insights Output:     │
│   ┌────────────────────────────┐      ┌────────────────────────────┐   │
│   │ IP: 1.2.3.4  GET /api      │      │  Top 5 Contributors:       │   │
│   │ IP: 5.6.7.8  POST /login   │      │                            │   │
│   │ IP: 1.2.3.4  GET /users    │ ───► │  1. 1.2.3.4    45% (900)   │   │
│   │ IP: 1.2.3.4  GET /orders   │      │  2. 9.10.11.12 20% (400)   │   │
│   │ IP: 9.10.11.12 GET /api    │      │  3. 5.6.7.8    15% (300)   │   │
│   │ ...thousands more...       │      │  4. ...                    │   │
│   └────────────────────────────┘      └────────────────────────────┘   │
└─────────────────────────────────────────────────────────────────────────┘
```

### Contributor Insights giải quyết vấn đề gì?

| Vấn đề | Contributor Insights giúp |
|--------|---------------------------|
| Ai đang spam API? | Top IPs by request count |
| URL nào lỗi nhiều nhất? | Top URLs by error count |
| User nào dùng nhiều bandwidth? | Top users by bytes transferred |
| Host nào bị reject connections? | Top hosts by rejected TCP |

### Real-time Processing

Contributor Insights chỉ tính logs **từ lúc rule được enable**, không tính logs cũ:

```
┌─────────────────────────────────────────────────────────────────────────┐
│  Timeline                                                                │
│                                                                          │
│  ──────────────────────────────────────────────────────────────────────► │
│                                                                          │
│  Logs cũ            │  Rule enabled    │  Logs mới                      │
│  (trước 10:00)      │  lúc 10:00       │  (sau 10:00)                   │
│                     │                  │                                 │
│  ❌ Không tính      │                  │  ✅ Được tính                   │
│                     │                  │                                 │
└─────────────────────────────────────────────────────────────────────────┘
```

Khi có log mới đến, rule evaluate và **update counter ngay lập tức** (không đọc lại tất cả logs):

```
10:00:01  Log A arrives ──► Rule evaluate ──► Update counter
10:00:02  Log B arrives ──► Rule evaluate ──► Update counter
10:00:03  Log C arrives ──► Rule evaluate ──► Update counter

Giữ RUNNING STATE trong memory:
┌─────────────────────────────┐
│  IP 1.2.3.4  │  count: 45  │  ◄── Cộng dồn, không đọc lại
│  IP 5.6.7.8  │  count: 23  │
└─────────────────────────────┘
```

> 💡 **Cần analyze logs cũ?** Dùng **Log Insights Query** thay vì Contributor Insights.

### So sánh với Metric Filter

Cả hai đều analyze logs, nhưng **output khác nhau**:

```
┌─────────────────────────────────────────────────────────────────────────┐
│  Cùng logs input: 1000 requests với errors                              │
│                                                                          │
│  ┌─────────────────────────────────┐  ┌─────────────────────────────┐   │
│  │     Metric Filter               │  │   Contributor Insights      │   │
│  │                                 │  │                             │   │
│  │  Output: 1 con số              │  │  Output: Bảng ranking       │   │
│  │                                 │  │                             │   │
│  │  ErrorCount = 500               │  │  IP 1.2.3.4   = 300        │   │
│  │                                 │  │  IP 5.6.7.8   = 120        │   │
│  │  (không biết AI gây ra)        │  │  IP 9.10.11.12 = 80        │   │
│  │                                 │  │                             │   │
│  │  → Chỉ biết CÓ lỗi             │  │  → Biết AI gây lỗi          │   │
│  └─────────────────────────────────┘  └─────────────────────────────┘   │
└─────────────────────────────────────────────────────────────────────────┘
```

| Tính năng | Metric Filter | Contributor Insights |
|-----------|---------------|---------------------|
| **Output** | 1 metric value (số) | Top-N ranking table |
| **Câu hỏi trả lời** | "Có bao nhiêu?" | "Ai/Cái gì gây ra?" |
| **Group by dimension** | ❌ Không | ✅ Có (tối đa 4 keys) |
| **Real-time** | ✅ | ✅ |
| **Tạo Alarm** | ✅ | ✅ |

**Ví dụ:**
- **Metric Filter:** `Pattern: ERROR` → `ErrorCount = 500` (không biết IP nào gây ra)
- **Contributor Insights:** `Keys: ["$.ip"], Filter: ERROR` → Top IPs: `1.2.3.4 = 300, 5.6.7.8 = 120...`

**Tóm lại:** Metric Filter cho biết **có vấn đề**, Contributor Insights cho biết **ai/cái gì gây ra vấn đề**.

### So sánh với Log Insights Query

| Tính năng | Contributor Insights | Log Insights |
|-----------|---------------------|--------------|
| **Mục đích** | Top-N analysis, real-time | Ad-hoc queries |
| **Chạy khi nào** | Liên tục, real-time | Khi user chạy query |
| **Output** | Time series + ranking | Query results |
| **Alarm** | Có thể tạo alarm | Không |
| **Cost** | Per matching log event | Per data scanned |

---

## Cách hoạt động

### Flow xử lý

```
┌─────────────────────────────────────────────────────────────────────────┐
│                                                                          │
│   CloudWatch Logs                                                        │
│   ┌─────────────────────────────────────────────────────────────────┐   │
│   │ Log Group: /app/api-gateway                                      │   │
│   │ ┌─────────────────────────────────────────────────────────────┐ │   │
│   │ │ {"ip":"1.2.3.4","method":"GET","path":"/api","status":200}  │ │   │
│   │ │ {"ip":"5.6.7.8","method":"POST","path":"/login","status":401}│ │   │
│   │ │ {"ip":"1.2.3.4","method":"GET","path":"/users","status":500} │ │   │
│   │ └─────────────────────────────────────────────────────────────┘ │   │
│   └───────────────────────────┬─────────────────────────────────────┘   │
│                               │                                          │
│                               ▼                                          │
│   Contributor Insights Rule                                              │
│   ┌─────────────────────────────────────────────────────────────────┐   │
│   │ Keys: ["$.ip"]                    ◄── Group by IP                │   │
│   │ Filters: [status >= 400]          ◄── Chỉ lấy errors            │   │
│   │ AggregateOn: Count                ◄── Đếm số lần                │   │
│   └───────────────────────────┬─────────────────────────────────────┘   │
│                               │                                          │
│                               ▼                                          │
│   Output: Time Series + Top Contributors                                 │
│   ┌─────────────────────────────────────────────────────────────────┐   │
│   │  Top IPs with errors:                                            │   │
│   │  ┌─────────┬───────────┬─────────┐                              │   │
│   │  │ Rank    │ IP        │ Count   │                              │   │
│   │  ├─────────┼───────────┼─────────┤                              │   │
│   │  │ 1       │ 1.2.3.4   │ 150     │                              │   │
│   │  │ 2       │ 5.6.7.8   │ 89      │                              │   │
│   │  │ 3       │ 9.10.11.12│ 45      │                              │   │
│   │  └─────────┴───────────┴─────────┘                              │   │
│   └─────────────────────────────────────────────────────────────────┘   │
│                                                                          │
└─────────────────────────────────────────────────────────────────────────┘
```

### Real-time Processing

```
Timeline:
─────────────────────────────────────────────────────────────────────────►

10:00    10:01    10:02    10:03    10:04    10:05
  │        │        │        │        │        │
  ▼        ▼        ▼        ▼        ▼        ▼
┌────┐   ┌────┐   ┌────┐   ┌────┐   ┌────┐   ┌────┐
│Log │   │Log │   │Log │   │Log │   │Log │   │Log │
│ A  │   │ B  │   │ C  │   │ D  │   │ E  │   │ F  │
└──┬─┘   └──┬─┘   └──┬─┘   └──┬─┘   └──┬─┘   └──┬─┘
   │        │        │        │        │        │
   └────────┴────────┴────────┴────────┴────────┘
                     │
                     ▼
            Rule evaluates EVERY log event
            as it arrives (real-time)
```

---

## Rule Syntax

### Cấu trúc cơ bản

```json
{
    "Schema": {
        "Name": "CloudWatchLogRule",
        "Version": 1
    },
    "LogGroupNames": [
        "/app/api-gateway*"
    ],
    "LogFormat": "JSON",
    "Contribution": {
        "Keys": ["$.ip"],
        "ValueOf": "$.requestBytes",
        "Filters": [
            {
                "Match": "$.httpMethod",
                "In": ["PUT", "POST"]
            }
        ]
    },
    "AggregateOn": "Sum"
}
```

### Giải thích các field

| Field | Mô tả | Ví dụ |
|-------|-------|-------|
| **Schema** | Luôn cố định | `{"Name": "CloudWatchLogRule", "Version": 1}` |
| **LogGroupNames** | Log groups cần analyze (hỗ trợ wildcard `*`) | `["/app/*", "/api/prod"]` |
| **LogFormat** | Format của logs | `JSON` hoặc `CLF` |
| **Keys** | Fields dùng để group contributors (tối đa 4) | `["$.ip", "$.path"]` |
| **ValueOf** | Field số để tính Sum (optional) | `"$.requestBytes"` |
| **Filters** | Điều kiện lọc logs (tối đa 4) | Filter theo status, method... |
| **AggregateOn** | Cách aggregate | `Count` hoặc `Sum` |

### Filter Operators

| Operator | Mô tả | Ví dụ |
|----------|-------|-------|
| `In` | Giá trị nằm trong list | `{"Match": "$.method", "In": ["GET", "POST"]}` |
| `NotIn` | Giá trị không nằm trong list | `{"Match": "$.status", "NotIn": [200, 201]}` |
| `StartsWith` | Bắt đầu bằng | `{"Match": "$.path", "StartsWith": ["/api"]}` |
| `EqualTo` | Bằng (số) | `{"Match": "$.status", "EqualTo": 500}` |
| `GreaterThan` | Lớn hơn | `{"Match": "$.bytes", "GreaterThan": 10000}` |
| `LessThan` | Nhỏ hơn | `{"Match": "$.latency", "LessThan": 100}` |
| `IsPresent` | Field có tồn tại | `{"Match": "$.error", "IsPresent": true}` |

### JSON Property Notation

```
$.fieldName                  → Top-level field
$.nested.field              → Nested field
$.users[0].name             → Array element
$.requestParameters.instanceId → Deep nested
```

---

## Ví dụ thực tế

### 1. Top IPs gây lỗi 5xx

```json
{
    "Schema": {"Name": "CloudWatchLogRule", "Version": 1},
    "LogGroupNames": ["/app/api-gateway"],
    "LogFormat": "JSON",
    "Contribution": {
        "Keys": ["$.ip"],
        "Filters": [
            {"Match": "$.status", "GreaterThan": 499}
        ]
    },
    "AggregateOn": "Count"
}
```

**Output:**
```
┌──────┬─────────────┬───────┐
│ Rank │ IP          │ Count │
├──────┼─────────────┼───────┤
│ 1    │ 1.2.3.4     │ 250   │  ◄── Suspicious! Có thể là attacker
│ 2    │ 5.6.7.8     │ 45    │
│ 3    │ 9.10.11.12  │ 12    │
└──────┴─────────────┴───────┘
```

### 2. Top URLs by bytes transferred

```json
{
    "Schema": {"Name": "CloudWatchLogRule", "Version": 1},
    "LogGroupNames": ["/app/api-gateway"],
    "LogFormat": "JSON",
    "Contribution": {
        "Keys": ["$.path"],
        "ValueOf": "$.responseBytes"
    },
    "AggregateOn": "Sum"
}
```

**Output:**
```
┌──────┬─────────────────┬──────────────┐
│ Rank │ Path            │ Total Bytes  │
├──────┼─────────────────┼──────────────┤
│ 1    │ /api/export     │ 50 GB        │  ◄── Cần optimize!
│ 2    │ /api/images     │ 12 GB        │
│ 3    │ /api/reports    │ 5 GB         │
└──────┴─────────────────┴──────────────┘
```

### 3. VPC Flow Logs - Rejected TCP connections

```json
{
    "Schema": {"Name": "CloudWatchLogRule", "Version": 1},
    "LogGroupNames": ["/aws/vpc/flowlogs"],
    "LogFormat": "CLF",
    "Fields": {
        "3": "interfaceID",
        "4": "sourceAddress",
        "8": "protocol",
        "13": "action"
    },
    "Contribution": {
        "Keys": ["interfaceID", "sourceAddress"],
        "Filters": [
            {"Match": "protocol", "EqualTo": 6},
            {"Match": "action", "In": ["REJECT"]}
        ]
    },
    "AggregateOn": "Count"
}
```

### 4. Top users by request count

```json
{
    "Schema": {"Name": "CloudWatchLogRule", "Version": 1},
    "LogGroupNames": ["/app/production"],
    "LogFormat": "JSON",
    "Contribution": {
        "Keys": ["$.userId", "$.endpoint"],
        "Filters": []
    },
    "AggregateOn": "Count"
}
```

---

## Use Cases

### 1. Security - Detect DDoS/Abuse

**Scenario:** Phát hiện IPs đang spam requests hoặc gây ra nhiều errors.

**Real-world example:** E-commerce website bị slow. Dùng Contributor Insights rule tìm top IPs by request count, phát hiện 1 IP chiếm 60% traffic → Block IP đó hoặc apply rate limiting.

### 2. Performance - Find Slow Endpoints

**Scenario:** Tìm APIs có latency cao nhất.

**Real-world example:** Banking app có complaints về performance. Rule với Keys=`["$.endpoint"]`, ValueOf=`"$.latency"`, AggregateOn=`Sum` → Phát hiện `/api/transactions` chiếm 80% total latency → Focus optimize endpoint này.

### 3. Cost Optimization - Identify Heavy Users

**Scenario:** Tìm users/tenants sử dụng nhiều resources nhất.

**Real-world example:** SaaS platform với multi-tenant. Rule tìm top tenants by bytes transferred → Phát hiện 1 tenant sử dụng 50% bandwidth → Upsell họ lên higher tier hoặc apply fair usage policy.

### 4. Debugging - Error Analysis

**Scenario:** Tìm root cause của errors.

**Real-world example:** Spike trong error rate. Rule với Keys=`["$.errorType", "$.endpoint"]` → Phát hiện `NullPointerException` chỉ xảy ra ở `/api/checkout` → Focus debug endpoint đó.

---

## Pricing

```
┌─────────────────────────────────────────────────────────────────────────┐
│  Contributor Insights Pricing                                            │
│                                                                          │
│  Charged per: LOG EVENT that MATCHES a rule                             │
│                                                                          │
│  Ví dụ:                                                                  │
│  - Log Group có 1 triệu events/ngày                                     │
│  - Rule filter chỉ match 100,000 events (status >= 400)                 │
│  - Chỉ tính tiền cho 100,000 events đó                                  │
│                                                                          │
│  ⚠️ Cẩn thận với wildcard trong LogGroupNames!                          │
│  LogGroupNames: ["/app/*"] có thể match nhiều log groups hơn dự định    │
└─────────────────────────────────────────────────────────────────────────┘
```

### Tips giảm cost

| Tip | Mô tả |
|-----|-------|
| **Filters chặt** | Dùng Filters để giảm số events match |
| **Specific log groups** | Tránh wildcard nếu có thể |
| **Disable khi không cần** | Rule có thể enable/disable |

---

## Kết hợp với Alarms

Contributor Insights có thể tạo CloudWatch Metrics, từ đó tạo Alarms:

```
┌─────────────────────────────────────────────────────────────────────────┐
│                                                                          │
│   Contributor Insights Rule                                              │
│   (Top IPs by error count)                                              │
│              │                                                           │
│              ▼                                                           │
│   Metrics: INSIGHT_RULE_METRIC(ruleName, metricName)                    │
│   - UniqueContributors                                                   │
│   - MaxContributorValue                                                  │
│   - Sum                                                                  │
│              │                                                           │
│              ▼                                                           │
│   CloudWatch Alarm                                                       │
│   "Alert if MaxContributorValue > 100 in 5 minutes"                     │
│              │                                                           │
│              ▼                                                           │
│   SNS → Slack/PagerDuty                                                 │
│                                                                          │
└─────────────────────────────────────────────────────────────────────────┘
```

---

## Tài liệu tham khảo

| Chủ đề | Link |
|--------|------|
| **Contributor Insights Overview** | https://docs.aws.amazon.com/AmazonCloudWatch/latest/monitoring/ContributorInsights.html |
| **Rule Syntax** | https://docs.aws.amazon.com/AmazonCloudWatch/latest/monitoring/ContributorInsights-RuleSyntax.html |
| **Rule Examples** | https://docs.aws.amazon.com/AmazonCloudWatch/latest/monitoring/ContributorInsights-Rule-Examples.html |
| **Viewing Reports** | https://docs.aws.amazon.com/AmazonCloudWatch/latest/monitoring/ContributorInsights-ViewReports.html |
| **Graphing Metrics** | https://docs.aws.amazon.com/AmazonCloudWatch/latest/monitoring/ContributorInsights-GraphReportData.html |
| **Pricing** | https://aws.amazon.com/cloudwatch/pricing/ |

---

*Ngày tạo: 2026-01-18*
*Project: realworld-exam*
