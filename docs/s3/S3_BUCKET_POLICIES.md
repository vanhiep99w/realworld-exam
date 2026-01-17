# S3 Bucket Policies

JSON document định nghĩa ai được làm gì với bucket/objects.

---

## Overview

**Bucket Policy:** "Luật" của bucket - định nghĩa permissions.

```
┌─────────────────────────────────────────────────────────────────┐
│  Bucket Policy = "Luật" của bucket                              │
├─────────────────────────────────────────────────────────────────┤
│                                                                 │
│  "Principal X được phép Action Y trên Resource Z"               │
│                                                                 │
│  Ví dụ:                                                         │
│  - "User A được GET tất cả objects"                             │
│  - "Chỉ IP 1.2.3.4 được PUT"                                    │
│  - "Account 123456 được access folder exports/"                 │
│                                                                 │
└─────────────────────────────────────────────────────────────────┘
```

---

## Cấu trúc Policy

```json
{
  "Version": "2012-10-17",
  "Statement": [
    {
      "Sid": "UniqueStatementId",
      "Effect": "Allow",
      "Principal": "*",
      "Action": "s3:GetObject",
      "Resource": "arn:aws:s3:::my-bucket/*",
      "Condition": { ... }
    }
  ]
}
```

| Field | Ý nghĩa | Ví dụ |
|-------|---------|-------|
| **Sid** | ID của statement (optional) | `"AllowPublicRead"` |
| **Effect** | Allow hoặc Deny | `"Allow"`, `"Deny"` |
| **Principal** | Ai? | `"*"`, `"arn:aws:iam::123:user/bob"` |
| **Action** | Làm gì? | `"s3:GetObject"`, `"s3:PutObject"` |
| **Resource** | Trên resource nào? | `"arn:aws:s3:::bucket/*"` |
| **Condition** | Điều kiện (optional) | IP, VPC, HTTPS... |

---

## So sánh IAM Policy vs Bucket Policy

| | IAM Policy | Bucket Policy |
|---|------------|---------------|
| **Gắn vào** | User/Role | Bucket |
| **Scope** | Tất cả resources user access | Chỉ bucket đó |
| **Cross-account** | Phức tạp | Dễ dàng |
| **Use case** | "User này được làm gì" | "Bucket này ai được access" |

```
┌──────────────────────────────────────────────────────────────┐
│  IAM Policy: "User A được access bucket X, Y, Z"             │
│              (gắn vào User A)                                │
├──────────────────────────────────────────────────────────────┤
│  Bucket Policy: "Bucket X cho phép User A, B, C access"      │
│                 (gắn vào Bucket X)                           │
└──────────────────────────────────────────────────────────────┘
```

---

## Common Actions

| Action | Ý nghĩa |
|--------|---------|
| `s3:GetObject` | Download object |
| `s3:PutObject` | Upload object |
| `s3:DeleteObject` | Xóa object |
| `s3:ListBucket` | List objects trong bucket |
| `s3:GetBucketLocation` | Xem region của bucket |
| `s3:*` | Tất cả actions |

---

## Use Case 1: Restrict by IP Address

Chỉ cho phép access từ IP office hoặc VPN:

```json
{
  "Version": "2012-10-17",
  "Statement": [
    {
      "Sid": "DenyNonOfficeIP",
      "Effect": "Deny",
      "Principal": "*",
      "Action": "s3:*",
      "Resource": [
        "arn:aws:s3:::my-bucket",
        "arn:aws:s3:::my-bucket/*"
      ],
      "Condition": {
        "NotIpAddress": {
          "aws:SourceIp": ["1.2.3.4/32", "10.0.0.0/8"]
        }
      }
    }
  ]
}
```

---

## Use Case 2: Cross-Account Access

Cho phép AWS account khác đọc bucket:

```json
{
  "Version": "2012-10-17",
  "Statement": [
    {
      "Sid": "AllowCrossAccountRead",
      "Effect": "Allow",
      "Principal": {
        "AWS": "arn:aws:iam::123456789012:root"
      },
      "Action": ["s3:GetObject", "s3:ListBucket"],
      "Resource": [
        "arn:aws:s3:::my-bucket",
        "arn:aws:s3:::my-bucket/*"
      ]
    }
  ]
}
```

---

## Use Case 3: Public Read (Static Website)

Chỉ folder `public/` được public access:

```json
{
  "Version": "2012-10-17",
  "Statement": [
    {
      "Sid": "PublicRead",
      "Effect": "Allow",
      "Principal": "*",
      "Action": "s3:GetObject",
      "Resource": "arn:aws:s3:::my-bucket/public/*"
    }
  ]
}
```

---

## Use Case 4: Enforce HTTPS Only

Block tất cả HTTP requests:

```json
{
  "Version": "2012-10-17",
  "Statement": [
    {
      "Sid": "DenyHTTP",
      "Effect": "Deny",
      "Principal": "*",
      "Action": "s3:*",
      "Resource": "arn:aws:s3:::my-bucket/*",
      "Condition": {
        "Bool": {
          "aws:SecureTransport": "false"
        }
      }
    }
  ]
}
```

---

## Use Case 5: Restrict by VPC Endpoint

Chỉ cho phép access từ VPC endpoint (private network):

```json
{
  "Version": "2012-10-17",
  "Statement": [
    {
      "Sid": "AllowOnlyFromVPC",
      "Effect": "Deny",
      "Principal": "*",
      "Action": "s3:*",
      "Resource": "arn:aws:s3:::my-bucket/*",
      "Condition": {
        "StringNotEquals": {
          "aws:SourceVpce": "vpce-1234567890abcdef0"
        }
      }
    }
  ]
}
```

---

## Use Case 6: Folder-level Permissions

Mỗi user chỉ access được folder của mình:

```json
{
  "Version": "2012-10-17",
  "Statement": [
    {
      "Sid": "AllowUserFolder",
      "Effect": "Allow",
      "Principal": {
        "AWS": "arn:aws:iam::123456789012:user/alice"
      },
      "Action": ["s3:GetObject", "s3:PutObject"],
      "Resource": "arn:aws:s3:::my-bucket/users/alice/*"
    }
  ]
}
```

---

## Policy Evaluation Logic

```
┌─────────────────────────────────────────────────────────────────┐
│  Request đến S3                                                 │
│         │                                                       │
│         ▼                                                       │
│  ┌─────────────┐                                               │
│  │ Explicit    │──── YES ───▶ ❌ DENY                          │
│  │ DENY?       │                                               │
│  └─────────────┘                                               │
│         │ NO                                                    │
│         ▼                                                       │
│  ┌─────────────┐                                               │
│  │ Explicit    │──── YES ───▶ ✅ ALLOW                         │
│  │ ALLOW?      │                                               │
│  └─────────────┘                                               │
│         │ NO                                                    │
│         ▼                                                       │
│  ❌ Implicit DENY (default)                                    │
│                                                                 │
└─────────────────────────────────────────────────────────────────┘
```

**Rule quan trọng:**
1. Mặc định là DENY (implicit deny)
2. Explicit ALLOW có thể override implicit deny
3. **Explicit DENY luôn thắng ALLOW**

---

## Common Conditions

| Condition Key | Ý nghĩa | Ví dụ |
|---------------|---------|-------|
| `aws:SourceIp` | IP address | `"1.2.3.4/32"` |
| `aws:SourceVpce` | VPC Endpoint ID | `"vpce-xxx"` |
| `aws:SecureTransport` | HTTPS hay HTTP | `"true"` / `"false"` |
| `s3:prefix` | Object key prefix | `"logs/"` |
| `aws:PrincipalOrgID` | Organization ID | `"o-xxx"` |

---

## AWS CLI Commands

### Apply Bucket Policy

```bash
# Tạo file policy.json rồi apply
aws s3api put-bucket-policy \
  --bucket my-bucket \
  --policy file://policy.json
```

### Xem Bucket Policy

```bash
aws s3api get-bucket-policy --bucket my-bucket
```

### Xóa Bucket Policy

```bash
aws s3api delete-bucket-policy --bucket my-bucket
```

### LocalStack

```bash
aws --endpoint-url=http://localhost:4566 s3api put-bucket-policy \
  --bucket my-bucket \
  --policy file://policy.json
```

---

## Best Practices

| Practice | Lý do |
|----------|-------|
| Dùng Deny thay vì chỉ Allow | Defense in depth |
| Specific Resource ARN | Không dùng `*` khi có thể |
| Least privilege | Chỉ grant permissions cần thiết |
| Use Conditions | Thêm layer security (IP, VPC, HTTPS) |
| Version control policies | Track changes qua Git |

---

## Debugging

Khi access bị denied:

1. **Check IAM Policy** của user/role
2. **Check Bucket Policy** của bucket
3. **Check S3 Block Public Access** settings
4. Dùng **IAM Policy Simulator** để test

```bash
# List bucket policy
aws s3api get-bucket-policy --bucket my-bucket --output text | jq .

# Check block public access
aws s3api get-public-access-block --bucket my-bucket
```

---

## Official Documentation

- [Bucket Policies](https://docs.aws.amazon.com/AmazonS3/latest/userguide/bucket-policies.html)
- [Policy Examples](https://docs.aws.amazon.com/AmazonS3/latest/userguide/example-bucket-policies.html)
- [IAM Policy Simulator](https://policysim.aws.amazon.com/)
- [S3 Actions](https://docs.aws.amazon.com/AmazonS3/latest/API/API_Operations.html)

---

*Document created: 2026-01-15*
*Project: realworld-exam*
