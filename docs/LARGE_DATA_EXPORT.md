# Large Data Export to S3

Export hàng triệu records từ PostgreSQL sang file CSV và upload lên S3 một cách hiệu quả về memory.

## Architecture Overview

```
┌─────────────┐     ┌──────────────────┐     ┌───────────────┐     ┌─────────┐
│   Client    │────▶│  ExportController │────▶│ ExportService │────▶│   S3    │
│             │     │                  │     │  (Async)      │     │         │
└─────────────┘     └──────────────────┘     └───────────────┘     └─────────┘
      │                                              │
      │                                              │ Stream
      │                                              ▼
      │                                      ┌───────────────┐
      │                                      │  PostgreSQL   │
      │                                      └───────────────┘
      │
      ▼
┌─────────────────────────────────────────────────────────────┐
│  1. POST /exports/users → returns jobId                     │
│  2. GET /exports/{jobId} → poll for status & progress       │
│  3. GET /exports/{jobId}/download-url → presigned URL       │
└─────────────────────────────────────────────────────────────┘
```

## Key Techniques

### 1. Streaming Query (Memory-Safe)
```java
// Sử dụng JDBC streaming với fetchSize để tránh load toàn bộ data vào memory
jdbcTemplate.query(con -> {
    var ps = con.prepareStatement(
        "SELECT id, email, name, created_at FROM users",
        ResultSet.TYPE_FORWARD_ONLY,
        ResultSet.CONCUR_READ_ONLY
    );
    ps.setFetchSize(5000);  // Fetch 5000 rows mỗi lần
    return ps;
}, rs -> {
    // Process row-by-row, không buffer trong memory
    upload.write(formatCsvRow(rs));
});
```

### 2. S3 Streaming Multipart Upload (No Temp File)
```java
// Stream trực tiếp lên S3, không cần lưu temp file
public class S3StreamingUploader {
    private static final int PART_SIZE = 5 * 1024 * 1024; // 5MB minimum

    public StreamingUpload startUpload(String key) {
        // Initiate multipart upload
        CreateMultipartUploadResponse response = s3Client.createMultipartUpload(...);
        return new StreamingUpload(response.uploadId());
    }

    class StreamingUpload {
        private ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        
        public void write(String line) {
            writer.write(line + "\n");
            if (buffer.size() >= PART_SIZE) {
                // Upload part khi đủ 5MB
                s3Client.uploadPart(..., RequestBody.fromBytes(buffer.toByteArray()));
                buffer.reset();
            }
        }
        
        public void complete() {
            // Upload phần còn lại + complete multipart
            s3Client.completeMultipartUpload(...);
        }
    }
}
```

**Flow:**
```
DB → Buffer 5MB → Upload Part 1
   → Buffer 5MB → Upload Part 2
   → ...
   → Complete Multipart
```

### 3. Data Consistency với Snapshot Isolation
```java
// PostgreSQL REPEATABLE_READ đảm bảo data nhất quán trong suốt quá trình export
// INSERT/UPDATE/DELETE từ transaction khác không ảnh hưởng
@Transactional(isolation = Isolation.REPEATABLE_READ, readOnly = true)
public void runExportAsync(UUID jobId) {
    // Toàn bộ SELECT sẽ thấy snapshot tại thời điểm bắt đầu transaction
}
```

### 4. Async Processing với Progress Tracking
```java
@Async("taskExecutor")
public void runExportAsync(UUID jobId) {
    // Update progress mỗi 10,000 rows
    if (processed[0] % 10000 == 0) {
        updateProgress(jobId, processed[0]);
    }
}
```

## API Endpoints

### Start Export
```bash
POST /exports/users
```

Response:
```json
{
  "jobId": "550e8400-e29b-41d4-a716-446655440000",
  "message": "Export started",
  "statusUrl": "/exports/550e8400-e29b-41d4-a716-446655440000"
}
```

### Check Status
```bash
GET /exports/{jobId}
```

Response:
```json
{
  "id": "550e8400-e29b-41d4-a716-446655440000",
  "status": "RUNNING",
  "totalRecords": 10000000,
  "processedRecords": 2500000,
  "progressPercent": 25,
  "s3Key": null,
  "errorMessage": null,
  "createdAt": "2026-01-13T10:00:00Z",
  "startedAt": "2026-01-13T10:00:01Z",
  "finishedAt": null
}
```

### Get Download URL
```bash
GET /exports/{jobId}/download-url
```

Response:
```json
{
  "downloadUrl": "http://localhost:4566/demo-bucket/exports/550e8400-e29b-41d4-a716-446655440000.csv?X-Amz-..."
}
```

## Database Schema

```sql
CREATE TABLE export_jobs (
    id UUID PRIMARY KEY,
    status VARCHAR(20) NOT NULL,
    total_records BIGINT,
    processed_records BIGINT DEFAULT 0,
    s3_key VARCHAR(255),
    error_message VARCHAR(1000),
    created_at TIMESTAMP NOT NULL,
    started_at TIMESTAMP,
    finished_at TIMESTAMP
);

-- Example users table for testing
CREATE TABLE users (
    id SERIAL PRIMARY KEY,
    email VARCHAR(255),
    name VARCHAR(255),
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);
```

## Configuration

```yaml
# application.yml
spring:
  datasource:
    url: jdbc:postgresql://localhost:5432/demo
    hikari:
      auto-commit: false  # Required for streaming

aws:
  s3:
    bucket: demo-bucket
    endpoint: http://localhost:4566
    region: us-east-1
    access-key: test
    secret-key: test
```

## Memory Considerations

| Records | Estimated CSV Size | Memory Usage |
|---------|-------------------|--------------|
| 1M      | ~100MB            | ~50MB        |
| 10M     | ~1GB              | ~50MB        |
| 100M    | ~10GB             | ~50MB        |

Memory usage stays constant vì:
- Streaming query: chỉ giữ `fetchSize` rows trong memory
- Row-by-row CSV write: không buffer toàn bộ file
- Temp file: data được ghi ra disk, không giữ trong memory

## Error Handling

- **DB connection lost**: Job marked as FAILED, có thể retry bằng cách tạo job mới
- **S3 upload failed**: Transfer Manager tự cleanup multipart upload
- **Temp file**: Luôn được xóa trong finally block

## Official Documentation

- [AWS SDK v2 S3 Transfer Manager](https://docs.aws.amazon.com/sdk-for-java/latest/developer-guide/transfer-manager.html)
- [PostgreSQL JDBC Streaming](https://jdbc.postgresql.org/documentation/head/query.html#query-with-cursor)
- [Spring @Async](https://docs.spring.io/spring-framework/reference/integration/scheduling.html#scheduling-annotation-support-async)
