# spec 0055 — V72 API 错误日志可见性:失败请求必须落 log

> 票:YPJ-72(用户定:优先)。现状:GlobalExceptionHandler 只有 catch-all 5xx 落 ERROR,4xx 各 handler 全静默——失败排障只能靠响应体。

## 1. 需求

API 调用失败在服务端必须有日志:5xx 带完整栈(ERROR),4xx 至少 WARN 一行,不开 debug 也能查。

**范围**:`GlobalExceptionHandler` 统一出口一处改动,全覆盖。

**非目标**:不加 MDC/关联 ID;不动 AOP 访问日志;不区分业务异常细分文案。

## 2. 详细设计

### 2.1 `error()` 助手内统一 WARN

4xx/5xx 中经 `error()` 的 handler(NotFound/Conflict/integrity/badRequest/unauthorized/methodNotSupported/internal)自动获得日志:

```kotlin
private fun error(status, code, message, req): ResponseEntity<ApiError> {
    if (status.is5xxServerError) log.error("API {} {} path={} msg={}", status, code, req.requestURI, message)
    else log.warn("API {} {} path={} msg={}", status, code, req.requestURI, message)
    return ResponseEntity.status(status).body(ApiError(code, message, req.requestURI))
}
```

- internal 的 catch-all 已有带栈 `log.error`,保留(双条 ERROR 可接受:一条带栈一条结构化;或删 internal 内那条——**定:删**,由 `error()` 统一出一条,栈通过 `error()` 增可选 `e` 参数带出)
- 实际签名:`error(status, code, message, req, e: Throwable? = null)`;5xx 时 `log.error(..., e)` 带栈,4xx WARN 不带栈
- **internal handler 改为** `error(INTERNAL, "INTERNAL", "内部错误", req, e)`,消息不泄内部信息

### 2.2 validation 单独 WARN

`MethodArgumentNotValidException` handler 不走 `error()`(有 fields),补一行 `log.warn("API 400 VALIDATION path={} fields={}", req.requestURI, fields)`。

### 2.3 否决备选

- AOP 访问日志层记失败:重复记录(ExceptionHandler 已覆盖),两处维护
- 每个 handler 手写 log:样板重复

## 3. 手工测试用例

1. 任意 500(如停 Postgres 后调 API):日志见 ERROR 带栈
2. 指派池外成员 409:日志见 WARN 一行(含 path 与 msg)
3. 建表单缺字段 400 VALIDATION:日志见 WARN 带 fields

## 4. 单元测试(单测优先)

`GlobalExceptionHandlerTest`(纯单测,不走 Spring):logback `ListAppender` 挂 logger,直调 handler(HttpServletRequest 用匿名实现/stub),断言:5xx → ERROR 事件且 throwable 非空;409/400 → WARN 事件;VALIDATION → WARN。

## 5. As-built

(完工后补)
