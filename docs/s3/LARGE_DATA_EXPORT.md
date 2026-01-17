# Large Data Export to S3

Export hàng triệu records từ PostgreSQL sang file CSV (gzip compressed) và upload lên S3 một cách hiệu quả về memory.

## Architecture Overview

```
┌─────────────────────────────────────────────────────────────────┐
│                       ExportService                             │
│                                                                 │
│  ┌───────────┐    ┌────────────┐    ┌──────────┐    ┌────────┐ │
│  │PostgreSQL │───▶│10000 rows  │───▶│ CSV Line │───▶│  Gzip  │ │
│  │  Cursor   │    │   Buffer   │    │  Format  │    │ Buffer │ │
│  └───────────┘    └────────────┘    └──────────┘    └───┬────┘ │
│       ▲                                                  │      │
│       │ Fetch next 10000                                 │      │
│       └──────────────────────────────────────────────────┘      │
│                                                          │      │
│                                    Parallel Upload Parts │      │
│                                         (4 threads)      ▼      │
│                                                   ┌──────────┐  │
│                                                   │    S3    │  │
│                                                   │Multipart │  │
│                                                   └──────────┘  │
└─────────────────────────────────────────────────────────────────┘
```

### Client Flow
```
┌─────────────────────────────────────────────────────────────┐
│  1. POST /exports/users → returns jobId                     │
│  2. GET /exports/{jobId} → poll for status & progress       │
│  3. GET /exports/{jobId}/download-url → presigned URL       │
└─────────────────────────────────────────────────────────────┘
```

## Key Techniques

### 1. Streaming Query (Memory-Safe)

**Vấn đề:** Load 10M rows vào memory → OutOfMemoryError

**Giải pháp:** JDBC Streaming với cursor

```java
jdbcTemplate.query(con -> {
    var ps = con.prepareStatement(
        "SELECT id, email, name, created_at FROM users",
        ResultSet.TYPE_FORWARD_ONLY,   // Chỉ đọc tiến, không back
        ResultSet.CONCUR_READ_ONLY     // Không update
    );
    ps.setFetchSize(10000);  // ⭐ Fetch 10000 rows mỗi lần 
    return ps;
}, rs -> {
    // Process từng row, không buffer toàn bộ
    upload.write(formatCsvRow(rs));
});
```

**Cách hoạt động:**
- PostgreSQL giữ **cursor** trên server
- JDBC driver chỉ fetch `10000` rows vào memory
- Khi đọc hết → fetch tiếp 10000 rows tiếp theo
- Memory usage **cố định ~100MB** dù export 10M hay 100M rows

**Yêu cầu:**
- `auto-commit: false` trong Hikari config
- `TYPE_FORWARD_ONLY` + `CONCUR_READ_ONLY`

### 2. S3 Multipart Upload (Stream trực tiếp, không temp file)

**Vấn đề:** File 10GB không thể giữ trong memory hoặc disk

**Giải pháp:** S3 Multipart Upload - stream từng phần 5MB

#### Flow Multipart Upload

```
1. createMultipartUpload()     → Nhận uploadId
   
2. Loop: mỗi khi buffer >= 5MB
   └── uploadPart(partNumber=1) → ETag1
   └── uploadPart(partNumber=2) → ETag2
   └── uploadPart(partNumber=3) → ETag3
   ...

3. completeMultipartUpload(parts=[{1,ETag1}, {2,ETag2}, ...])
```

#### Implementation

```java
@Component
public class S3StreamingUploader {
    private static final int PART_SIZE = 5 * 1024 * 1024; // 5MB minimum

    public StreamingUpload startUpload(String key) {
        // Bước 1: Khởi tạo multipart upload
        CreateMultipartUploadResponse response = s3Client.createMultipartUpload(
            CreateMultipartUploadRequest.builder()
                .bucket(bucketName)
                .key(key)
                .contentType("text/csv")
                .build()
        );
        return new StreamingUpload(s3Client, bucketName, key, response.uploadId());
    }

    public static class StreamingUpload {
        private final ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        private final List<CompletedPart> completedParts = new ArrayList<>();
        private int partNumber = 1;  // ⭐ Part number bắt đầu từ 1

        public void write(String line) throws IOException {
            buffer.write((line + "\n").getBytes());

            // Bước 2: Upload part khi buffer đủ 5MB
            if (buffer.size() >= PART_SIZE) {
                flushPart();
            }
        }

        private void flushPart() {
            byte[] data = buffer.toByteArray();
            
            UploadPartResponse response = s3Client.uploadPart(
                UploadPartRequest.builder()
                    .bucket(bucket)
                    .key(key)
                    .uploadId(uploadId)
                    .partNumber(partNumber)  // ⭐ Part number
                    .build(),
                RequestBody.fromBytes(data)
            );

            // Lưu ETag để complete sau
            completedParts.add(CompletedPart.builder()
                .partNumber(partNumber)
                .eTag(response.eTag())
                .build());

            log.info("Uploaded part {}: {} bytes", partNumber, data.length);
            partNumber++;
            buffer.reset();  // Clear buffer
        }

        public void complete() {
            flushPart();  // Upload phần còn lại

            // Bước 3: Complete multipart upload
            s3Client.completeMultipartUpload(
                CompleteMultipartUploadRequest.builder()
                    .bucket(bucket)
                    .key(key)
                    .uploadId(uploadId)
                    .multipartUpload(CompletedMultipartUpload.builder()
                        .parts(completedParts)  // ⭐ List các part đã upload
                        .build())
                    .build()
            );
            log.info("Completed multipart upload: {} parts", completedParts.size());
        }

        public void abort() {
            // Cleanup nếu có lỗi
            s3Client.abortMultipartUpload(...);
        }
    }
}
```

#### Tại sao cần Multipart?

| Upload thường | Multipart Upload |
|---------------|------------------|
| Max 5GB | Max **5TB** |
| Fail → upload lại từ đầu | Fail → retry part đó |
| Phải có full file trước | **Stream từng phần** |
| 1 request | N requests (có thể parallel) |

#### Log khi chạy

```
Uploaded part 1: 5242880 bytes
Uploaded part 2: 5242880 bytes
Uploaded part 3: 5242880 bytes
...
Completed multipart upload: 10 parts
```

### 3. Data Consistency với Snapshot Isolation

**Vấn đề:** Export mất 10 phút, trong lúc đó có INSERT/UPDATE/DELETE → data không nhất quán

**Giải pháp:** PostgreSQL `REPEATABLE_READ` isolation

```java
@Transactional(isolation = Isolation.REPEATABLE_READ, readOnly = true)
public void runExportAsync(UUID jobId) {
    // Tất cả SELECT thấy snapshot tại thời điểm bắt đầu transaction
}
```

**Behavior:**
| Thao tác từ transaction khác | Kết quả trong export |
|------------------------------|----------------------|
| INSERT row mới | ❌ Không thấy |
| DELETE row | ✅ Vẫn thấy (giá trị cũ) |
| UPDATE row | ✅ Vẫn thấy (giá trị cũ) |

→ **Data nhất quán 100%** trong file export

### 4. Async Processing + Progress Tracking

```java
@Async("taskExecutor")  // Chạy trong thread pool riêng
public void runExportAsync(UUID jobId) {
    // ...
    if (processed[0] % 10000 == 0) {
        updateProgress(jobId, processed[0]);
    }
}
```

**Tại sao Async?**
- HTTP request trả về ngay với `jobId` (202 Accepted)
- Export chạy background, không block HTTP thread
- Client poll `/exports/{jobId}` để xem progress

**Update Progress với JDBC (không qua JPA):**

```java
// ❌ Không dùng được - self-invocation không trigger @Transactional
@Transactional
public void updateProgress(UUID jobId, long processed) {
    exportJobRepository.updateProgress(jobId, processed);
}

// ✅ Dùng JDBC trực tiếp - bypass transaction
public void updateProgress(UUID jobId, long processed) {
    jdbcTemplate.update(
        "UPDATE export_jobs SET processed_records = ? WHERE id = ?",
        processed, jobId
    );
}
```

**Lý do:** Method trong cùng class gọi nhau (self-invocation) → Spring AOP không intercept → `@Transactional` bị bỏ qua → dùng JDBC để bypass.

## API Endpoints

### Start Export
```bash
POST /exports/users
```

Response (202 Accepted):
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
  "downloadUrl": "http://localhost:4566/demo-bucket/exports/550e8400.csv?X-Amz-..."
}
```

## Database Schema

```sql
CREATE TABLE export_jobs (
    id UUID PRIMARY KEY,
    status VARCHAR(20) NOT NULL,      -- PENDING, RUNNING, COMPLETED, FAILED
    total_records BIGINT,
    processed_records BIGINT DEFAULT 0,
    s3_key VARCHAR(255),
    error_message VARCHAR(1000),
    created_at TIMESTAMP NOT NULL,
    started_at TIMESTAMP,
    finished_at TIMESTAMP,
    -- Metrics
    file_size_bytes BIGINT,
    uncompressed_size_bytes BIGINT,
    rows_per_second DOUBLE PRECISION,
    duration_ms BIGINT
);

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
      auto-commit: false  # ⭐ Required for streaming query

aws:
  s3:
    bucket: demo-bucket
    endpoint: http://localhost:4566
    region: ap-southeast-1

management:
  endpoints:
    web:
      exposure:
        include: health,metrics,info
```

## Performance Optimizations

### 1. Gzip Compression (70-80% file size reduction)

```java
// Enable gzip khi upload
upload = s3StreamingUploader.startUpload(s3Key, true); // gzipEnabled = true

// S3 sẽ set Content-Encoding: gzip
// Browser tự động decompress khi download
```

**Kết quả:**
| Rows | Uncompressed | Gzipped | Reduction |
|------|-------------|---------|-----------|
| 100K | 10 MB | 2 MB | **80%** |
| 1M | 100 MB | 20 MB | **80%** |
| 10M | 1 GB | 200 MB | **80%** |

### 2. Larger Fetch Size (giảm DB round-trips)

```java
ps.setFetchSize(10000);  // Tăng từ 5000 lên 10000
```

**Trade-off:**
| Fetch Size | Memory | DB Round-trips |
|------------|--------|----------------|
| 5000 | ~50MB | Nhiều hơn |
| 10000 | ~100MB | Ít hơn 50% |
| 20000 | ~200MB | Ít hơn 75% |

### 3. Parallel Part Upload (4x faster upload)

```java
private static final int MAX_CONCURRENT_UPLOADS = 4;

// Upload 4 parts đồng thời
CompletableFuture<CompletedPart> future = CompletableFuture.supplyAsync(
    () -> uploadPart(data, partNumber), uploadExecutor);
```

**Flow:**
```
Part 1 ──┐
Part 2 ──┼──▶ ExecutorService (4 threads) ──▶ S3
Part 3 ──┤
Part 4 ──┘
```

## Memory Considerations

| Records | CSV Size | Gzipped Size | Memory Usage |
|---------|----------|--------------|--------------|
| 1M      | ~100MB   | ~20MB        | **~120MB**   |
| 10M     | ~1GB     | ~200MB       | **~120MB**   |
| 100M    | ~10GB    | ~2GB         | **~120MB**   |

**Memory breakdown:**

| Component | Memory |
|-----------|--------|
| JDBC ResultSet buffer | ~100MB (10000 rows × ~10KB/row) |
| S3 Multipart buffer × 4 | 20MB (5MB × 4 threads) |
| Gzip buffer | ~1MB |
| **Total** | **~120MB** |

## Error Handling

| Lỗi | Xử lý |
|-----|-------|
| DB connection lost | Job → FAILED, retry bằng cách tạo job mới |
| S3 upload failed | Gọi `abort()` để cleanup multipart upload |
| Exception trong export | Catch, mark FAILED, log error message |

```java
} catch (Exception e) {
    if (upload != null) {
        upload.abort();  // Cleanup incomplete multipart
    }
    job.setStatus(ExportStatus.FAILED);
    job.setErrorMessage(e.getMessage());
}
```

## Export Metrics

Response khi export hoàn thành:

```json
{
  "id": "550e8400-e29b-41d4-a716-446655440000",
  "status": "COMPLETED",
  "totalRecords": 1000000,
  "fileSizeBytes": 20480000,
  "fileSizeFormatted": "19.53 MB",
  "uncompressedSizeBytes": 102400000,
  "uncompressedSizeFormatted": "97.66 MB",
  "compressionPercent": 80.0,
  "rowsPerSecond": 50000.0,
  "durationMs": 20000,
  "durationFormatted": "20s"
}
```

## Official Documentation

- [AWS S3 Multipart Upload](https://docs.aws.amazon.com/AmazonS3/latest/userguide/mpuoverview.html)
- [AWS SDK v2 S3 Client](https://docs.aws.amazon.com/sdk-for-java/latest/developer-guide/examples-s3.html)
- [PostgreSQL JDBC Streaming](https://jdbc.postgresql.org/documentation/head/query.html#query-with-cursor)
- [Spring @Async](https://docs.spring.io/spring-framework/reference/integration/scheduling.html#scheduling-annotation-support-async)
- [Spring Boot Actuator](https://docs.spring.io/spring-boot/docs/current/reference/html/actuator.html)

---

*Document created: 2026-01-13*
*Project: realworld-exam*
