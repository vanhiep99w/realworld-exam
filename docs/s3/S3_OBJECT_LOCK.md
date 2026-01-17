# S3 Object Lock

Ngăn objects bị xóa/ghi đè bằng WORM (Write Once Read Many) model → compliance & data protection.

---

## Overview

**Mục đích:** Đảm bảo data immutability ở storage level, đáp ứng compliance requirements (SEC 17a-4, CFTC, FINRA).

```
┌─────────────────────────────────────────────────────────────────┐
│  Object Lock vs. Versioning                                     │
├─────────────────────────────────────────────────────────────────┤
│                                                                 │
│  Versioning alone:                                              │
│  ┌──────┐    DELETE    ┌──────────────┐                        │
│  │ file │ ───────────▶ │ Delete Marker│  ← Vẫn xóa được!       │
│  └──────┘              └──────────────┘                        │
│                                                                 │
│  Object Lock + Versioning:                                      │
│  ┌──────┐    DELETE    ┌──────┐                                │
│  │ file │ ───────────▶ │ 403  │  ← Access Denied!              │
│  │ 🔒   │              │      │                                │
│  └──────┘              └──────┘                                │
│                                                                 │
└─────────────────────────────────────────────────────────────────┘
```

**Yêu cầu:** Versioning phải được bật trước khi dùng Object Lock.

---

## Hai cách quản lý Object Lock

Object Lock có 2 mechanisms độc lập để bảo vệ objects:

```
┌─────────────────────────────────────────────────────────────────┐
│                     OBJECT LOCK MECHANISMS                      │
├─────────────────────────────────────────────────────────────────┤
│                                                                 │
│  ┌─────────────────────┐      ┌─────────────────────┐          │
│  │  RETENTION PERIOD   │      │     LEGAL HOLD      │          │
│  ├─────────────────────┤      ├─────────────────────┤          │
│  │ • Có thời hạn       │      │ • Không thời hạn    │          │
│  │ • Set days/years    │      │ • On/Off toggle     │          │
│  │ • Tự hết hạn        │      │ • Phải gỡ manually  │          │
│  │ • 2 modes:          │      │ • Dùng cho lawsuits │          │
│  │   - Compliance      │      │                     │          │
│  │   - Governance      │      │                     │          │
│  └─────────────────────┘      └─────────────────────┘          │
│                                                                 │
│  → Object có thể có cả 2 cùng lúc!                             │
└─────────────────────────────────────────────────────────────────┘
```

---

## Retention Modes

### Compliance Mode 🔐

**Nghiêm ngặt nhất:** Không ai có thể xóa/ghi đè, kể cả root user.

```
┌─────────────────────────────────────────────────────────────────┐
│  COMPLIANCE MODE                                                │
├─────────────────────────────────────────────────────────────────┤
│                                                                 │
│  ┌──────────────────────────────────────────┐                  │
│  │ audit-log-2026.csv                       │                  │
│  │ Retention: COMPLIANCE                    │                  │
│  │ Retain Until: 2033-01-01                 │                  │
│  └──────────────────────────────────────────┘                  │
│                                                                 │
│  Admin: DELETE?        → 403 Access Denied                     │
│  Root User: DELETE?    → 403 Access Denied                     │
│  Shorten retention?    → 403 Access Denied                     │
│  Extend retention?     → ✅ Allowed                            │
│                                                                 │
│  ⚠️  Cách duy nhất xóa trước hạn: XÓA AWS ACCOUNT!            │
└─────────────────────────────────────────────────────────────────┘
```

**Use cases:** Financial records (SEC 17a-4), healthcare records (HIPAA), legal documents.

### Governance Mode 🔓

**Linh hoạt hơn:** Users đặc biệt có thể bypass nếu cần.

```
┌─────────────────────────────────────────────────────────────────┐
│  GOVERNANCE MODE                                                │
├─────────────────────────────────────────────────────────────────┤
│                                                                 │
│  ┌──────────────────────────────────────────┐                  │
│  │ quarterly-report.pdf                     │                  │
│  │ Retention: GOVERNANCE                    │                  │
│  │ Retain Until: 2027-04-01                 │                  │
│  └──────────────────────────────────────────┘                  │
│                                                                 │
│  Normal User: DELETE?  → 403 Access Denied                     │
│  Admin with bypass:    → ✅ Allowed (cần header đặc biệt)      │
│                                                                 │
│  Permission cần: s3:BypassGovernanceRetention                  │
│  Header cần:     x-amz-bypass-governance-retention: true       │
└─────────────────────────────────────────────────────────────────┘
```

**Use cases:** Test retention trước khi dùng Compliance, protect từ accidental delete nhưng vẫn cho admin quyền xóa khi cần.

### So sánh Modes

| Feature | Compliance | Governance |
|---------|------------|------------|
| **Protection level** | Absolute | Bypassable |
| **Root user can delete?** | ❌ No | ✅ With permission |
| **Shorten retention?** | ❌ Never | ✅ With permission |
| **Extend retention?** | ✅ Yes | ✅ Yes |
| **Use case** | Legal compliance | Accidental deletion protection |

---

## Legal Hold

Giữ object vô thời hạn cho đến khi tắt. Độc lập với retention period.

```
┌─────────────────────────────────────────────────────────────────┐
│  LEGAL HOLD SCENARIO                                            │
├─────────────────────────────────────────────────────────────────┤
│                                                                 │
│  Lawsuit bắt đầu:                                               │
│  ┌──────────────────┐    PUT Legal Hold    ┌──────────────────┐│
│  │ contract.pdf     │ ──────────────────▶  │ contract.pdf     ││
│  │ Retention: 30d   │                      │ Retention: 30d   ││
│  │ Legal Hold: OFF  │                      │ Legal Hold: ON 🔒││
│  └──────────────────┘                      └──────────────────┘│
│                                                                 │
│  30 ngày sau (retention hết hạn):                              │
│  ┌──────────────────┐                                          │
│  │ contract.pdf     │  ← Vẫn protected vì Legal Hold ON!      │
│  │ Legal Hold: ON 🔒│                                          │
│  └──────────────────┘                                          │
│                                                                 │
│  Lawsuit kết thúc → Gỡ Legal Hold → Object có thể xóa được     │
└─────────────────────────────────────────────────────────────────┘
```

**Permission cần:** `s3:PutObjectLegalHold`

---

## Delete Behavior với Object Lock

```
┌─────────────────────────────────────────────────────────────────┐
│  DELETE REQUEST TYPES                                           │
├─────────────────────────────────────────────────────────────────┤
│                                                                 │
│  Simple DELETE (không có version-id):                           │
│  DELETE /file.txt                                               │
│  → 200 OK + tạo Delete Marker (như Versioning bình thường)      │
│  → Object versions vẫn còn, vẫn được protect                   │
│                                                                 │
│  Permanent DELETE (có version-id):                              │
│  DELETE /file.txt?versionId=abc123                              │
│  → 403 Access Denied (nếu version đang locked)                 │
│                                                                 │
└─────────────────────────────────────────────────────────────────┘
```

---

## Thiết lập Object Lock

### Enable khi tạo bucket mới

```bash
aws s3api create-bucket \
  --bucket my-compliance-bucket \
  --object-lock-enabled-for-bucket
```

### Enable trên bucket có sẵn

```bash
aws s3api put-object-lock-configuration \
  --bucket my-bucket \
  --object-lock-configuration '{ 
    "ObjectLockEnabled": "Enabled", 
    "Rule": { 
      "DefaultRetention": { 
        "Mode": "GOVERNANCE", 
        "Days": 90 
      }
    }
  }'
```

### Set retention trên từng object

```bash
aws s3api put-object-retention \
  --bucket my-bucket \
  --key audit-log.csv \
  --retention '{ 
    "Mode": "COMPLIANCE", 
    "RetainUntilDate": "2030-12-31T00:00:00Z" 
  }'
```

### Set Legal Hold

```bash
# Bật Legal Hold
aws s3api put-object-legal-hold \
  --bucket my-bucket \
  --key contract.pdf \
  --legal-hold '{ "Status": "ON" }'

# Tắt Legal Hold
aws s3api put-object-legal-hold \
  --bucket my-bucket \
  --key contract.pdf \
  --legal-hold '{ "Status": "OFF" }'
```

---

## Required Permissions

| Action | Permission |
|--------|------------|
| Get bucket lock config | `s3:GetBucketObjectLockConfiguration` |
| Set bucket lock config | `s3:PutBucketObjectLockConfiguration` |
| Get object retention | `s3:GetObjectRetention` |
| Set object retention | `s3:PutObjectRetention` |
| Get legal hold | `s3:GetObjectLegalHold` |
| Set legal hold | `s3:PutObjectLegalHold` |
| Bypass governance mode | `s3:BypassGovernanceRetention` |

---

## Lưu ý quan trọng

| ⚠️ Warning | Chi tiết |
|-----------|----------|
| **Không thể tắt** | Một khi bật Object Lock, không thể disable hoặc suspend |
| **Cần Versioning** | Versioning bắt buộc và cũng không thể suspend sau khi bật Object Lock |
| **Không dùng cho server logs** | Bucket có Object Lock không thể làm destination cho server access logs |
| **Compliance mode = permanent** | Không có cách nào xóa trước hạn ngoại trừ xóa AWS account |
| **Per-version** | Lock áp dụng cho từng version, không phải cả object |

---

## Use Cases

| Scenario | Recommended Setup |
|----------|-------------------|
| **Financial compliance (SEC 17a-4)** | Compliance mode, 7 years retention |
| **Healthcare records (HIPAA)** | Compliance mode, 6 years retention |
| **Legal document hold** | Governance mode + Legal Hold khi cần |
| **Protect từ ransomware** | Governance mode, 30-90 days retention |
| **Audit logs** | Compliance mode, retention theo policy |

---

## Official Documentation

| Topic | Link |
|-------|------|
| **Object Lock Overview** | https://docs.aws.amazon.com/AmazonS3/latest/userguide/object-lock.html |
| **Configuring Object Lock** | https://docs.aws.amazon.com/AmazonS3/latest/userguide/object-lock-configure.html |
| **Managing Object Lock** | https://docs.aws.amazon.com/AmazonS3/latest/userguide/object-lock-managing.html |
| **SEC 17a-4 Compliance Assessment** | https://d1.awsstatic.com/r2018/b/S3-Object-Lock/Amazon-S3-Compliance-Assessment.pdf |

---

*Ngày tạo: 2026-01-16*
*Project: realworld-exam*
