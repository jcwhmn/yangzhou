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

## As-built(2026-11-06,PR #188)

- **落地**:`web/lib/api.ts` `fetchWithRetry`(TypeError/502/504 退避 1s→2s,耗尽 `setDown()` + `ApiError(0,"后端不可达")`;有响应即 `setUp()` 自愈,含 4xx/5xx;abort 等非网络层异常原样抛)/ `web/lib/connection-store.ts`(模块级 pub/sub,照 notifications-store 模式,无定时器)/ `web/components/ConnectivityBanner.tsx`(useState+useEffect 订阅,挂 `app/layout.tsx` 根)/ `texts.ts` `connection.down` / `e2e/connectivity.spec.ts` 3 例(1 例 @smoke)
- **设计修正(偏离 §二原文,勿回退)**:原文「不动 bootstrapOrLogin(自身已有明确报错)」——手工验证实锤不成立:其裸 fetch 网络层失败抛原始 TypeError,原文 "Failed to fetch" 被登录页 `setError(err.message)` 透出,且不 `setDown`(登录页横幅不亮);「明确报错」仅对 HTTP 错误响应成立。已接入 `fetchWithRetry`,登录页断连 = 横幅 + 中文报错,新增 e2e 断言 "Failed to fetch" 零出现
- **旁路确认不改**:登录页内层 fetch(`catch {}` 静默)/ board 页 last-project(`catch(() => {})`)/ Footer(catch 回落版本号)/ exportXlsx(调用点无 catch,断连 = 无反应,横幅兜底)——均不透英文原文
- **验证**:e2e 全量 72/72 绿(两轮);`tsc` 0 错;`prettier --check .` 全仓过;后端零改动。手工验收(dev 容器):stop api → 数秒横幅,start api → 自愈 ✅。首轮全量 project-settings 1 例并行偶发挂,单跑 + 二轮全量均绿,与本票无路径交集(若 nightly 再挂同例另开票)
- **已知边界**:502/504 一律视为连接失败——后端若未来主动返回 502/504 业务语义会被重试 ~3s 再报错(现状不存在);断连时页面级报错延迟 ~3s,与横幅「正在自动重试」语义一致
- **残余(有意不修,横幅兜底)**:断连时看板页「项目未配置组」info 条与错误 Alert 同屏并存(error 态渲染条件未排 error)——0104 拍板「页面级零散 catch 不改」,若后续观感不佳再议
