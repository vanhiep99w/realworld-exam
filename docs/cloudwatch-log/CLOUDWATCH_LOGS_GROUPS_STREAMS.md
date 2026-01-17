# CloudWatch Logs - Log Groups & Streams + Push Logs từ Application

## Mục lục
1. [Tổng quan](#tổng-quan)
2. [Khái niệm](#khái-niệm)
3. [Implementation](#implementation)
4. [Thử nghiệm với LocalStack](#thử-nghiệm-với-localstack)
5. [Tài liệu tham khảo](#tài-liệu-tham-khảo)

---

## Tổng quan

Log Groups và Log Streams là cấu trúc tổ chức cơ bản của CloudWatch Logs. Kết hợp với Logback Appender, ứng dụng Spring Boot có thể tự động push logs lên CloudWatch thông qua `@Slf4j`.

```
Code: log.info("Order created")
         ↓
Logback: nhận log event
         ↓
CloudWatch Appender: batch & push
         ↓
CloudWatch Logs
├── Log Group: /app/realworld-example/dev
│   └── Log Stream: hostname/2026-01-17
│       └── "2026-01-17 10:07:39 INFO Order created"
```

---

## Khái niệm

### Cấu trúc phân cấp

```
CloudWatch Logs
└── Log Group: /app/order-service/prod     ← Container (theo app + env)
    ├── Log Stream: pod-abc123             ← Source 1 (container/instance)
    │   ├── [timestamp] INFO Order created
    │   └── [timestamp] ERROR Payment failed
    ├── Log Stream: pod-def456             ← Source 2
    └── Log Stream: pod-ghi789             ← Source 3
```

### 3 thành phần chính

| Thành phần | Là gì | Ví dụ |
|------------|-------|-------|
| **Log Group** | Container chứa logs, định nghĩa retention + access control | `/app/order-service/prod` |
| **Log Stream** | Sequence log events từ 1 source | `pod-abc123`, `hostname-123456` |
| **Log Event** | 1 dòng log = timestamp + message | `{timestamp: 123, message: "INFO..."}` |

### Log Group quyết định gì?

| Setting | Mô tả |
|---------|-------|
| **Retention** | Giữ logs bao lâu (7 days, 30 days, 1 year...) |
| **Access Control** | IAM permissions - ai được xem/ghi |
| **Metric Filters** | Pattern nào tạo metric |
| **Subscription** | Stream đi đâu (Lambda/Kinesis/S3) |

### Naming rules

Log Group và Log Stream name **hoàn toàn do bạn tự đặt**, chỉ cần tuân thủ:

| | Quy tắc |
|---|---|
| **Log Group** | 1-512 ký tự, cho phép: `a-zA-Z0-9`, `_`, `-`, `/`, `.`, `#` |
| **Log Stream** | 1-512 ký tự, cho phép: `a-zA-Z0-9`, `_`, `-`, `/`, `.` |

### Naming convention (recommended)

```
/aws/lambda/function-name        ← Lambda tự tạo
/aws/ecs/cluster-name            ← ECS logs  
/app/{service}/{environment}     ← Application logs (recommended)
```

---

## Implementation

### 1. Dependencies

```groovy
// build.gradle
implementation 'software.amazon.awssdk:cloudwatchlogs:2.29.51'
```

### 2. Custom Logback Appender

Tạo appender hỗ trợ LocalStack endpoint:

```
be/src/main/java/com/seft/learn/example/logging/CloudWatchLogsAppender.java
```

**Tính năng chính:**
- Async queue (10,000 events buffer)
- Batch push (50 events mỗi 5 giây)
- Tự động tạo Log Group/Stream
- Hỗ trợ custom endpoint (LocalStack)

### 3. Logback Configuration

```xml
<!-- logback-spring.xml -->
<springProfile name="cloudwatch">
    <appender name="CLOUDWATCH" class="com.seft.learn.example.logging.CloudWatchLogsAppender">
        <endpoint>${CLOUDWATCH_ENDPOINT:-http://localhost:4566}</endpoint>
        <region>${AWS_REGION:-ap-southeast-1}</region>
        <accessKey>${AWS_ACCESS_KEY:-test}</accessKey>
        <secretKey>${AWS_SECRET_KEY:-test}</secretKey>
        <logGroupName>${CLOUDWATCH_LOG_GROUP:-/app/realworld-example/dev}</logGroupName>
        <filter class="ch.qos.logback.classic.filter.ThresholdFilter">
            <level>INFO</level>
        </filter>
    </appender>

    <root level="INFO">
        <appender-ref ref="CONSOLE"/>
        <appender-ref ref="CLOUDWATCH"/>
    </root>
</springProfile>
```

### 4. Sử dụng trong code

```java
@Slf4j
@RestController
public class OrderController {
    
    @PostMapping("/orders")
    public Order createOrder(@RequestBody OrderRequest request) {
        log.info("Creating order for user: {}", request.getUserId());
        // ... business logic
        log.info("Order created: {}", order.getId());
        return order;
    }
}
```

Logs tự động push lên CloudWatch khi chạy với profile `cloudwatch`.

---

## Thử nghiệm với LocalStack

### Prerequisites

```yaml
# docker-compose.yml
services:
  localstack:
    image: localstack/localstack-pro:latest
    environment:
      - SERVICES=s3,logs,cloudwatch
      - AWS_DEFAULT_REGION=ap-southeast-1
```

### Chạy ứng dụng

```bash
# Start LocalStack
docker-compose up -d

# Chạy app với cloudwatch profile
./gradlew bootRun --args='--spring.profiles.active=cloudwatch'
```

### Verify logs

```bash
# List log groups
docker exec realworld-exam_localstack_1 awslocal logs describe-log-groups \
  --region ap-southeast-1

# List streams
docker exec realworld-exam_localstack_1 awslocal logs describe-log-streams \
  --log-group-name /app/realworld-example/dev \
  --region ap-southeast-1

# Get log events
docker exec realworld-exam_localstack_1 awslocal logs get-log-events \
  --log-group-name /app/realworld-example/dev \
  --log-stream-name <stream-name> \
  --region ap-southeast-1 \
  --limit 10
```

### Manual test (không cần app)

```bash
# Tạo log group
docker exec realworld-exam_localstack_1 awslocal logs create-log-group \
  --log-group-name /app/test \
  --region ap-southeast-1

# Tạo log stream
docker exec realworld-exam_localstack_1 awslocal logs create-log-stream \
  --log-group-name /app/test \
  --log-stream-name instance-001 \
  --region ap-southeast-1

# Push log event
docker exec realworld-exam_localstack_1 awslocal logs put-log-events \
  --log-group-name /app/test \
  --log-stream-name instance-001 \
  --log-events "timestamp=$(date +%s)000,message=INFO Test message" \
  --region ap-southeast-1
```

---

## Lưu ý quan trọng

### Thư viện có sẵn vs Custom Appender

| Thư viện | LocalStack | Production AWS |
|----------|------------|----------------|
| `logback-awslogs-appender` | ❌ Không hỗ trợ custom endpoint | ✅ |
| `j256/cloudwatch-logback-appender` | ❌ Không hỗ trợ custom endpoint | ✅ |
| **Custom Appender** (trong project) | ✅ | ✅ |

→ Dùng custom appender để test với LocalStack

### Log Stream naming

Mỗi instance/container cần stream riêng. Pattern phổ biến trong production:

| Môi trường | Pattern | Ví dụ |
|------------|---------|-------|
| **ECS/Fargate** | `{container-name}/{container-id}` | `app/abc123def456` |
| **Kubernetes** | `{namespace}/{pod-name}` | `prod/api-7f8d9c-xyz` |
| **EC2** | `{instance-id}` | `i-0abc123def456` |
| **General** | `{hostname}/{date}` | `api-server-1/2026-01-17` |

**Project này sử dụng pattern `{hostname}/{date}`:**
```
hieptran-IdeaPad/2026-01-17
```

**Lý do chọn pattern này:**
- 1 stream per instance per day → dễ tìm logs theo ngày
- Instance restart không tạo stream mới (trong cùng ngày)
- Tự động rotation theo ngày

### Performance considerations

- Queue size: 10,000 events (tránh mất logs khi burst)
- Batch size: 50 events
- Flush interval: 5 giây
- Non-blocking: logging thread không bị block

---

## Files trong project

| File | Mô tả |
|------|-------|
| `logging/CloudWatchLogsAppender.java` | Custom Logback appender |
| `config/CloudWatchLogsConfig.java` | CloudWatchLogsClient bean |
| `service/CloudWatchLogsService.java` | Service để push logs thủ công |
| `logback-spring.xml` | Logback configuration |
| `application-cloudwatch.yml` | Profile config (H2 database) |

---

## Tài liệu tham khảo

| Chủ đề | Link |
|--------|------|
| **CloudWatch Logs Concepts** | https://docs.aws.amazon.com/AmazonCloudWatch/latest/logs/CloudWatchLogsConcepts.html |
| **Working with Log Groups** | https://docs.aws.amazon.com/AmazonCloudWatch/latest/logs/Working-with-log-groups-and-streams.html |
| **LocalStack CloudWatch Logs** | https://docs.localstack.cloud/aws/services/logs/ |
| **PutLogEvents API** | https://docs.aws.amazon.com/AmazonCloudWatchLogs/latest/APIReference/API_PutLogEvents.html |

---

*Ngày tạo: 2026-01-17*  
*Project: realworld-exam*
