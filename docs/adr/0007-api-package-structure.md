# ADR-0007 — api 模块业务/非业务分包

日期:2026-10-23 · 票:YPJ-71 · 状态:已接受

## 背景

api 模块最初按垂直切片扁平组织(ADR-0004):业务切片与 config/support 混在 `yangzhou.api` 一级。切片数量增长到 30 个后,业务与非业务无包级边界,依赖方向不显式。

## 决策

```text
yangzhou.api
├── YangzhouApplication.kt     # 扫描根,不动
├── business.<slice>/          # 全部业务垂直切片(实体+DTO+service+controller 同包规则不变)
├── config/                    # 纯 Spring 装配:@Configuration / @ConfigurationProperties
├── security/                  # 认证授权基础设施:SecurityConfig / JwtAuthFilter / JwtService
├── general/                   # 非业务横切处理:AccessLogFilter
└── error/                     # 错误形状与映射:GlobalExceptionHandler / exceptions.kt
```

归类判据:

- **business**:有对应 REST 资源/业务用例的垂直切片;
- **config**:启用某基础设施的技术装配类(无业务逻辑);
- **security**:JWT 签发、请求过滤、过滤链等认证授权机制;
- **general**:与业务无关的横切技术处理;
- **error**:业务异常定义 + 全局错误映射。

边界裁决:

- `auth` 切片(登录/注册/刷新端点)归 **business**——是业务用例;其依赖的 JWT 机制归 security;
- `AttachmentProperties` 归 **config**——配置定义不随业务切片走;
- `GlobalExceptionHandler` 不留 config——config 从此只有装配类。

## 约束

- 纯移动 + import 修正,零行为变更;REST 路径/Bean 装配语义/配置键全不变;
- domain / persistence / cli / web 不动;
- 新业务切片一律进 `business.<slice>`;新非业务代码按判据归位,不得回填 config。
