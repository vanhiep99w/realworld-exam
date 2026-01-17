# Logging Best Practices

## Mục lục
1. [Tổng quan](#tổng-quan)
2. [Log Formats](#log-formats)
3. [Naming Convention](#naming-convention)
4. [Môi trường và CloudWatch](#môi-trường-và-cloudwatch)
5. [Console Logs vs CloudWatch](#console-logs-vs-cloudwatch)
6. [Logging Levels](#logging-levels)
7. [Disk Space và Log Rotation](#disk-space-và-log-rotation)

---

## Tổng quan

Document này tổng hợp các best practices về logging trong production environments, bao gồm naming conventions, khi nào bật CloudWatch, cách quản lý log levels và disk space.

---

## Log Formats

### Các format phổ biến

| Format | Ví dụ | CloudWatch parsing |
|--------|-------|-------------------|
| **Plain text** | `2026-01-17 INFO Order created` | Chỉ có `@message`, phải dùng `parse` |
| **JSON** | `{"level":"INFO","msg":"Order created"}` | ✅ Tự động tạo fields |
| **Logfmt** | `level=INFO msg="Order created"` | Phải dùng `parse` |
| **CSV** | `2026-01-17,INFO,Order created` | Phải dùng `parse` |

### Plain text (default)

```
2026-01-17 10:25:18.294 INFO  [main] com.app.Service - Order created
```

**Query trong CloudWatch:**
```sql
# Phải pattern match trong @message
filter @message like /ERROR/

# Hoặc parse để extract fields
parse @message "* * *  [*] * - *" as date, time, level, thread, logger, msg
| filter level = "ERROR"
```

### JSON Structured Logging (recommended)

```json
{"timestamp":"2026-01-17T10:25:18","level":"INFO","logger":"com.app.Service","message":"Order created","userId":"123"}
```

**Query trong CloudWatch:**
```sql
# CloudWatch tự động tạo fields → query trực tiếp
filter level = "ERROR" and userId = "123"
```

### So sánh

| | Plain Text | JSON |
|---|---|---|
| **Setup** | Mặc định, không cần config | Cần config encoder |
| **Đọc log thủ công** | ✅ Dễ đọc | ❌ Khó đọc |
| **Query CloudWatch** | Phải dùng `parse` | ✅ Query trực tiếp fields |
| **Thêm context** | Khó | ✅ Dễ (thêm fields) |
| **Tương thích tools** | Chỉ text search | ✅ ELK, Datadog, Splunk |

### Recommendation

| Môi trường | Format | Lý do |
|------------|--------|-------|
| **Local/Dev** | Plain text | Dễ đọc trong console |
| **Production** | JSON | Query dễ, thêm context, tương thích monitoring tools |

---

## Naming Convention

### Log Group & Log Stream - Tự do đặt tên

Log Group và Log Stream name **hoàn toàn do bạn tự đặt**, chỉ cần tuân thủ:

| | Quy tắc |
|---|---|
| **Log Group** | 1-512 ký tự, cho phép: `a-zA-Z0-9`, `_`, `-`, `/`, `.`, `#` |
| **Log Stream** | 1-512 ký tự, cho phép: `a-zA-Z0-9`, `_`, `-`, `/`, `.` |

### Log Group Naming Convention

```
/aws/lambda/function-name        ← Lambda tự tạo
/aws/ecs/cluster-name            ← ECS logs  
/app/{service}/{environment}     ← Application logs (recommended)
```

### Log Stream Naming Patterns

Mỗi instance/container cần stream riêng. Pattern phổ biến trong production:

| Môi trường | Pattern | Ví dụ |
|------------|---------|-------|
| **ECS/Fargate** | `{container-name}/{container-id}` | `app/abc123def456` |
| **Kubernetes** | `{namespace}/{pod-name}` | `prod/api-7f8d9c-xyz` |
| **EC2** | `{instance-id}` | `i-0abc123def456` |
| **General** | `{hostname}/{date}` | `api-server-1/2026-01-17` |

**Lưu ý quan trọng:**
- Tránh dùng `timestamp (milliseconds)` → mỗi restart tạo stream mới
- Dùng `date` thay vì `timestamp` → 1 stream/instance/day, tự động rotation

---

## Môi trường và CloudWatch

### Khi nào bật CloudWatch?

| Môi trường | CloudWatch | Lý do |
|------------|------------|-------|
| **Local/Dev** | ❌ Không | Console đủ, không phụ thuộc infrastructure |
| **Staging/UAT** | ✅ Có | Test tương tự production |
| **Production** | ✅ Có | Centralized logs, monitoring, alerting |

### Configuration theo môi trường

```xml
<!-- logback-spring.xml -->

<!-- Production/Staging: Console + CloudWatch -->
<springProfile name="prod,staging">
    <root level="INFO">
        <appender-ref ref="CONSOLE"/>
        <appender-ref ref="CLOUDWATCH"/>
    </root>
</springProfile>

<!-- Development: Console only -->
<springProfile name="dev,local">
    <root level="DEBUG">
        <appender-ref ref="CONSOLE"/>
    </root>
</springProfile>
```

---

## Console Logs vs CloudWatch

### Tại sao giữ cả hai?

```
Application
    │
    ├──► Console (stdout) ──► Container runtime capture
    │                         kubectl logs, docker logs
    │
    └──► CloudWatch ────────► Centralized, query, alert
```

**Không nên bỏ console logs** ở UAT/Production vì:

| Console logs dùng để | |
|---------------------|---|
| `kubectl logs pod-name` | Debug ngay lập tức |
| `docker logs container` | Không cần vào CloudWatch |
| Container runtime capture | Fluent Bit/Fluentd có thể forward |
| Backup khi CloudWatch lỗi | Không mất logs |

**Kết luận:** Giữ cả 2, logs đi song song. Chỉ bỏ console nếu có lý do đặc biệt (logs quá nhiều, ảnh hưởng performance).

---

## Logging Levels

### Log Level Hierarchy

```
TRACE < DEBUG < INFO < WARN < ERROR
```

Khi set level, sẽ hiện **level đó và tất cả levels cao hơn**:

| Set level | Hiện | Ẩn |
|-----------|------|-----|
| `TRACE` | TRACE, DEBUG, INFO, WARN, ERROR | (không ẩn gì) |
| `DEBUG` | DEBUG, INFO, WARN, ERROR | TRACE |
| `INFO` | INFO, WARN, ERROR | DEBUG, TRACE |
| `WARN` | WARN, ERROR | INFO, DEBUG, TRACE |
| `ERROR` | ERROR | Tất cả còn lại |

### Level theo môi trường

| Môi trường | Level | Lý do |
|------------|-------|-------|
| **Local/Dev** | `DEBUG` | Debug chi tiết, xem SQL queries, request/response |
| **UAT/Staging** | `DEBUG` hoặc `INFO` | Test tương tự prod, nhưng cần debug khi có issue |
| **Production** | `INFO` | Cân bằng giữa visibility và performance/cost |

### Khi nào dùng level nào?

| Level | Khi nào dùng | Ví dụ |
|-------|--------------|-------|
| `ERROR` | Lỗi cần xử lý ngay | Payment failed, DB connection lost |
| `WARN` | Có vấn đề nhưng app vẫn chạy | Retry thành công, deprecated API |
| `INFO` | Business events quan trọng | Order created, User login |
| `DEBUG` | Chi tiết technical | SQL queries, method params |
| `TRACE` | Rất chi tiết | Loop iterations, byte-level data |

### Dynamic Log Level trong Production

Mặc định `INFO`, nhưng có thể **thay đổi runtime** khi cần debug:

```bash
# Spring Actuator - thay đổi level không cần restart
curl -X POST http://localhost:8080/actuator/loggers/com.myapp \
  -H "Content-Type: application/json" \
  -d '{"configuredLevel": "DEBUG"}'
```

---

## Disk Space và Log Rotation

### Console logs có làm đầy disk?

Phụ thuộc cách deploy:

| Môi trường | Console logs | Đầy disk? |
|------------|--------------|-----------|
| **Docker/K8s** | stdout → container runtime | ❌ Có log rotation tự động |
| **ECS/Fargate** | awslogs driver forward | ❌ Không lưu local |
| **VM + `java -jar > app.log`** | File không rotation | ⚠️ **Có thể đầy** |
| **VM + systemd** | journald capture | ❌ Có size limit |

### Docker Log Rotation (mặc định)

```json
{
  "log-driver": "json-file",
  "log-opts": {
    "max-size": "10m",
    "max-file": "3"
  }
}
```

### Kubernetes Log Rotation

Kubelet tự động rotate logs, cấu hình trong:
- `containerLogMaxSize`: 10Mi (mặc định)
- `containerLogMaxFiles`: 5 (mặc định)

### VM/EC2 - Cần cấu hình thủ công

Nếu chạy trực tiếp trên VM, dùng `logrotate`:

```bash
# /etc/logrotate.d/myapp
/var/log/myapp/*.log {
    daily
    rotate 7
    compress
    missingok
    notifempty
}
```

**Kết luận:** Container environments tự handle. Chỉ lo nếu chạy trực tiếp trên VM mà redirect stdout vào file không có rotation.

---

## Tài liệu tham khảo

| Chủ đề | Link |
|--------|------|
| **Logback Configuration** | https://logback.qos.ch/manual/configuration.html |
| **Spring Boot Logging** | https://docs.spring.io/spring-boot/docs/current/reference/html/features.html#features.logging |
| **Docker Logging Drivers** | https://docs.docker.com/config/containers/logging/configure/ |
| **Kubernetes Logging** | https://kubernetes.io/docs/concepts/cluster-administration/logging/ |

---

*Ngày tạo: 2026-01-17*  
*Project: realworld-exam*
