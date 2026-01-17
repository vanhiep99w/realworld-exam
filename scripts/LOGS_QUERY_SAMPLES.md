# CloudWatch Logs Insights - Sample Queries

Hướng dẫn sử dụng script `logs-query.sh` và các query mẫu.

## Cách sử dụng

```bash
# Syntax
./scripts/logs-query.sh "QUERY" [LOG_GROUP] [START_TIME] [END_TIME]

# Mặc định
# - LOG_GROUP: /app/realworld-example/dev
# - START_TIME: 1 giờ trước
# - END_TIME: hiện tại
```

## Ví dụ cơ bản

```bash
# Query đơn giản
./scripts/logs-query.sh "fields @timestamp, @message | limit 5"

# Custom log group
./scripts/logs-query.sh "fields @timestamp, @message | limit 5" "/app/other-service/prod"

# Custom time range (epoch milliseconds)
./scripts/logs-query.sh "fields @timestamp, @message | limit 5" "/app/realworld-example/dev" "1768600000000" "1768700000000"
```

---

## Sample Queries

### 1. Basic - Xem logs gần nhất

```bash
./scripts/logs-query.sh "fields @timestamp, @message | sort @timestamp desc | limit 20"
```

### 2. Filter - Tìm errors

```bash
./scripts/logs-query.sh "filter @message like /ERROR/ | sort @timestamp desc | limit 50"
```

### 3. Filter - Tìm exceptions

```bash
./scripts/logs-query.sh "filter @message like /Exception/ | sort @timestamp desc | limit 20"
```

### 4. Filter - Multiple conditions

```bash
./scripts/logs-query.sh "filter @message like /ERROR/ or @message like /WARN/ | sort @timestamp desc | limit 30"
```

### 5. Stats - Đếm logs theo level

```bash
./scripts/logs-query.sh "parse @message '* * *  [*] *' as date, time, level, thread, rest | stats count(*) by level"
```

### 6. Stats - Đếm logs theo thời gian

```bash
./scripts/logs-query.sh "stats count(*) by bin(5m)"
```

### 7. Stats - Error count per hour

```bash
./scripts/logs-query.sh "filter @message like /ERROR/ | stats count(*) as errors by bin(1h)"
```

### 8. Parse - Extract và filter

```bash
./scripts/logs-query.sh "parse @message '* * *  [*] * - *' as date, time, level, thread, logger, msg | filter level like /ERROR/ | display @timestamp, level, msg"
```

### 9. Spring Boot - Startup time

```bash
./scripts/logs-query.sh "filter @message like /Started.*Application/ | parse @message 'Started * in * seconds' as app, startupTime | display @timestamp, app, startupTime"
```

### 10. Spring Boot - Database issues

```bash
./scripts/logs-query.sh "filter @message like /HikariPool/ or @message like /Connection/ | sort @timestamp desc | limit 20"
```

### 11. Tomcat - Request logs

```bash
./scripts/logs-query.sh "filter @message like /TomcatWebServer/ or @message like /DispatcherServlet/ | sort @timestamp desc | limit 20"
```

### 12. Dedup - Unique messages

```bash
./scripts/logs-query.sh "parse @message '* * *  [*] * - *' as date, time, level, thread, logger, msg | dedup msg | display msg"
```

### 13. Stats - Top loggers

```bash
./scripts/logs-query.sh "parse @message '* * *  [*] *' as date, time, level, thread, logger | stats count(*) as count by logger | sort count desc | limit 10"
```

### 14. Filter - Specific class

```bash
./scripts/logs-query.sh "filter @message like /ExampleApplication/ | sort @timestamp desc | limit 20"
```

### 15. Stats - Logs per stream

```bash
./scripts/logs-query.sh "stats count(*) by @logStream"
```

---

## JSON Logs Queries (khi dùng structured logging)

Nếu logs là JSON format:
```json
{"timestamp":"2026-01-17T10:25:18","level":"INFO","message":"Order created","userId":"123"}
```

### Filter by level

```bash
./scripts/logs-query.sh "filter level = 'ERROR' | sort @timestamp desc | limit 20"
```

### Filter by userId

```bash
./scripts/logs-query.sh "filter userId = '123' | sort @timestamp desc"
```

### Stats by level

```bash
./scripts/logs-query.sh "stats count(*) by level"
```

### Filter multiple fields

```bash
./scripts/logs-query.sh "filter level = 'ERROR' and userId = '123' | sort @timestamp desc"
```

---

## Tips

### 1. Escape quotes trong shell

```bash
# Single quotes bên ngoài, double quotes bên trong
./scripts/logs-query.sh 'filter @message like /ERROR/ | stats count(*)'

# Hoặc escape
./scripts/logs-query.sh "filter @message like /ERROR/ | stats count(*)"
```

### 2. Complex queries - dùng file

```bash
# Lưu query vào file
echo 'parse @message "* * *  [*] * - *" as date, time, level, thread, logger, msg
| filter level like /ERROR/
| stats count(*) by logger
| sort count desc
| limit 10' > /tmp/query.txt

# Chạy từ file
./scripts/logs-query.sh "$(cat /tmp/query.txt)"
```

### 3. Watch logs (polling)

```bash
# Chạy mỗi 10 giây
watch -n 10 './scripts/logs-query.sh "filter @message like /ERROR/ | stats count(*)" 2>/dev/null | jq -r ".results"'
```

---

## Quick Reference

| Command | Mô tả |
|---------|-------|
| `fields` | Chọn fields |
| `filter` | Lọc logs |
| `stats` | Aggregate |
| `sort` | Sắp xếp |
| `limit` | Giới hạn |
| `parse` | Extract từ message |
| `dedup` | Loại duplicate |
| `display` | Chọn output fields |

| Operator | Ví dụ |
|----------|-------|
| `like /pattern/` | Regex match |
| `=`, `!=` | Equal |
| `<`, `>`, `<=`, `>=` | So sánh |
| `and`, `or` | Logic |
| `in` | Trong list |

| Time bin | Ví dụ |
|----------|-------|
| `bin(1m)` | Per minute |
| `bin(5m)` | Per 5 minutes |
| `bin(1h)` | Per hour |
| `bin(1d)` | Per day |
