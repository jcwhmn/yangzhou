# 0104 — 前端 API 不可达 UX:静默重试 + 全局报错横幅(YPJ-153)

> 来源:YPJ-152 验收事故(2026-11-06):`rebuild prod` 重启 api 的十几秒里,前端把「后端不可达」渲染成空态(侧栏 Sprint 段隐藏、列表空),用户误以为数据全丢。用户拍板:**数据拿不到,可以静默重试,或报错,但不能显示空数据**。

## 一、需求

- 后端不可达(网络层失败/代理 502)时:先**静默重试**(退避 2 次);仍失败则**全局横幅明确报错**「后端不可达,数据可能不完整,正在自动重试」。
- 任一后续请求成功 → 横幅自愈清除(无需轮询)。
- 业务错误(4xx/5xx 带 message)路径**完全不变**——本票只管「连不上」。
- 页面级零散 `catch(() => setEmpty)` 不改(横幅承担真相告知;逐页改造是过度工程)。

## 二、详细设计(单 seam:`web/lib/api.ts`)

- `api()` 内 fetch 前包重试:网络层异常(TypeError/代理 502/504)→ 等 1s 重试 → 再等 2s 重试 → 仍失败:
  - `connectionStore.setDown()`(模块级 pub/sub,模式照 `notifications-store.ts`)
  - 抛 `ApiError(0, "后端不可达")`(页面 catch 行为不变)
- 任一请求**成功**(含 401 之外的任何响应)→ `connectionStore.setUp()` 自愈。
- 401 跳登录路径不受重试影响(只对连接失败重试)。
- 新组件 `components/ConnectivityBanner.tsx`:订阅 connectionStore;down 时渲染 MUI Alert `severity="warning"` 常驻页面顶部(文本入 `texts.ts`:「后端不可达,数据可能不完整——正在自动重试…」);挂载点 = `app/layout.tsx` 根(登录页也覆盖:bootstrap 失败时同样提示)。
- **不动**:notifications-store、401 逻辑、bootstrapOrLogin(自身已有明确报错)。

## 三、测试

- e2e 新 spec `connectivity.spec.ts`:`page.route("**/api/**", abort)` 模拟后端不可达 → 首页断言横幅出现;`unroute` + 触发任一请求(reload)→ 横幅消失(自愈)。不依赖真停后端,CI 可跑。
- 手工测试用例:
  1. `docker stop yz-prod-api-1` → 打开任意页 → 数秒内出现横幅;`docker start` → 横幅自愈
  2. 正常使用全程无横幅(回归)

## Out of Scope

- 逐页 loading skeleton / error boundary 改造;断线期间禁写操作(写失败本就有页面级报错)。
