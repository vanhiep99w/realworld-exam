# MDC (Mapped Diagnostic Context) - Documentation

## Mục lục
1. [Tổng quan](#tổng-quan)
2. [Tại sao cần MDC](#tại-sao-cần-mdc)
3. [Cách hoạt động](#cách-hoạt-động)
4. [Implementation trong Spring Boot](#implementation-trong-spring-boot)
5. [Common Use Cases](#common-use-cases)
6. [Pitfalls và Best Practices](#pitfalls-và-best-practices)
7. [Tài liệu tham khảo](#tài-liệu-tham-khảo)

---

## Tổng quan

**MDC (Mapped Diagnostic Context)** là cơ chế lưu trữ **thread-local** cho phép thêm contextual data vào mọi log message mà không cần truyền parameters qua từng method.

```
┌─────────────────────────────────────────────────────────────────────────┐
│  Không có MDC                                                            │
│                                                                          │
│  2026-01-18 10:00:01 INFO  Processing order                             │
│  2026-01-18 10:00:01 INFO  Processing order     ◄── Không biết order nào│
│  2026-01-18 10:00:02 INFO  Order completed                              │
│  2026-01-18 10:00:02 ERROR Payment failed                               │
│                                                                          │
├─────────────────────────────────────────────────────────────────────────┤
│  Có MDC                                                                  │
│                                                                          │
│  2026-01-18 10:00:01 INFO  [orderId=123 userId=U001] Processing order   │
│  2026-01-18 10:00:01 INFO  [orderId=456 userId=U002] Processing order   │
│  2026-01-18 10:00:02 INFO  [orderId=123 userId=U001] Order completed    │
│  2026-01-18 10:00:02 ERROR [orderId=456 userId=U002] Payment failed     │
│                            ▲                                             │
│                            └── Dễ dàng trace từng request               │
└─────────────────────────────────────────────────────────────────────────┘
```

### MDC được hỗ trợ bởi

| Framework | Class |
|-----------|-------|
| **SLF4J** | `org.slf4j.MDC` |
| **Logback** | Dùng SLF4J MDC |
| **Log4j2** | `org.apache.logging.log4j.ThreadContext` |
| **Log4j** | `org.apache.log4j.MDC` |

---

## Tại sao cần MDC

### Vấn đề: Interleaved Logs

Trong multi-threaded application, logs từ nhiều requests xen kẽ nhau:

```
┌─────────────────────────────────────────────────────────────────────────┐
│  Thread pool xử lý 3 requests đồng thời                                 │
│                                                                          │
│  Thread-1: Request A (order 123)                                        │
│  Thread-2: Request B (order 456)                                        │
│  Thread-3: Request C (order 789)                                        │
│                                                                          │
│  Logs output (không có MDC):                                            │
│  ┌─────────────────────────────────────────────────────────────────┐   │
│  │ 10:00:01 [thread-1] INFO  Preparing to transfer 1000$          │   │
│  │ 10:00:01 [thread-2] INFO  Preparing to transfer 2000$          │   │
│  │ 10:00:02 [thread-3] INFO  Preparing to transfer 500$           │   │
│  │ 10:00:02 [thread-1] INFO  Transfer completed                   │   │
│  │ 10:00:03 [thread-2] ERROR Transfer failed      ◄── Order nào?  │   │
│  │ 10:00:03 [thread-3] INFO  Transfer completed                   │   │
│  └─────────────────────────────────────────────────────────────────┘   │
│                                                                          │
│  → Không thể trace được request nào gây lỗi!                            │
└─────────────────────────────────────────────────────────────────────────┘
```

### Giải pháp: MDC

```
┌─────────────────────────────────────────────────────────────────────────┐
│  Với MDC: Mỗi log có context                                            │
│                                                                          │
│  10:00:01 [thread-1] INFO  [orderId=123] Preparing to transfer 1000$   │
│  10:00:01 [thread-2] INFO  [orderId=456] Preparing to transfer 2000$   │
│  10:00:02 [thread-3] INFO  [orderId=789] Preparing to transfer 500$    │
│  10:00:02 [thread-1] INFO  [orderId=123] Transfer completed            │
│  10:00:03 [thread-2] ERROR [orderId=456] Transfer failed               │
│  10:00:03 [thread-3] INFO  [orderId=789] Transfer completed            │
│                             ▲                                           │
│                             └── Order 456 failed!                       │
└─────────────────────────────────────────────────────────────────────────┘
```

---

## Cách hoạt động

### ThreadLocal Storage

MDC sử dụng **ThreadLocal** để lưu data. Mỗi thread có bản sao riêng của MDC map.

```
┌─────────────────────────────────────────────────────────────────────────┐
│                                                                          │
│  Thread-1                    Thread-2                   Thread-3        │
│  ┌─────────────────┐        ┌─────────────────┐        ┌─────────────┐  │
│  │ MDC Map:        │        │ MDC Map:        │        │ MDC Map:    │  │
│  │ orderId = 123   │        │ orderId = 456   │        │ orderId=789 │  │
│  │ userId = U001   │        │ userId = U002   │        │ userId=U003 │  │
│  └─────────────────┘        └─────────────────┘        └─────────────┘  │
│         │                          │                          │         │
│         ▼                          ▼                          ▼         │
│  log.info("msg")            log.info("msg")            log.info("msg")  │
│         │                          │                          │         │
│         ▼                          ▼                          ▼         │
│  [orderId=123] msg          [orderId=456] msg          [orderId=789] msg│
│                                                                          │
└─────────────────────────────────────────────────────────────────────────┘
```

### Basic API

```java
import org.slf4j.MDC;

// Put values vào MDC
MDC.put("orderId", "123");
MDC.put("userId", "U001");

// Get value từ MDC
String orderId = MDC.get("orderId");

// Remove single key
MDC.remove("orderId");

// Clear tất cả
MDC.clear();

// Get toàn bộ context
Map<String, String> context = MDC.getCopyOfContextMap();

// Restore context (hữu ích cho async)
MDC.setContextMap(context);
```

---

## Implementation trong Spring Boot

### 1. Filter để set MDC cho mỗi request

```java
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class MDCFilter implements Filter {

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, 
                         FilterChain chain) throws IOException, ServletException {
        
        HttpServletRequest httpRequest = (HttpServletRequest) request;
        
        try {
            // Generate hoặc lấy request ID từ header
            String requestId = httpRequest.getHeader("X-Request-ID");
            if (requestId == null) {
                requestId = UUID.randomUUID().toString().substring(0, 8);
            }
            
            // Set MDC values
            MDC.put("requestId", requestId);
            MDC.put("clientIP", httpRequest.getRemoteAddr());
            MDC.put("method", httpRequest.getMethod());
            MDC.put("uri", httpRequest.getRequestURI());
            
            // Nếu có authenticated user
            Authentication auth = SecurityContextHolder.getContext().getAuthentication();
            if (auth != null && auth.isAuthenticated()) {
                MDC.put("userId", auth.getName());
            }
            
            chain.doFilter(request, response);
            
        } finally {
            // QUAN TRỌNG: Luôn clear MDC sau request
            MDC.clear();
        }
    }
}
```

### 2. Cấu hình Logback pattern

```xml
<!-- logback-spring.xml -->
<configuration>
    <appender name="CONSOLE" class="ch.qos.logback.core.ConsoleAppender">
        <encoder>
            <pattern>
                %d{yyyy-MM-dd HH:mm:ss.SSS} %5level [%thread] [%X{requestId}] [%X{userId}] %logger{36} - %msg%n
            </pattern>
        </encoder>
    </appender>
    
    <root level="INFO">
        <appender-ref ref="CONSOLE"/>
    </root>
</configuration>
```

**Pattern placeholders:**
- `%X{requestId}` - Lấy value từ MDC với key "requestId"
- `%X` - In tất cả MDC values

### 3. JSON logs với MDC

```xml
<!-- logback-spring.xml với JSON output -->
<configuration>
    <appender name="JSON" class="ch.qos.logback.core.ConsoleAppender">
        <encoder class="net.logstash.logback.encoder.LogstashEncoder">
            <includeMdcKeyName>requestId</includeMdcKeyName>
            <includeMdcKeyName>userId</includeMdcKeyName>
            <includeMdcKeyName>clientIP</includeMdcKeyName>
        </encoder>
    </appender>
</configuration>
```

**Output:**
```json
{
  "@timestamp": "2026-01-18T10:00:01.123Z",
  "level": "INFO",
  "message": "Order processed",
  "requestId": "abc12345",
  "userId": "U001",
  "clientIP": "192.168.1.100"
}
```

### 4. Sử dụng trong Service layer

```java
@Service
@RequiredArgsConstructor
public class OrderService {
    
    private static final Logger log = LoggerFactory.getLogger(OrderService.class);
    
    public void processOrder(Order order) {
        // Thêm business context vào MDC
        MDC.put("orderId", order.getId());
        MDC.put("orderAmount", String.valueOf(order.getAmount()));
        
        try {
            log.info("Starting order processing");
            
            // Business logic...
            validateOrder(order);
            processPayment(order);
            
            log.info("Order processing completed");
            
        } finally {
            // Clean up business-specific MDC
            MDC.remove("orderId");
            MDC.remove("orderAmount");
        }
    }
    
    private void validateOrder(Order order) {
        log.debug("Validating order");  // Tự động có orderId, requestId...
    }
    
    private void processPayment(Order order) {
        log.info("Processing payment");  // Tự động có orderId, requestId...
    }
}
```

---

## Common Use Cases

### 1. Request Tracing

Trace request xuyên suốt application:

```java
MDC.put("requestId", UUID.randomUUID().toString());
MDC.put("traceId", request.getHeader("X-Trace-ID"));
MDC.put("spanId", generateSpanId());
```

### 2. User Context

Thêm thông tin user vào mọi logs:

```java
MDC.put("userId", user.getId());
MDC.put("userEmail", user.getEmail());
MDC.put("tenantId", user.getTenantId());  // Multi-tenant app
```

### 3. Transaction Context

Track database transactions:

```java
MDC.put("transactionId", transaction.getId());
MDC.put("transactionType", "PAYMENT");
MDC.put("amount", String.valueOf(amount));
```

### 4. Correlation ID cho Microservices

Truyền correlation ID qua services:

```
┌──────────────────────────────────────────────────────────────────────────┐
│                                                                           │
│  Service A              Service B              Service C                 │
│  ┌──────────┐          ┌──────────┐          ┌──────────┐               │
│  │MDC:      │ ──HTTP──►│MDC:      │ ──HTTP──►│MDC:      │               │
│  │corrId=X  │  Header: │corrId=X  │  Header: │corrId=X  │               │
│  │          │  X-Corr  │          │  X-Corr  │          │               │
│  └──────────┘          └──────────┘          └──────────┘               │
│                                                                           │
│  Logs từ cả 3 services đều có corrId=X → dễ trace                        │
└──────────────────────────────────────────────────────────────────────────┘
```

```java
// Service A - gửi request
RestTemplate restTemplate = new RestTemplate();
HttpHeaders headers = new HttpHeaders();
headers.set("X-Correlation-ID", MDC.get("correlationId"));

// Service B - nhận request
@Component
public class CorrelationIdFilter implements Filter {
    @Override
    public void doFilter(...) {
        String corrId = request.getHeader("X-Correlation-ID");
        if (corrId == null) {
            corrId = UUID.randomUUID().toString();
        }
        MDC.put("correlationId", corrId);
        // ...
    }
}
```

---

## Pitfalls và Best Practices

### ⚠️ Pitfall 1: Không clear MDC

```java
// ❌ BAD - Quên clear MDC
public void processRequest() {
    MDC.put("orderId", "123");
    doSomething();
    // Quên MDC.clear() → Thread pool reuse → Data leak!
}

// ✅ GOOD - Luôn clear trong finally
public void processRequest() {
    try {
        MDC.put("orderId", "123");
        doSomething();
    } finally {
        MDC.clear();
    }
}
```

### ⚠️ Pitfall 2: MDC không tự động propagate sang child threads

**Vấn đề:** MDC dùng `ThreadLocal` → mỗi thread có MDC riêng, **không tự động copy** sang thread mới.

```
┌─────────────────────────────────────────────────────────────────────────┐
│  Main Thread (xử lý HTTP request)                                       │
│  ┌─────────────────────────────────────────────────────────────────┐   │
│  │  MDC: { requestId: "abc123", userId: "U001" }                   │   │
│  │                                                                  │   │
│  │  executor.submit(() -> {                                         │   │
│  │      log.info("Processing...");  // ← Chạy trên Thread-2        │   │
│  │  });                                                             │   │
│  └─────────────────────────────────────────────────────────────────┘   │
│                              │                                          │
│                              ▼                                          │
│  Thread-2 (từ Thread Pool)                                              │
│  ┌─────────────────────────────────────────────────────────────────┐   │
│  │  MDC: { }  ← TRỐNG! Không có requestId, userId                  │   │
│  └─────────────────────────────────────────────────────────────────┘   │
└─────────────────────────────────────────────────────────────────────────┘
```

**Có 3 cách giải quyết:**

#### Option 1: Làm tay mỗi lần (không khuyến khích)

```java
// Phải viết lại mỗi lần submit - dễ quên
Map<String, String> context = MDC.getCopyOfContextMap();
executor.submit(() -> {
    try {
        MDC.setContextMap(context);
        doWork();
    } finally {
        MDC.clear();
    }
});
```

#### Option 2: Tạo Utility wrapper

```java
public class MdcRunnable implements Runnable {
    private final Runnable delegate;
    private final Map<String, String> context;
    
    public MdcRunnable(Runnable delegate) {
        this.delegate = delegate;
        this.context = MDC.getCopyOfContextMap();  // Copy lúc tạo
    }
    
    @Override
    public void run() {
        try {
            if (context != null) {
                MDC.setContextMap(context);
            }
            delegate.run();
        } finally {
            MDC.clear();
        }
    }
}

// Sử dụng
executor.submit(new MdcRunnable(() -> doWork()));
```

#### Option 3: Custom Executor (tự động hoàn toàn) ✅ Recommended

```java
public class MdcAwareExecutor extends ThreadPoolExecutor {
    
    public MdcAwareExecutor(int corePoolSize, int maxPoolSize) {
        super(corePoolSize, maxPoolSize, 60L, TimeUnit.SECONDS, 
              new LinkedBlockingQueue<>());
    }
    
    @Override
    public void execute(Runnable command) {
        // Tự động wrap mọi task với MDC
        Map<String, String> context = MDC.getCopyOfContextMap();
        
        super.execute(() -> {
            try {
                if (context != null) {
                    MDC.setContextMap(context);
                }
                command.run();
            } finally {
                MDC.clear();
            }
        });
    }
}

// Sử dụng - không cần làm gì thêm!
ExecutorService executor = new MdcAwareExecutor(5, 10);

MDC.put("requestId", "abc123");
executor.submit(() -> {
    log.info("Task running");  // requestId = abc123 ✓ (tự động!)
});
```

| Option | Code mỗi lần submit | Dễ quên? | Recommend |
|--------|---------------------|----------|-----------|
| Làm tay | Nhiều (5-10 dòng) | ✅ Rất dễ | ❌ |
| Utility wrapper | Ít (`new MdcRunnable()`) | Có thể | ⚠️ OK |
| Custom Executor | Không cần | ❌ Không | ✅ Best |

### ⚠️ Pitfall 3: MDC với @Async

**Vấn đề tương tự:** `@Async` chạy method trên thread khác → MDC bị mất.

```java
@Service
public class OrderService {
    
    public void processOrder(Order order) {
        MDC.put("orderId", order.getId());
        log.info("Start processing");      // orderId = 123 ✓
        
        sendEmailAsync(order);             // Chạy trên thread khác
    }
    
    @Async
    public void sendEmailAsync(Order order) {
        log.info("Sending email");         // orderId = null ✗ (MDC trống!)
    }
}
```

**Giải pháp:** Dùng `TaskDecorator` để tự động copy MDC:

```
┌─────────────────────────────────────────────────────────────────────────┐
│  Không có TaskDecorator                                                  │
│                                                                          │
│  Main Thread                      Async Thread                          │
│  MDC: {orderId: 123}    ──────►   MDC: { }  ← Trống!                   │
│                                                                          │
├─────────────────────────────────────────────────────────────────────────┤
│  Có TaskDecorator                                                        │
│                                                                          │
│  Main Thread                      TaskDecorator         Async Thread    │
│  MDC: {orderId: 123}    ──────►   Copy MDC    ──────►   MDC: {orderId: 123}
│                                                                          │
└─────────────────────────────────────────────────────────────────────────┘
```

```java
// ✅ Tạo custom TaskDecorator để propagate MDC
public class MdcTaskDecorator implements TaskDecorator {
    @Override
    public Runnable decorate(Runnable runnable) {
        Map<String, String> context = MDC.getCopyOfContextMap();
        return () -> {
            try {
                if (context != null) {
                    MDC.setContextMap(context);
                }
                runnable.run();
            } finally {
                MDC.clear();
            }
        };
    }
}

// Cấu hình
@Configuration
@EnableAsync
public class AsyncConfig {
    @Bean
    public TaskExecutor taskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setTaskDecorator(new MdcTaskDecorator());
        executor.initialize();
        return executor;
    }
}
```

### ⚠️ Pitfall 4: MDC với Thread Pool

```java
// ✅ Custom ThreadPoolExecutor tự động clear MDC
public class MdcAwareThreadPoolExecutor extends ThreadPoolExecutor {
    
    @Override
    protected void afterExecute(Runnable r, Throwable t) {
        MDC.clear();  // Auto cleanup sau mỗi task
    }
}
```

### ⚠️ Pitfall 5: Spring Security Context cũng bị mất (tương tự MDC)

**Vấn đề:** Spring Security cũng dùng `ThreadLocal` để lưu `SecurityContext`. Khi tạo child thread → mất authentication info.

```
┌─────────────────────────────────────────────────────────────────────────┐
│  Main Thread                           Child Thread                      │
│                                                                          │
│  SecurityContext:                      SecurityContext:                  │
│  ┌─────────────────────┐              ┌─────────────────────┐           │
│  │ user: "john@abc.com"│   ────►      │ user: null          │ ← Trống!  │
│  │ roles: [ADMIN]      │              │ roles: null         │           │
│  └─────────────────────┘              └─────────────────────┘           │
│                                                                          │
│  MDC:                                  MDC:                              │
│  ┌─────────────────────┐              ┌─────────────────────┐           │
│  │ userId: "U001"      │   ────►      │ (empty)             │ ← Trống!  │
│  └─────────────────────┘              └─────────────────────┘           │
└─────────────────────────────────────────────────────────────────────────┘
```

**Giải pháp:**

#### Option 1: MODE_INHERITABLETHREADLOCAL (chỉ cho thread mới, không cho Thread Pool)

```java
@Configuration
public class SecurityConfig {
    
    @PostConstruct
    public void init() {
        SecurityContextHolder.setStrategyName(
            SecurityContextHolder.MODE_INHERITABLETHREADLOCAL
        );
    }
}
```

> ⚠️ Không hoạt động với Thread Pool (thread được reuse).

#### Option 2: DelegatingSecurityContextExecutor (chỉ SecurityContext)

```java
@Bean
public TaskExecutor taskExecutor() {
    ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
    executor.initialize();
    return new DelegatingSecurityContextAsyncTaskExecutor(executor);
}
```

#### Option 3: TaskDecorator cho cả MDC + SecurityContext ✅ Recommended

```java
public class MdcAndSecurityTaskDecorator implements TaskDecorator {
    
    @Override
    public Runnable decorate(Runnable runnable) {
        // 1. Copy MDC
        Map<String, String> mdcContext = MDC.getCopyOfContextMap();
        
        // 2. Copy SecurityContext
        SecurityContext securityContext = SecurityContextHolder.getContext();
        
        return () -> {
            try {
                // 3. Restore MDC
                if (mdcContext != null) {
                    MDC.setContextMap(mdcContext);
                }
                
                // 4. Restore SecurityContext
                SecurityContextHolder.setContext(securityContext);
                
                // 5. Run task
                runnable.run();
                
            } finally {
                // 6. Cleanup
                MDC.clear();
                SecurityContextHolder.clearContext();
            }
        };
    }
}

// Config
@Configuration
@EnableAsync
public class AsyncConfig {
    
    @Bean
    public TaskExecutor taskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(5);
        executor.setMaxPoolSize(10);
        executor.setTaskDecorator(new MdcAndSecurityTaskDecorator());
        executor.initialize();
        return executor;
    }
}
```

**So sánh các options:**

| Option | MDC | SecurityContext | Thread Pool | Recommend |
|--------|-----|-----------------|-------------|-----------|
| `MODE_INHERITABLETHREADLOCAL` | ❌ | ✅ | ❌ Không work | ❌ |
| `DelegatingSecurityContextExecutor` | ❌ | ✅ | ✅ | ⚠️ Chỉ Security |
| Custom TaskDecorator (cả 2) | ✅ | ✅ | ✅ | ✅ Best |

### Best Practices Summary

| Practice | Mô tả |
|----------|-------|
| **Luôn clear MDC** | Dùng try-finally hoặc Filter |
| **Propagate cho async** | Copy context trước khi submit task |
| **Dùng meaningful keys** | `userId`, `orderId`, không dùng `id`, `key` |
| **Không lưu sensitive data** | Không put password, tokens vào MDC |
| **Set ở entry point** | Filter, Interceptor, Controller |
| **Clean ở exit point** | Filter finally, afterExecute hook |

---

## Query logs với MDC trong CloudWatch

### Log Insights Query

```sql
-- Tìm tất cả logs của 1 request
fields @timestamp, @message
| filter requestId = "abc12345"
| sort @timestamp asc

-- Tìm errors của 1 user
fields @timestamp, @message, userId, requestId
| filter userId = "U001" and level = "ERROR"
| sort @timestamp desc
| limit 100

-- Count requests by user
fields userId
| stats count(*) by userId
| sort count desc
```

### Structured JSON logs

```sql
-- Query JSON logs với MDC fields
fields @timestamp, message, requestId, userId, orderId
| filter orderId = "ORD-123"
| sort @timestamp asc
```

---

## Tài liệu tham khảo

| Chủ đề | Link |
|--------|------|
| **Baeldung MDC Tutorial** | https://www.baeldung.com/mdc-in-log4j-2-logback |
| **SLF4J MDC Javadoc** | https://www.slf4j.org/api/org/slf4j/MDC.html |
| **Logback MDC** | https://logback.qos.ch/manual/mdc.html |
| **Spring Boot Logging** | https://docs.spring.io/spring-boot/reference/features/logging.html |
| **Log4j2 ThreadContext** | https://logging.apache.org/log4j/2.x/manual/thread-context.html |

---

*Ngày tạo: 2026-01-18*
*Project: realworld-exam*
