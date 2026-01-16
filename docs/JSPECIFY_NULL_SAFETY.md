# JSpecify Null Safety - Documentation

Hướng dẫn sử dụng JSpecify + NullAway để đảm bảo null safety trong project Java.

## Tổng quan các Tools

### JSpecify, Error Prone, NullAway là gì?

| Tool | Vai trò | Của ai |
|------|---------|--------|
| **JSpecify** | Annotations (`@Nullable`, `@NullMarked`) | Community standard |
| **Error Prone** | Framework chạy checks lúc compile | Google |
| **NullAway** | Plugin check null safety | Uber |

### Error Prone

**Error Prone** là framework của Google để tạo các **compile-time checks** cho Java. NullAway là một plugin chạy trên Error Prone.

```
┌─────────────────────────────────────────────┐
│              Error Prone                     │
│  (Framework for compile-time analysis)       │
│                                              │
│  ┌──────────┐  ┌──────────┐  ┌──────────┐   │
│  │ NullAway │  │BuiltIn   │  │ Custom   │   │
│  │ (Uber)   │  │ Checks   │  │ Checks   │   │
│  └──────────┘  └──────────┘  └──────────┘   │
└─────────────────────────────────────────────┘
```

Error Prone có sẵn **500+ checks** cho các bugs phổ biến:
- `StringSplitter` - dùng Splitter thay vì String.split()
- `MissingOverride` - thiếu @Override
- `EqualsHashCode` - override equals() mà không override hashCode()

### NullAway

**NullAway** là tool của Uber để phát hiện `NullPointerException` lúc **compile time** thay vì runtime.

```
Code có bug                    Build với NullAway
      │                              │
      ▼                              ▼
┌─────────────┐              ┌─────────────────┐
│ String x;   │              │ error: @NonNull │
│ x.length(); │  ──────────▶ │ field x not     │
│ // NPE!     │              │ initialized     │
└─────────────┘              └─────────────────┘
   Runtime crash                Compile error
```

**Đặc điểm:**
- Nhanh (~10% build overhead)
- Chỉ check packages bạn chỉ định (`NullAway:AnnotatedPackages`)
- Dựa trên `@Nullable` annotations từ JSpecify

---

## Setup

### 1. Dependencies (build.gradle)

```gradle
plugins {
    id 'net.ltgt.errorprone' version '4.1.0'
}

dependencies {
    // JSpecify annotations
    implementation 'org.jspecify:jspecify:1.0.0'
    
    // NullAway + Error Prone
    errorprone 'com.uber.nullaway:nullaway:0.12.4'
    errorprone 'com.google.errorprone:error_prone_core:2.36.0'
}

// NullAway configuration
import net.ltgt.gradle.errorprone.CheckSeverity

tasks.withType(JavaCompile).configureEach {
    options.errorprone {
        check("NullAway", CheckSeverity.ERROR)
        option("NullAway:AnnotatedPackages", "com.seft.learn")
        option("NullAway:JSpecifyMode", "true")
    }
    // Disable on test code
    if (name.toLowerCase().contains("test")) {
        options.errorprone {
            disable("NullAway")
        }
    }
}
```

### 2. Lombok Configuration (lombok.config)

```properties
# Required for NullAway to skip Lombok-generated code
lombok.addLombokGeneratedAnnotation = true
```

### 3. Package-level @NullMarked (package-info.java)

```java
@NullMarked
package com.seft.learn.example;

import org.jspecify.annotations.NullMarked;
```

---

## Annotations

| Annotation | Meaning | Where to use |
|------------|---------|--------------|
| `@NullMarked` | Default = non-null | Package, class, method |
| `@Nullable` | Can be null | Field, parameter, return type |
| `@NonNull` | Cannot be null (explicit) | Type arguments in generics |
| `@NullUnmarked` | Disable null checking | For gradual adoption |

---

## Common Patterns

### 1. Method returns nullable

```java
public @Nullable ExportJob getJob(UUID jobId) {
    return exportJobRepository.findById(jobId).orElse(null);
}
```

### 2. Parameter accepts nullable

```java
private void handleError(UUID jobId, 
                         S3StreamingUploader.@Nullable StreamingUpload upload, 
                         Exception e) {
    if (upload != null) {
        upload.abort();
    }
}
```

### 3. Nullable field with @Value

```java
// ❌ NullAway error: field not initialized
@Value("${aws.s3.bucket}")
private String bucketName;

// ✅ Provide default value
@Value("${aws.s3.bucket}")
private String bucketName = "";
```

### 4. Record with nullable fields

```java
public record ExportJobResponse(
    UUID id,
    String status,                    // non-null
    @Nullable Long totalRecords,      // nullable
    @Nullable String errorMessage     // nullable
) {}
```

### 5. Nullable in generics

```java
// List that can contain null elements
List<@Nullable String> names;

// Map with nullable values
Map<String, @Nullable Integer> scores;
```

### 6. Nested type annotation

```java
// For nested static classes, annotation goes before inner type
S3StreamingUploader.@Nullable StreamingUpload upload

// For java.time.Instant in a different package
java.time.@Nullable Instant startedAt
```

### 7. Handle nullable from Exception.getMessage()

```java
// ❌ getMessage() returns @Nullable String
job.setErrorMessage(e.getMessage());

// ✅ Handle null explicitly
String errorMessage = e.getMessage();
job.setErrorMessage(errorMessage != null ? errorMessage : "Unknown error");
```

---

## Build-time Errors

NullAway catches errors at compile time:

```
error: [NullAway] returning @Nullable expression from method with @NonNull return type
    return exportJobRepository.findById(jobId).orElse(null);
    ^

error: [NullAway] passing @Nullable parameter 'upload' where @NonNull is required
    handleError(jobId, upload, e);
                       ^

error: [NullAway] @NonNull field bucketName not initialized
    private String bucketName;
                   ^
```

---

## Best Practices

### 1. Start with @NullMarked at package level
```java
// package-info.java
@NullMarked
package com.yourcompany.app;
```

### 2. Add @Nullable only where needed
In `@NullMarked` scope, everything is non-null by default. Only annotate what can be null.

### 3. Avoid null - use Optional for return types
```java
// ❌ Returns null
public @Nullable User findUser(String id) {
    return userRepository.findById(id).orElse(null);
}

// ✅ Returns Optional
public Optional<User> findUser(String id) {
    return userRepository.findById(id);
}
```

### 4. Validate early, avoid null propagation
```java
// ❌ Propagate null
public void process(@Nullable String input) {
    // ... null checks everywhere
}

// ✅ Validate at entry point
public void process(String input) {
    Objects.requireNonNull(input, "input must not be null");
    // ... no null checks needed
}
```

### 5. Use @NullUnmarked for gradual adoption
```java
@NullMarked
package com.example;

// In a class that's not ready yet
@NullUnmarked
class LegacyCode {
    // No null checks here
}
```

---

## Spring Framework 7 / Boot 4 Integration

Spring Framework 7 đã chuyển sang JSpecify. Các API của Spring sẽ có null annotations, giúp:
- IDE warnings chính xác hơn
- Kotlin interop tốt hơn (null-safe types)
- Build-time checks với NullAway

---

## Official Documentation

| Resource | Link |
|----------|------|
| JSpecify User Guide | https://jspecify.dev/docs/user-guide/ |
| JSpecify Javadoc | https://jspecify.dev/docs/api/org/jspecify/annotations/package-summary.html |
| NullAway | https://github.com/uber/NullAway |
| Spring Null Safety | https://spring.io/blog/2025/03/10/null-safety-in-spring-apps-with-jspecify-and-null-away |
| Error Prone | https://errorprone.info/ |

---

*Document created: 2026-01-15*
*Project: realworld-exam*
