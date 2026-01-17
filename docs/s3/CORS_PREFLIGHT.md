# CORS & Preflight Request

Giải thích cơ chế Cross-Origin Resource Sharing và Preflight request trong browser.

---

## CORS là gì?

**CORS (Cross-Origin Resource Sharing):** Cơ chế cho phép browser từ domain A gọi API/resource từ domain B.

**Vấn đề:** Browser block request cross-origin mặc định (security).

```
┌─────────────────────────────────────────────────────────────────┐
│  Không có CORS                                                  │
├─────────────────────────────────────────────────────────────────┤
│                                                                 │
│  https://myapp.com                      https://api.other.com   │
│  ┌──────────────┐     GET /data         ┌──────────────┐       │
│  │   Browser    │ ─────────────────▶    │    Server    │       │
│  └──────────────┘         ❌            └──────────────┘       │
│                     CORS Error!                                 │
│                                                                 │
└─────────────────────────────────────────────────────────────────┘
```

---

## Origin là gì?

**Origin = scheme + host + port**

| URL A | URL B | Same origin? |
|-------|-------|--------------|
| `http://localhost:3000` | `http://localhost:8080` | ❌ Khác port |
| `http://myapp.com` | `https://myapp.com` | ❌ Khác scheme |
| `https://myapp.com` | `https://api.myapp.com` | ❌ Khác host |
| `https://myapp.com/app` | `https://myapp.com/api` | ✅ Cùng origin |

---

## Preflight Request là gì?

**Preflight = OPTIONS request** được browser gửi **tự động** để hỏi server: "Tao có được phép gửi request này không?"

### Tại sao cần Preflight?

```
┌─────────────────────────────────────────────────────────────────┐
│  VẤN ĐỀ: Request nguy hiểm có thể thay đổi data trên server     │
├─────────────────────────────────────────────────────────────────┤
│                                                                 │
│  Hacker's website                          Your API Server      │
│  (evil.com)                                                     │
│  ┌──────────────┐     DELETE /users/123    ┌──────────────┐    │
│  │   Browser    │ ─────────────────────▶   │    Server    │    │
│  │  (victim)    │                          │              │    │
│  └──────────────┘                          └──────────────┘    │
│                                                                 │
│  Nếu không có preflight → Request đã được GỬI và XỬ LÝ!        │
│  User bị xóa rồi, dù response bị block cũng không cứu được     │
│                                                                 │
└─────────────────────────────────────────────────────────────────┘
```

### Preflight giải quyết như thế nào?

```
┌─────────────────────────────────────────────────────────────────┐
│  CÓ PREFLIGHT                                                   │
├─────────────────────────────────────────────────────────────────┤
│                                                                 │
│  Step 1: Browser hỏi trước (OPTIONS)                            │
│  ┌──────────┐                              ┌──────────┐        │
│  │ Browser  │ ── OPTIONS: "Tao từ evil.com │  Server  │        │
│  │          │    được DELETE không?" ────▶ │          │        │
│  │          │ ◀─── "KHÔNG, chỉ myapp.com   │          │        │
│  │          │       mới được phép" ─────── │          │        │
│  └──────────┘                              └──────────┘        │
│                                                                 │
│  Step 2: Browser DỪNG, không gửi DELETE                         │
│  → Data an toàn, không bị xóa!                                  │
│                                                                 │
└─────────────────────────────────────────────────────────────────┘
```

---

## Simple vs Non-Simple Request

### Simple Request (KHÔNG có preflight)

```
┌─────────────────────────────────────────────────────────────────┐
│  Điều kiện để là Simple Request:                                │
├─────────────────────────────────────────────────────────────────┤
│                                                                 │
│  ✅ Method: GET, HEAD, hoặc POST                                │
│  ✅ Headers chỉ có: Accept, Accept-Language, Content-Language,  │
│                    Content-Type (với giá trị đơn giản)          │
│  ✅ Content-Type chỉ là:                                        │
│     - application/x-www-form-urlencoded                         │
│     - multipart/form-data                                       │
│     - text/plain                                                │
│                                                                 │
│  → Browser gửi thẳng, KHÔNG có OPTIONS                          │
└─────────────────────────────────────────────────────────────────┘
```

### Non-Simple Request (CÓ preflight)

```
┌─────────────────────────────────────────────────────────────────┐
│  Khi nào là Non-Simple:                                         │
├─────────────────────────────────────────────────────────────────┤
│                                                                 │
│  ❌ Method: PUT, DELETE, PATCH                                  │
│  ❌ Custom headers: Authorization, X-Custom-Header, ...         │
│  ❌ Content-Type: application/json                              │
│                                                                 │
│  → Browser gửi OPTIONS trước                                    │
└─────────────────────────────────────────────────────────────────┘
```

### Ví dụ

| Request | Preflight? | Lý do |
|---------|------------|-------|
| `GET /image.jpg` | ❌ Không | Simple GET |
| `POST form-data` | ❌ Không | Simple POST + simple Content-Type |
| `PUT /file.jpg` | ✅ Có | PUT là non-simple |
| `POST application/json` | ✅ Có | Content-Type không đơn giản |
| `GET` với `Authorization` header | ✅ Có | Custom header |

---

## Preflight Flow Chi Tiết

```
┌─────────────────────────────────────────────────────────────────┐
│  Step 1: Preflight (tự động, browser làm)                       │
├─────────────────────────────────────────────────────────────────┤
│                                                                 │
│  OPTIONS /api/users/123                                         │
│  Headers:                                                       │
│    Origin: https://myapp.com                                    │
│    Access-Control-Request-Method: DELETE                        │
│    Access-Control-Request-Headers: Content-Type, Authorization  │
│                                                                 │
│  Server Response:                                               │
│    Access-Control-Allow-Origin: https://myapp.com               │
│    Access-Control-Allow-Methods: GET, POST, PUT, DELETE         │
│    Access-Control-Allow-Headers: Content-Type, Authorization    │
│    Access-Control-Max-Age: 3600  ← Cache preflight 1 giờ        │
│                                                                 │
└─────────────────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────────────────┐
│  Step 2: Actual Request (nếu preflight OK)                      │
├─────────────────────────────────────────────────────────────────┤
│                                                                 │
│  DELETE /api/users/123                                          │
│  Headers:                                                       │
│    Origin: https://myapp.com                                    │
│    Authorization: Bearer xxx                                    │
│                                                                 │
└─────────────────────────────────────────────────────────────────┘
```

---

## Framework xử lý OPTIONS tự động

Bạn không cần viết OPTIONS controller vì **framework tự xử lý**.

### Spring Boot

```
┌─────────────────────────────────────────────────────────────────┐
│  Request flow trong Spring Boot                                 │
├─────────────────────────────────────────────────────────────────┤
│                                                                 │
│  OPTIONS /api/users/123                                         │
│         │                                                       │
│         ▼                                                       │
│  ┌──────────────────┐                                          │
│  │   CORS Filter    │  ← Spring tự động xử lý OPTIONS          │
│  │  (tự động thêm)  │    Trả về headers, KHÔNG vào Controller  │
│  └──────────────────┘                                          │
│         │                                                       │
│         ▼                                                       │
│  ┌──────────────────┐                                          │
│  │   Controller     │  ← Chỉ nhận GET, POST, PUT, DELETE       │
│  │  @DeleteMapping  │                                          │
│  └──────────────────┘                                          │
│                                                                 │
└─────────────────────────────────────────────────────────────────┘
```

### Config CORS trong Spring Boot

```java
// Cách 1: Annotation trên controller
@CrossOrigin(origins = "http://localhost:3000")
@RestController
public class UserController { ... }

// Cách 2: Global config (phổ biến hơn)
@Configuration
public class CorsConfig implements WebMvcConfigurer {
    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/**")
                .allowedOrigins("http://localhost:3000")
                .allowedMethods("GET", "POST", "PUT", "DELETE");
    }
}
```

---

## Nhiều API cùng URL khác Method

Preflight **không cần biết** controller nào! Nó chỉ hỏi: "Method này có được phép không?"

```
┌─────────────────────────────────────────────────────────────────┐
│  Controller có nhiều methods cùng URL:                          │
├─────────────────────────────────────────────────────────────────┤
│                                                                 │
│  @GetMapping("/users/{id}")     // GET /users/123               │
│  @PutMapping("/users/{id}")     // PUT /users/123               │
│  @DeleteMapping("/users/{id}")  // DELETE /users/123            │
│                                                                 │
└─────────────────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────────────────┐
│  Preflight request:                                             │
├─────────────────────────────────────────────────────────────────┤
│                                                                 │
│  OPTIONS /users/123                                             │
│  Headers:                                                       │
│    Access-Control-Request-Method: DELETE   ← "Tao muốn DELETE"  │
│                                                                 │
│  CORS Filter:                                                   │
│  1. Đọc header "Access-Control-Request-Method: DELETE"          │
│  2. Check config: DELETE có trong allowedMethods không?         │
│  3. Trả về: "OK, DELETE được phép"                              │
│                                                                 │
│  → KHÔNG cần biết controller nào, chỉ check config              │
│                                                                 │
└─────────────────────────────────────────────────────────────────┘
```

---

## Tóm tắt

| Khái niệm | Giải thích |
|-----------|------------|
| **CORS** | Cơ chế cho phép cross-origin requests |
| **Origin** | scheme + host + port |
| **Preflight** | OPTIONS request hỏi phép trước |
| **Simple Request** | GET/HEAD/POST đơn giản → không preflight |
| **Non-Simple** | PUT/DELETE/custom headers → có preflight |
| **Ai gửi OPTIONS** | Browser tự động |
| **Ai xử lý OPTIONS** | Framework (Spring, Express...) tự động |

---

## Official Documentation

- [MDN - CORS](https://developer.mozilla.org/en-US/docs/Web/HTTP/CORS)
- [MDN - Preflight Request](https://developer.mozilla.org/en-US/docs/Glossary/Preflight_request)
- [Fetch Spec - CORS Protocol](https://fetch.spec.whatwg.org/#http-cors-protocol)

---

*Document created: 2026-01-15*
*Project: realworld-exam*
