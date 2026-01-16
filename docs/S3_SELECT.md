# S3 Select

Query CSV/JSON/Parquet trực tiếp trên S3 bằng SQL, không cần download toàn bộ file.

---

## Overview

```
┌─────────────────────────────────────────────────────────────────┐
│  Không có S3 Select                                             │
├─────────────────────────────────────────────────────────────────┤
│                                                                 │
│  File 10GB trên S3                                              │
│         │                                                       │
│         ▼                                                       │
│  Download toàn bộ 10GB → Parse → Filter → Lấy 100 rows          │
│                                                                 │
│  → Tốn bandwidth, memory, thời gian                             │
│                                                                 │
└─────────────────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────────────────┐
│  Có S3 Select                                                   │
├─────────────────────────────────────────────────────────────────┤
│                                                                 │
│  File 10GB trên S3                                              │
│         │                                                       │
│         ▼                                                       │
│  S3 chạy SQL query → Trả về chỉ 100 rows (vài KB)              │
│                                                                 │
│  → Nhanh, rẻ, tiết kiệm bandwidth                               │
│                                                                 │
└─────────────────────────────────────────────────────────────────┘
```

---

## Cách hoạt động

```
┌─────────────────────────────────────────────────────────────────┐
│  S3 Select Processing                                           │
├─────────────────────────────────────────────────────────────────┤
│                                                                 │
│  ┌──────────────────────────────────────────────────────────┐  │
│  │                     S3 Server                             │  │
│  │  ┌─────────┐    ┌──────────┐    ┌─────────┐              │  │
│  │  │  File   │───▶│  Query   │───▶│ Filter  │───▶ Results  │  │
│  │  │ 10GB    │    │  Engine  │    │ Rows    │     100KB    │  │
│  │  └─────────┘    └──────────┘    └─────────┘              │  │
│  │       ▲                                                   │  │
│  │       │ Scan (tốn tiền theo GB scanned)                  │  │
│  └──────────────────────────────────────────────────────────┘  │
│                                      │                          │
│                                      ▼                          │
│                              ┌─────────────┐                   │
│                              │   Client    │                   │
│                              │ (chỉ nhận   │                   │
│                              │  100KB)     │                   │
│                              └─────────────┘                   │
└─────────────────────────────────────────────────────────────────┘
```

---

## Chi phí

| Item | Cost |
|------|------|
| Data scanned | $0.002 per GB |
| Data returned | $0.0007 per GB |

**Ví dụ:** File 10GB, query trả về 1MB
- Scanned: 10GB × $0.002 = $0.02
- Returned: 0.001GB × $0.0007 = ~$0

→ **Rẻ hơn nhiều** so với download 10GB về EC2 rồi process.

---

## Supported Formats

| Format | Compression | Notes |
|--------|-------------|-------|
| **CSV** | GZIP, BZIP2, None | Có/không header |
| **JSON** | GZIP, BZIP2, None | JSON Lines hoặc JSON Document |
| **Parquet** | Snappy, GZIP | Columnar format (scan ít hơn) |

---

## Input Configuration

### CSV

```java
InputSerialization.builder()
    .csv(CSVInput.builder()
        .fileHeaderInfo(FileHeaderInfo.USE)      // USE, IGNORE, NONE
        .comments("#")                            // Skip lines starting with #
        .quoteEscapeCharacter("\\")
        .recordDelimiter("\n")
        .fieldDelimiter(",")
        .quoteCharacter("\"")
        .build())
    .compressionType(CompressionType.GZIP)       // GZIP, BZIP2, NONE
    .build()
```

| FileHeaderInfo | Ý nghĩa |
|----------------|---------|
| `USE` | Row đầu là header, dùng tên column trong query |
| `IGNORE` | Row đầu là header nhưng bỏ qua, dùng `_1`, `_2`... |
| `NONE` | Không có header, dùng `_1`, `_2`... |

### JSON

```java
InputSerialization.builder()
    .json(JSONInput.builder()
        .type(JSONType.LINES)    // Hoặc DOCUMENT
        .build())
    .build()
```

**JSON Lines format:**
```json
{"id": 1, "name": "Alice", "email": "alice@example.com"}
{"id": 2, "name": "Bob", "email": "bob@example.com"}
{"id": 3, "name": "Charlie", "email": "charlie@example.com"}
```

**JSON Document format:**
```json
{
  "users": [
    {"id": 1, "name": "Alice"},
    {"id": 2, "name": "Bob"}
  ]
}
```

Query cho JSON Document:
```sql
SELECT s.users[0].name FROM S3Object s
```

### Parquet

```java
InputSerialization.builder()
    .parquet(ParquetInput.builder().build())
    .build()
```

→ Parquet là **columnar format**, S3 Select chỉ scan columns cần thiết = **rẻ hơn nhiều**.

---

## SQL Syntax

### Basic Query

```sql
SELECT s.id, s.email, s.name 
FROM S3Object s 
WHERE s.status = 'active' 
LIMIT 100
```

### Aggregate Functions

```sql
-- Đếm số records
SELECT COUNT(*) FROM S3Object s

-- Tính tổng
SELECT SUM(CAST(s.amount AS FLOAT)) FROM S3Object s

-- Multiple aggregates
SELECT 
    COUNT(*) as total,
    SUM(CAST(s.amount AS FLOAT)) as sum,
    AVG(CAST(s.amount AS FLOAT)) as avg
FROM S3Object s
```

### String Functions

```sql
SELECT s.* 
FROM S3Object s 
WHERE LOWER(s.email) LIKE '%@gmail.com'

SELECT SUBSTRING(s.phone, 1, 3) as area_code
FROM S3Object s
```

### Type Casting

```sql
-- CSV values are strings, need CAST for comparison
SELECT * 
FROM S3Object s 
WHERE CAST(s.age AS INT) > 18
  AND CAST(s.created_at AS TIMESTAMP) > TIMESTAMP '2026-01-01'
```

---

## SQL Support

| Feature | Hỗ trợ |
|---------|--------|
| `SELECT` | ✅ |
| `WHERE` | ✅ |
| `LIMIT` | ✅ |
| `COUNT`, `SUM`, `AVG`, `MIN`, `MAX` | ✅ |
| `CAST`, `SUBSTRING`, `TRIM`, `UPPER`, `LOWER` | ✅ |
| `LIKE` | ✅ |
| `AND`, `OR`, `NOT` | ✅ |
| `IS NULL`, `IS NOT NULL` | ✅ |
| `GROUP BY` | ❌ |
| `ORDER BY` | ❌ |
| `JOIN` | ❌ |
| `DISTINCT` | ❌ |
| Subqueries | ❌ |
| Window functions | ❌ |

---

## Use Cases

| Use Case | Ví dụ |
|----------|-------|
| **Preview exported data** | Xem 100 rows đầu từ file 10GB |
| **Filter before download** | Chỉ download rows cần thiết |
| **Log analysis** | Query CloudTrail/access logs |
| **Data validation** | Check data trước khi process |
| **Quick stats** | COUNT, SUM trên large file |

---

## Java SDK Implementation

```java
@Service
public class S3SelectService {
    
    private final S3Client s3Client;
    private final String bucket;

    public List<Map<String, String>> query(String key, String sql) {
        SelectObjectContentRequest request = SelectObjectContentRequest.builder()
            .bucket(bucket)
            .key(key)
            .expression(sql)
            .expressionType(ExpressionType.SQL)
            .inputSerialization(InputSerialization.builder()
                .csv(CSVInput.builder()
                    .fileHeaderInfo(FileHeaderInfo.USE)
                    .build())
                .compressionType(CompressionType.GZIP)
                .build())
            .outputSerialization(OutputSerialization.builder()
                .csv(CSVOutput.builder().build())
                .build())
            .build();

        List<Map<String, String>> results = new ArrayList<>();
        
        try (SelectObjectContentResponse response = 
                s3Client.selectObjectContent(request)) {
            
            response.payload().subscribe(event -> {
                if (event instanceof RecordsEvent) {
                    String data = ((RecordsEvent) event).payload().asUtf8String();
                    // Parse CSV rows into maps
                    results.addAll(parseCsvRows(data));
                }
            }).join();
        }
        
        return results;
    }
}
```

---

## Practical Example: Preview Export

```
┌─────────────────────────────────────────────────────────────────┐
│  User exports 10M rows → File 1GB trên S3                       │
│  User muốn xem 100 rows đầu trước khi download                  │
├─────────────────────────────────────────────────────────────────┤
│                                                                 │
│  GET /exports/{jobId}/preview?limit=100                         │
│                   │                                             │
│                   ▼                                             │
│  Backend gọi S3 Select:                                         │
│  "SELECT * FROM S3Object s LIMIT 100"                           │
│                   │                                             │
│                   ▼                                             │
│  Trả về 100 rows (vài KB) trong < 1 giây                        │
│                                                                 │
└─────────────────────────────────────────────────────────────────┘
```

```java
@GetMapping("/exports/{jobId}/preview")
public List<Map<String, String>> previewExport(
    @PathVariable UUID jobId,
    @RequestParam(defaultValue = "100") int limit
) {
    ExportJob job = exportJobRepository.findById(jobId).orElseThrow();
    
    String sql = String.format("SELECT * FROM S3Object s LIMIT %d", limit);
    
    return s3SelectService.query(job.getS3Key(), sql);
}
```

---

## So sánh với Athena

| | S3 Select | Athena |
|---|-----------|--------|
| **SQL** | Giới hạn (no JOIN, GROUP BY) | **Full SQL** |
| **Files** | 1 file | **Nhiều files** (partitioned) |
| **Setup** | Không cần | Cần Glue Catalog (schema) |
| **Cost** | $0.002/GB | $5/TB (~$0.005/GB) |
| **Speed** | Nhanh cho simple | Tối ưu cho complex |
| **Use case** | Preview, filter đơn giản | Analytics, reporting |

### Khi nào dùng gì?

| Cần làm gì | Dùng |
|------------|------|
| Xem 100 rows đầu của 1 file | **S3 Select** |
| Filter simple trên 1 file | **S3 Select** |
| Analytics trên TB data | **Athena** |
| JOIN multiple tables | **Athena** |
| Dashboard/BI reports | **Athena** |

---

## Cost Optimization

| Strategy | Tiết kiệm |
|----------|-----------|
| **Dùng Parquet** | Chỉ scan columns cần thiết |
| **Compress với GZIP** | Scan ít bytes hơn |
| **Partition files** | Query file nhỏ thay vì file lớn |
| **Specific columns** | `SELECT col1, col2` thay vì `SELECT *` |

---

## AWS CLI

```bash
aws s3api select-object-content \
  --bucket my-bucket \
  --key data.csv.gz \
  --expression "SELECT * FROM S3Object s WHERE s.status = 'active' LIMIT 10" \
  --expression-type SQL \
  --input-serialization '{"CSV": {"FileHeaderInfo": "USE"}, "CompressionType": "GZIP"}' \
  --output-serialization '{"CSV": {}}' \
  output.csv
```

---

## LocalStack

```bash
# LocalStack hỗ trợ S3 Select
aws --endpoint-url=http://localhost:4566 s3api select-object-content \
  --bucket my-bucket \
  --key data.csv \
  --expression "SELECT * FROM S3Object s LIMIT 10" \
  --expression-type SQL \
  --input-serialization '{"CSV": {"FileHeaderInfo": "USE"}}' \
  --output-serialization '{"CSV": {}}' \
  output.csv
```

---

## Official Documentation

- [S3 Select Overview](https://docs.aws.amazon.com/AmazonS3/latest/userguide/selecting-content-from-objects.html)
- [SQL Reference](https://docs.aws.amazon.com/AmazonS3/latest/userguide/s3-select-sql-reference.html)
- [SelectObjectContent API](https://docs.aws.amazon.com/AmazonS3/latest/API/API_SelectObjectContent.html)

---

*Document created: 2026-01-15*
*Project: realworld-exam*
