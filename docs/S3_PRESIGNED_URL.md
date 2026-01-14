# AWS S3 Presigned URL - Documentation

## Mục lục
1. [Tổng quan](#tổng-quan)
2. [Presigned PUT vs POST](#presigned-put-vs-post)
3. [Security & Validation](#security--validation)
4. [Use Cases](#use-cases)
5. [Implementation Examples](#implementation-examples)
6. [Official Documentation](#official-documentation)

---

## Tổng quan

**Presigned URL** là URL được ký (signed) với AWS credentials, cho phép client truy cập S3 mà không cần AWS credentials.

```
┌─────────────┐         ┌─────────────┐         ┌─────────────┐
│   Client    │         │   Backend   │         │     S3      │
└──────┬──────┘         └──────┬──────┘         └──────┬──────┘
       │                       │                       │
       │ 1. Request upload URL │                       │
       │──────────────────────>│                       │
       │                       │                       │
       │ 2. Generate presigned │                       │
       │    URL with signature │                       │
       │<──────────────────────│                       │
       │                       │                       │
       │ 3. Upload directly to S3 (no backend involved)│
       │───────────────────────────────────────────────>│
       │                       │                       │
       │ 4. S3 verifies signature & accepts/rejects    │
       │<───────────────────────────────────────────────│
```

### Các operations hỗ trợ Presigned URL

| Operation | Method | Use Case |
|-----------|--------|----------|
| `GetObject` | GET | Download file |
| `PutObject` | PUT | Upload file (simple) |
| `POST` (form-based) | POST | Upload với policy conditions |
| `DeleteObject` | DELETE | Xóa file |
| `HeadObject` | HEAD | Check file exists |
| `CreateMultipartUpload` | POST | Large file upload |
| `UploadPart` | PUT | Upload từng part |

---

## Presigned PUT vs POST

### Presigned PUT

```
URL: https://bucket.s3.amazonaws.com/key?X-Amz-Algorithm=...&X-Amz-Signature=...
```

**Đặc điểm:**
- URL chứa tất cả authentication params
- Đơn giản, dễ implement
- Chỉ validate **exact match** (không có range)

**Signed Headers có thể bao gồm:**

| Header | Mô tả |
|--------|-------|
| `Content-Type` | MIME type - phải match exact |
| `Content-Length` | Size bytes - phải match exact |
| `Content-MD5` | MD5 hash để verify integrity |
| `x-amz-meta-*` | Custom metadata |
| `x-amz-storage-class` | STANDARD, GLACIER, etc. |
| `x-amz-server-side-encryption` | Encryption settings |
| `x-amz-acl` | Access control |

**Ví dụ URL:**
```
https://demo-bucket.s3.ap-southeast-1.amazonaws.com/uploads/file.png
  ?X-Amz-Algorithm=AWS4-HMAC-SHA256
  &X-Amz-Credential=AKIAIOSFODNN7EXAMPLE/20260113/ap-southeast-1/s3/aws4_request
  &X-Amz-Date=20260113T150000Z
  &X-Amz-Expires=900
  &X-Amz-SignedHeaders=content-length;content-type;host
  &X-Amz-Signature=abc123...
```

### Presigned POST (Form-based)

**Đặc điểm:**
- Dùng HTML form hoặc FormData
- Có **Policy** với conditions linh hoạt
- Hỗ trợ **range validation** (min-max size)
- Hỗ trợ **starts-with** prefix matching

**Policy Conditions:**

| Condition | Ví dụ | Mô tả |
|-----------|-------|-------|
| Exact match | `{"bucket": "my-bucket"}` | Giá trị phải exact |
| Starts-with | `["starts-with", "$key", "uploads/"]` | Key phải bắt đầu bằng prefix |
| Content-length-range | `["content-length-range", 1, 10485760]` | Size trong range 1B - 10MB |
| Content-type | `{"Content-Type": "image/png"}` | Exact MIME type |

**Ví dụ Policy:**
```json
{
  "expiration": "2026-01-13T16:00:00.000Z",
  "conditions": [
    {"bucket": "demo-bucket"},
    ["starts-with", "$key", "uploads/"],
    {"Content-Type": "image/png"},
    ["content-length-range", 1, 10485760],
    {"x-amz-credential": "AKIAIOSFODNN7EXAMPLE/20260113/ap-southeast-1/s3/aws4_request"},
    {"x-amz-algorithm": "AWS4-HMAC-SHA256"},
    {"x-amz-date": "20260113T150000Z"}
  ]
}
```

**FormData phải bao gồm (theo thứ tự):**
```javascript
const formData = new FormData();
formData.append('key', 'uploads/my-file.png');
formData.append('Content-Type', 'image/png');
formData.append('X-Amz-Credential', '...');
formData.append('X-Amz-Algorithm', 'AWS4-HMAC-SHA256');
formData.append('X-Amz-Date', '20260113T150000Z');
formData.append('Policy', 'base64-encoded-policy');
formData.append('X-Amz-Signature', 'calculated-signature');
formData.append('file', file);  // ⚠️ PHẢI LÀ FIELD CUỐI CÙNG
```

### So sánh PUT vs POST

| Feature | PUT | POST |
|---------|-----|------|
| Độ phức tạp | Đơn giản | Phức tạp hơn |
| URL format | Query params | Form fields |
| Exact match validation | ✅ | ✅ |
| Range validation (size) | ❌ | ✅ `content-length-range` |
| Prefix matching | ❌ | ✅ `starts-with` |
| Browser redirect after upload | ❌ | ✅ `success_action_redirect` |
| Multiple files | ❌ | ❌ (1 file per request) |

---

## Security & Validation

### Signature Verification Flow

```
┌─────────────────────────────────────────────────────────────┐
│  1. Backend tạo Policy + Signature bằng SECRET_KEY         │
│                                                             │
│     Policy (JSON) ──► Base64 ──► HMAC-SHA256 ──► Signature  │
│                         │              ▲                    │
│                         │              │                    │
│                         │        SECRET_KEY                 │
│                         │        (chỉ backend có)           │
│                         ▼                                   │
│  2. Gửi cho Client: {url, fields: {Policy, Signature, ...}} │
└─────────────────────────────────────────────────────────────┘
                              │
                              ▼
┌─────────────────────────────────────────────────────────────┐
│  3. Client gửi lên S3 với fields                            │
│                                                             │
│  4. S3 có SECRET_KEY ──► Tính lại Signature                 │
│                              │                              │
│                              ▼                              │
│     Signature match?  ──► YES ──► Check Policy conditions   │
│           │                              │                  │
│           NO                             ▼                  │
│           │                    Conditions met? ──► Accept   │
│           ▼                         │                       │
│     AccessDenied                    NO                      │
│                                     │                       │
│                                     ▼                       │
│                              AccessDenied / EntityTooLarge  │
└─────────────────────────────────────────────────────────────┘
```

### Client có thể sửa fields không?

**Có thể thấy và sửa**, nhưng **không thể bypass**:

| Hành động | Kết quả |
|-----------|---------|
| Sửa `key` khác prefix | ❌ `AccessDenied` - violates `starts-with` |
| Sửa `Content-Type` | ❌ `AccessDenied` - không match policy |
| Upload file > max size | ❌ `EntityTooLarge` |
| Upload file < min size | ❌ `EntityTooSmall` |
| Sửa `Policy` | ❌ `SignatureDoesNotMatch` |
| Sửa `X-Amz-Signature` | ❌ `SignatureDoesNotMatch` |

### URL có thể reuse không?

**Có**, cho đến khi hết hạn:

```
Presigned URL (expires in 15 mins)

✅ Upload lần 1  → Success (file created)
✅ Upload lần 2  → Success (file OVERWRITTEN)
✅ Upload lần 3  → Success (file OVERWRITTEN)
❌ Upload sau 15 mins → AccessDenied (expired)
```

**Best practices:**
- Dùng unique key (thêm UUID/timestamp)
- Set expiry ngắn (5-15 phút)
- Track usage ở backend nếu cần single-use

### Validation Layers

```
┌─────────────────────────────────────────────────────────────┐
│  Layer 1: Frontend Validation (UX only - có thể bypass)    │
│  - Check file size trước khi upload                        │
│  - Check file type                                         │
│  - Show error message nhanh                                │
└─────────────────────────────────────────────────────────────┘
                              │
                              ▼
┌─────────────────────────────────────────────────────────────┐
│  Layer 2: Backend Validation (trước khi generate URL)      │
│  - Validate content-type trong whitelist                   │
│  - Validate file size trong limit                          │
│  - Return 400 Bad Request nếu không hợp lệ                 │
└─────────────────────────────────────────────────────────────┘
                              │
                              ▼
┌─────────────────────────────────────────────────────────────┐
│  Layer 3: S3 Validation (enforced by AWS)                  │
│  - Verify signature                                        │
│  - Check policy conditions                                 │
│  - Check expiration                                        │
│  - Reject nếu không match                                  │
└─────────────────────────────────────────────────────────────┘
```

---

## Use Cases

### 1. Direct Upload từ Browser
Tránh upload qua backend, giảm bandwidth và latency.

### 2. Mobile App Upload
App lấy presigned URL từ API, upload trực tiếp lên S3.

### 3. Temporary Download Links
Chia sẻ file private với expiry time (1h, 24h, 7 days max).

### 4. Large File Upload (Multipart)
Files > 5GB cần multipart upload với nhiều presigned URLs.

### 5. Secure File Sharing
Tạo link download tạm thời cho users không có AWS credentials.

---

## Implementation Examples

### Backend (Spring Boot + AWS SDK v2)

```java
// Presigned PUT URL
public String generatePresignedPutUrl(String key, String contentType, long fileSize) {
    PutObjectRequest putRequest = PutObjectRequest.builder()
            .bucket(bucketName)
            .key(key)
            .contentType(contentType)
            .contentLength(fileSize)
            .build();

    PutObjectPresignRequest presignRequest = PutObjectPresignRequest.builder()
            .signatureDuration(Duration.ofMinutes(15))
            .putObjectRequest(putRequest)
            .build();

    return s3Presigner.presignPutObject(presignRequest).url().toString();
}

// Presigned GET URL
public String generatePresignedGetUrl(String key) {
    GetObjectRequest getRequest = GetObjectRequest.builder()
            .bucket(bucketName)
            .key(key)
            .build();

    GetObjectPresignRequest presignRequest = GetObjectPresignRequest.builder()
            .signatureDuration(Duration.ofMinutes(15))
            .getObjectRequest(getRequest)
            .build();

    return s3Presigner.presignGetObject(presignRequest).url().toString();
}
```

### Frontend (TypeScript)

```typescript
// Upload với PUT
async function uploadWithPut(presignedUrl: string, file: File) {
  await fetch(presignedUrl, {
    method: 'PUT',
    body: file,
    headers: { 'Content-Type': file.type }
  });
}

// Upload với POST
async function uploadWithPost(url: string, fields: Record<string, string>, file: File) {
  const formData = new FormData();
  Object.entries(fields).forEach(([k, v]) => formData.append(k, v));
  formData.append('file', file);  // File must be last!
  
  await fetch(url, { method: 'POST', body: formData });
}

// Download
async function download(key: string) {
  const { url } = await getPresignedGetUrl(key);
  window.open(url, '_blank');
}
```

---

## Official Documentation

### AWS Documentation

| Topic | Link |
|-------|------|
| **Presigned URLs Overview** | https://docs.aws.amazon.com/AmazonS3/latest/userguide/using-presigned-url.html |
| **Presigned URL Upload** | https://docs.aws.amazon.com/AmazonS3/latest/userguide/PresignedUrlUploadObject.html |
| **POST Policy (Browser Upload)** | https://docs.aws.amazon.com/AmazonS3/latest/API/sigv4-post-example.html |
| **POST Policy Conditions** | https://docs.aws.amazon.com/AmazonS3/latest/API/sigv4-HTTPPOSTConstructPolicy.html |
| **Signature V4 (Query String)** | https://docs.aws.amazon.com/AmazonS3/latest/API/sigv4-query-string-auth.html |
| **Signature V4 (Header)** | https://docs.aws.amazon.com/AmazonS3/latest/API/sig-v4-header-based-auth.html |
| **Authenticating Requests** | https://docs.aws.amazon.com/AmazonS3/latest/API/sig-v4-authenticating-requests.html |

### AWS SDK for Java v2

| Topic | Link |
|-------|------|
| **S3Presigner Class** | https://sdk.amazonaws.com/java/api/latest/software/amazon/awssdk/services/s3/presigner/S3Presigner.html |
| **Presigned URL Examples** | https://docs.aws.amazon.com/sdk-for-java/latest/developer-guide/examples-s3-presign.html |
| **SDK GitHub** | https://github.com/aws/aws-sdk-java-v2 |

### LocalStack (Local Development)

| Topic | Link |
|-------|------|
| **LocalStack S3** | https://docs.localstack.cloud/user-guide/aws/s3/ |
| **LocalStack GitHub** | https://github.com/localstack/localstack |

---

## Lưu ý quan trọng

1. **Presigned URL expiry**: Maximum 7 ngày (với IAM user), 36 giờ (với STS credentials)

2. **LocalStack vs AWS**: LocalStack không enforce signature validation chặt như AWS thật

3. **CORS**: Cần configure CORS trên S3 bucket để browser có thể upload trực tiếp

4. **File field order**: Với POST, `file` PHẢI là field cuối cùng trong FormData

5. **Content-Length**: PUT có thể sign Content-Length, nhưng không có range - dùng POST nếu cần `content-length-range`

---

*Document created: 2026-01-13*
*Project: realworld-exam - AWS S3 Presigned URL Demo*
