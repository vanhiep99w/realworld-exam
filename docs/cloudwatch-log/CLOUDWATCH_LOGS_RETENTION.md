# CloudWatch Logs Retention Policies

## Mục lục
1. [Tổng quan](#tổng-quan)
2. [Khái niệm](#khái-niệm)
3. [Use Cases](#use-cases)
4. [Cách cấu hình](#cách-cấu-hình)
5. [Tài liệu tham khảo](#tài-liệu-tham-khảo)

---

## Tổng quan

Retention Policy cho phép tự động xóa logs sau X ngày, giúp kiểm soát chi phí lưu trữ.

```
┌─────────────────────────────────────────────────────────────┐
│  Log Group: /app/my-service/production                      │
│  Retention: 30 days                                         │
│                                                             │
│  ┌─────────────┐  ┌─────────────┐  ┌─────────────┐          │
│  │ Day 1-29    │  │ Day 30      │  │ Day 31+     │          │
│  │ ✅ Giữ lại  │  │ ⏳ Đánh dấu │  │ 🗑️ Tự động  │          │
│  │             │  │   xóa       │  │   xóa       │          │
│  └─────────────┘  └─────────────┘  └─────────────┘          │
└─────────────────────────────────────────────────────────────┘
```

### Các giá trị retention hợp lệ

| Nhóm | Giá trị (ngày) |
|------|----------------|
| **Ngắn hạn** | 1, 3, 5, 7, 14 |
| **Trung hạn** | 30, 60, 90, 120, 150, 180 |
| **Dài hạn** | 365, 400, 545, 731 (2 năm) |
| **Rất dài** | 1096 (3 năm), 1827 (5 năm), 2192, 2557, 2922, 3288, 3653 (10 năm) |
| **Vĩnh viễn** | Không set (default) = Never expire |

---

## Khái niệm

### Cách thức hoạt động

```
Log Event quá hạn
       │
       ▼
┌──────────────────┐
│ Đánh dấu xóa     │  ← Không tính vào storedBytes
│ (marked)         │  ← Không tính chi phí
└────────┬─────────┘
         │
         ▼ (lên đến 72 giờ sau)
┌──────────────────┐
│ Xóa vĩnh viễn    │
└──────────────────┘
```

**Lưu ý quan trọng:**
- Logs không bị xóa ngay khi hết hạn
- Thường mất **đến 72 giờ** sau ngày hết hạn để xóa thực sự
- Logs đã đánh dấu xóa **không tính phí** lưu trữ

### So sánh với các giải pháp khác

| Tính năng | CloudWatch Retention | S3 Lifecycle | ELK ILM |
|-----------|---------------------|--------------|---------|
| **Cấu hình** | Đơn giản (1 số) | Phức tạp hơn | Phức tạp |
| **Đơn vị** | Log Group | Bucket/Prefix | Index |
| **Chi phí** | $0.03/GB/tháng | $0.023/GB/tháng | Tự quản lý |
| **Tích hợp** | Native AWS | Manual export | Riêng biệt |

---

## Use Cases

### 1. Development/Test Logs
**Scenario:** Logs môi trường dev không cần giữ lâu

**Cấu hình:** 7-14 ngày

```
/app/my-service/dev     → 7 days
/app/my-service/staging → 14 days
```

### 2. Production Application Logs
**Scenario:** Cần giữ đủ lâu để debug incidents

**Cấu hình:** 30-90 ngày

```
/app/my-service/production → 30 days
/app/critical-service/prod → 90 days
```

### 3. Audit/Compliance Logs
**Scenario:** Yêu cầu pháp lý giữ logs dài hạn

**Cấu hình:** 365-3653 ngày (1-10 năm)

```
/audit/user-actions    → 365 days (PCI-DSS)
/audit/financial       → 2557 days (7 năm - SOX)
```

### 4. Cost Optimization Strategy

| Môi trường | Retention | Chi phí ước tính (100GB/tháng) |
|------------|-----------|-------------------------------|
| Dev | 7 ngày | ~$0.70/tháng |
| Staging | 14 ngày | ~$1.40/tháng |
| Production | 30 ngày | ~$3.00/tháng |
| Audit | 365 ngày | ~$36.00/tháng |

---

## Cách cấu hình

### AWS Console

1. Mở CloudWatch → Log groups
2. Chọn log group
3. Actions → Edit retention setting
4. Chọn số ngày hoặc "Never expire"

### AWS CLI

```bash
# Set retention 30 ngày
aws logs put-retention-policy \
    --log-group-name /app/my-service/production \
    --retention-in-days 30

# Xóa retention policy (never expire)
aws logs delete-retention-policy \
    --log-group-name /app/my-service/production

# Xem retention hiện tại
aws logs describe-log-groups \
    --log-group-name-prefix /app/my-service
```

### AWS SDK (Java)

```java
CloudWatchLogsClient client = CloudWatchLogsClient.builder()
    .region(Region.AP_SOUTHEAST_1)
    .build();

// Set retention policy
client.putRetentionPolicy(PutRetentionPolicyRequest.builder()
    .logGroupName("/app/my-service/production")
    .retentionInDays(30)
    .build());
```

### LocalStack (Testing)

```bash
# Start LocalStack
docker-compose up -d

# Create log group với retention
awslocal logs create-log-group \
    --log-group-name /app/test

awslocal logs put-retention-policy \
    --log-group-name /app/test \
    --retention-in-days 7

# Verify
awslocal logs describe-log-groups
```

### Terraform/CloudFormation

```yaml
# CloudFormation
Resources:
  MyLogGroup:
    Type: AWS::Logs::LogGroup
    Properties:
      LogGroupName: /app/my-service/production
      RetentionInDays: 30
```

```hcl
# Terraform
resource "aws_cloudwatch_log_group" "app_logs" {
  name              = "/app/my-service/production"
  retention_in_days = 30
}
```

---

## Best Practices

### 1. Đặt retention policy ngay khi tạo log group
Tránh logs tích lũy không kiểm soát.

### 2. Phân loại theo môi trường
```
/app/{service}/{environment}
    dev     → 7 days
    staging → 14 days  
    prod    → 30-90 days
```

### 3. Export trước khi xóa (nếu cần)
Dùng Export to S3 cho logs cần archive lâu hơn với chi phí thấp hơn.

### 4. Lưu ý khi thay đổi retention
Nếu **tăng** retention sau khi logs đã quá hạn nhưng chưa xóa:
- Logs vẫn có thể bị xóa trong 72 giờ tiếp theo
- Chờ 72 giờ sau khi hết hạn cũ trước khi tăng retention

---

## Tài liệu tham khảo

| Chủ đề | Link |
|--------|------|
| **PutRetentionPolicy API** | https://docs.aws.amazon.com/AmazonCloudWatchLogs/latest/APIReference/API_PutRetentionPolicy.html |
| **CloudWatch Logs Pricing** | https://aws.amazon.com/cloudwatch/pricing/ |
| **Log Group Settings** | https://docs.aws.amazon.com/AmazonCloudWatch/latest/logs/Working-with-log-groups-and-streams.html |

---

*Ngày tạo: 2026-01-17*
*Project: realworld-exam*
