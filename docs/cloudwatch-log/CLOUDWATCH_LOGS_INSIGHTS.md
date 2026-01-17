# CloudWatch Logs Insights - Query và Phân tích Logs

## Mục lục
1. [Tổng quan](#tổng-quan)
2. [Query Syntax](#query-syntax)
3. [Commands chi tiết](#commands-chi-tiết)
4. [Ví dụ thực tế](#ví-dụ-thực-tế)
5. [Thử nghiệm với LocalStack](#thử-nghiệm-với-localstack)
6. [Tài liệu tham khảo](#tài-liệu-tham-khảo)

---

## Tổng quan

### Logs Insights là gì?

CloudWatch Logs Insights là công cụ query logs với syntax tương tự SQL. Cho phép tìm kiếm, phân tích và visualize logs nhanh chóng.

```
┌─────────────────────────────────────────────────────────────┐
│  Log Group: /app/my-service/prod                            │
│  ├── Stream 1: [INFO] Order created...                      │
│  ├── Stream 2: [ERROR] Payment failed...                    │
│  └── Stream 3: [INFO] User logged in...                     │
└─────────────────────────────────────────────────────────────┘
                              │
                              ▼
┌─────────────────────────────────────────────────────────────┐
│  Query: filter @message like /ERROR/                        │
│         | stats count(*) by bin(1h)                         │
└─────────────────────────────────────────────────────────────┘
                              │
                              ▼
┌─────────────────────────────────────────────────────────────┐
│  Result: 10:00 - 5 errors                                   │
│          11:00 - 2 errors                                   │
│          12:00 - 8 errors  ← spike detected!                │
└─────────────────────────────────────────────────────────────┘
```

### Các ngôn ngữ query được hỗ trợ

| Ngôn ngữ | Mô tả | Ví dụ |
|----------|-------|-------|
| **Logs Insights QL** | Purpose-built, recommended | `fields @timestamp \| filter @message like /ERROR/` |
| **OpenSearch PPL** | Piped Processing Language | `source = logs \| where message like 'ERROR'` |
| **OpenSearch SQL** | Standard SQL syntax | `SELECT * FROM logs WHERE message LIKE '%ERROR%'` |

**Document này focus vào Logs Insights QL** - ngôn ngữ được AWS recommend.

### Discovered Fields

CloudWatch tự động parse và tạo các fields có prefix `@`:

| Field | Mô tả |
|-------|-------|
| `@timestamp` | Thời gian log event |
| `@message` | Nội dung log |
| `@logStream` | Tên log stream |
| `@log` | Log group ARN |
| `@ptr` | Pointer để retrieve full log |

Với JSON logs, các fields được tự động extract:
```json
{"level": "ERROR", "userId": "123", "action": "payment"}
```
→ Có thể query: `filter level = "ERROR" and userId = "123"`

### Log Formats và CloudWatch

| Format | Ví dụ | CloudWatch parsing |
|--------|-------|-------------------|
| **Plain text** | `2026-01-17 INFO Order created` | Chỉ có `@message`, phải dùng `parse` |
| **JSON** | `{"level":"INFO","msg":"Order"}` | ✅ Tự động tạo fields |
| **Logfmt** | `level=INFO msg="Order"` | Phải dùng `parse` |

**Plain text** (project hiện tại):
```sql
# Phải pattern match
filter @message like /ERROR/

# Hoặc parse để extract
parse @message "* * *  [*] * - *" as date, time, level, thread, logger, msg
| filter level = "ERROR"
```

**JSON structured logs** (recommended cho production):
```sql
# Query trực tiếp fields
filter level = "ERROR" and userId = "123"
```

> 📖 Xem thêm: [LOGGING_BEST_PRACTICES.md](./LOGGING_BEST_PRACTICES.md#log-formats)

---

## Query Syntax

### Cấu trúc cơ bản

```sql
command1 arg1, arg2
| command2 arg1
| command3 arg1, arg2
```

**Quy tắc:**
- Commands nối với nhau bằng pipe `|`
- Comments bắt đầu bằng `#`
- Strings dùng single `'` hoặc double `"` quotes
- Regex đặt trong `/pattern/`

### Ví dụ đơn giản

```sql
# Lấy 25 logs gần nhất
fields @timestamp, @message
| sort @timestamp desc
| limit 25
```

---

## Commands chi tiết

### 1. fields - Chọn fields hiển thị

```sql
# Chọn specific fields
fields @timestamp, @message, @logStream

# Tạo computed field
fields @timestamp, @message, 
       @memorySize / 1024 / 1024 as memoryMB

# Rename field
fields @timestamp as time, @message as log
```

### 2. filter - Lọc logs

```sql
# So sánh đơn giản
filter @message like /ERROR/
filter level = "ERROR"
filter statusCode >= 400

# Multiple conditions (AND)
filter level = "ERROR" and userId = "123"

# OR conditions
filter level = "ERROR" or level = "WARN"

# NOT
filter @message not like /DEBUG/

# Check field exists
filter ispresent(errorCode)

# Regex match
filter @message like /Order.*created/
```

**Comparison operators:**

| Operator | Mô tả |
|----------|-------|
| `=` | Equal |
| `!=` | Not equal |
| `<`, `>`, `<=`, `>=` | So sánh |
| `like` | Pattern match (regex) |
| `not like` | Không match |
| `in` | Trong list |

### 3. stats - Aggregate statistics

```sql
# Count tổng
stats count(*) as total

# Count theo group
stats count(*) by level
stats count(*) by bin(1h)  # Group theo giờ

# Multiple aggregations
stats count(*) as total,
      avg(duration) as avgDuration,
      max(duration) as maxDuration
      by serviceName

# Percentiles
stats pct(duration, 50) as p50,
      pct(duration, 95) as p95,
      pct(duration, 99) as p99
```

**Aggregate functions:**

| Function | Mô tả |
|----------|-------|
| `count(*)` | Đếm số records |
| `sum(field)` | Tổng |
| `avg(field)` | Trung bình |
| `min(field)` | Giá trị nhỏ nhất |
| `max(field)` | Giá trị lớn nhất |
| `pct(field, n)` | Percentile thứ n |

**Time binning:**

| Function | Mô tả |
|----------|-------|
| `bin(1m)` | Group theo phút |
| `bin(5m)` | Group theo 5 phút |
| `bin(1h)` | Group theo giờ |
| `bin(1d)` | Group theo ngày |

### 4. sort - Sắp xếp

```sql
# Descending (mới nhất trước)
sort @timestamp desc

# Ascending
sort @timestamp asc

# Multiple sort
sort level desc, @timestamp desc
```

### 5. limit - Giới hạn kết quả

```sql
# Lấy 100 records
limit 100

# Top 10 errors
filter level = "ERROR"
| sort @timestamp desc
| limit 10
```

### 6. parse - Extract data từ log message

```sql
# Glob pattern (wildcard *)
parse @message "user=* action=* status=*" as user, action, status

# Regex pattern
parse @message /user=(?<user>\w+) action=(?<action>\w+)/

# Ví dụ thực tế
parse @message "Request * completed in * ms" as requestId, duration
| filter duration > 1000
| stats avg(duration) by requestId
```

### 7. dedup - Loại bỏ duplicates

```sql
# Unique values của server field
fields @timestamp, server, message
| sort @timestamp desc
| dedup server

# Unique combination
dedup server, errorCode
```

### 8. display - Chỉ định fields output

```sql
# Chỉ hiện fields cụ thể trong output
fields @timestamp, @message, level, userId
| filter level = "ERROR"
| display @timestamp, userId, @message
```

---

## Ví dụ thực tế

### 1. Debug errors

```sql
# Tìm tất cả errors trong 1 giờ qua
fields @timestamp, @message
| filter @message like /ERROR/ or @message like /Exception/
| sort @timestamp desc
| limit 100
```

### 2. Error count theo thời gian

```sql
# Đếm errors mỗi 5 phút
filter @message like /ERROR/
| stats count(*) as errorCount by bin(5m)
| sort errorCount desc
```

### 3. Top exceptions

```sql
# Top 10 exception types
filter @message like /Exception/
| parse @message "* Exception:" as exceptionType
| stats count(*) as count by exceptionType
| sort count desc
| limit 10
```

### 4. Slow requests

```sql
# Requests > 1 second
parse @message "completed in * ms" as duration
| filter duration > 1000
| stats count(*) as slowRequests,
        avg(duration) as avgDuration,
        max(duration) as maxDuration
        by bin(1h)
```

### 5. User activity

```sql
# Top active users
parse @message "userId=*" as userId
| stats count(*) as actions by userId
| sort actions desc
| limit 10
```

### 6. HTTP status codes

```sql
# Distribution of status codes
parse @message "status=*" as statusCode
| stats count(*) as count by statusCode
| sort count desc
```

### 7. Error rate percentage

```sql
# Error rate per hour
stats sum(strcontains(@message, 'ERROR')) as errors,
      count(*) as total,
      (sum(strcontains(@message, 'ERROR')) / count(*)) * 100 as errorRate
      by bin(1h)
```

### 8. Spring Boot specific

```sql
# Application startup time
filter @message like /Started.*Application/
| parse @message "Started * in * seconds" as appName, startupTime
| display @timestamp, appName, startupTime
```

```sql
# Database connection issues
filter @message like /HikariPool/ or @message like /Connection/
| filter @message like /ERROR/ or @message like /WARN/
| sort @timestamp desc
| limit 50
```

---

## Thử nghiệm với LocalStack

### Prerequisites

```bash
# Start LocalStack
docker-compose up -d

# Verify logs service
docker exec realworld-exam_localstack_1 awslocal logs describe-log-groups
```

### Chạy app để tạo logs

```bash
cd be
./gradlew bootRun
```

### Query với CLI

**Bước 1: Start query**

```bash
# Lấy timestamp hiện tại (milliseconds)
START_TIME=$(($(date +%s) - 3600))000  # 1 giờ trước
END_TIME=$(date +%s)000

# Start query
QUERY_ID=$(docker exec realworld-exam_localstack_1 awslocal logs start-query \
  --log-group-name /app/realworld-example/dev \
  --start-time $START_TIME \
  --end-time $END_TIME \
  --query-string "fields @timestamp, @message | sort @timestamp desc | limit 10" \
  --region ap-southeast-1 \
  --query 'queryId' --output text)

echo "Query ID: $QUERY_ID"
```

**Bước 2: Get results**

```bash
docker exec realworld-exam_localstack_1 awslocal logs get-query-results \
  --query-id $QUERY_ID \
  --region ap-southeast-1
```

### Ví dụ queries với LocalStack

**Query 1: Logs gần nhất**

```bash
docker exec realworld-exam_localstack_1 awslocal logs start-query \
  --log-group-name /app/realworld-example/dev \
  --start-time 1768600000 \
  --end-time 1768700000 \
  --query-string "fields @timestamp, @message | sort @timestamp desc | limit 5" \
  --region ap-southeast-1
```

**Query 2: Filter by level**

```bash
docker exec realworld-exam_localstack_1 awslocal logs start-query \
  --log-group-name /app/realworld-example/dev \
  --start-time 1768600000 \
  --end-time 1768700000 \
  --query-string "filter @message like /INFO/ | stats count(*) as total" \
  --region ap-southeast-1
```

**Query 3: Count by time bin**

```bash
docker exec realworld-exam_localstack_1 awslocal logs start-query \
  --log-group-name /app/realworld-example/dev \
  --start-time 1768600000 \
  --end-time 1768700000 \
  --query-string "stats count(*) by bin(5m)" \
  --region ap-southeast-1
```

### Script helper

Sử dụng script để query dễ hơn:

```bash
# Xem help và ví dụ
./scripts/logs-query.sh

# Chạy query
./scripts/logs-query.sh "fields @timestamp, @message | limit 5"
```

> 📖 Xem thêm: [scripts/LOGS_QUERY_SAMPLES.md](/scripts/LOGS_QUERY_SAMPLES.md) - 15+ query mẫu chi tiết

---

## Best Practices

### 1. Optimize query cost

```sql
# ❌ Bad: Query toàn bộ data
fields *

# ✅ Good: Chỉ lấy fields cần thiết
fields @timestamp, @message
```

### 2. Always use time range

- Luôn chọn time range hẹp nhất có thể
- Query bị tính phí theo lượng data scanned

### 3. Filter early

```sql
# ✅ Good: Filter trước khi aggregate
filter level = "ERROR"
| stats count(*) by bin(1h)

# ❌ Bad: Aggregate rồi mới filter (nếu có thể tránh)
stats count(*) by level
| filter level = "ERROR"
```

### 4. Use parse cho unstructured logs

```sql
# Extract structured data từ log message
parse @message "[*] * - *" as level, logger, message
| filter level = "ERROR"
```

### 5. Save useful queries

Trong AWS Console, có thể save queries để reuse.

---

## Tài liệu tham khảo

| Chủ đề | Link |
|--------|------|
| **Logs Insights Overview** | https://docs.aws.amazon.com/AmazonCloudWatch/latest/logs/AnalyzingLogData.html |
| **Query Syntax** | https://docs.aws.amazon.com/AmazonCloudWatch/latest/logs/CWL_QuerySyntax.html |
| **Sample Queries** | https://docs.aws.amazon.com/AmazonCloudWatch/latest/logs/CWL_QuerySyntax-examples.html |
| **LocalStack Logs** | https://docs.localstack.cloud/aws/services/logs/ |

---

*Ngày tạo: 2026-01-17*  
*Project: realworld-exam*
