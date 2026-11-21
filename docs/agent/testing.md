# testing — 测试政策与 SOP

> 由 AGENTS.md §真相源 触发加载:写或跑任何测试(单元/集成/E2E/api.http)、手工验收前必读。

## 后端测试政策

**硬规则细则以 skill `.agents/skills/backend-testing/SKILL.md` 为唯一真相源**(本节只列政策,不复制细则):单元 `src/test` 与集成 `src/integrationTest` 物理二分(YPJ-100/spec 0078),目录即边界;唯一 seam = REST API 黑盒,集成必须 extends `AbstractApiTest`;CI 只跑单元,集成 merge 前本人本地 `gradle integrationTest`(nightly 兜底);`gradle build` 不跑集成(已与 check 解耦)。

## E2E(web/;Playwright,并行按用例隔离)

- 后端跑 **test 容器**(库 yangzhou_test):`script/app-infra.ps1 up test`(api http://localhost:8082);调试改码可换 bootRun test profile,须 `--server.port=8082`;前端由 webServer 拉起 prod server(`next build && next start`,独立构建目录 .next-e2e,不踩日常 dev 的 .next);凭证与地址在 `web/e2e/.env`(模板 `.env.example`),不硬编码不提交。
- 标准全量跑法(web/ 下):`E2E_FRONT_PORT=3002 E2E_BASE_URL=http://localhost:3002 E2E_API_URL=http://127.0.0.1:8082 BACKEND_URL=http://127.0.0.1:8082 npx playwright test`;globalSetup 自带**清测试库**(残渣 flake 解法;Fast Refresh 类由 prod server 根除,spec 0021 As-built),勿绕过;BACKEND_URL 必须 8082(test 容器;指 8080 会打 prod 容器、污染 PM 库);手动清库 `npm run db:reset`
- CI e2e.yml 只跑 **@smoke 冒烟**(`npx playwright test --grep "@smoke"`;grep 不作用于依赖项目,auth 3 例全跑提供登录前提,实跑 ~12 例);完整套件 = merge 前本地全量,跑法不变(spec 0015)
- 用例按模块分文件(一个功能域一个 `*.spec.ts`),`fullyParallel`;每用例经 fixture 独享唯一 key 项目(API 预置),**不依赖其它用例留下的状态,可单跑**。
- 用例内只对被测行为走 UI;数据准备走 API helpers(`e2e/support/helpers.ts`)。MUI 对话框淡出期(~200ms)会吞下层点击,断言/点下层前先 `expect(dialog).toBeHidden()`。

## api.http 与测试环境纪律(2026-11-08 拍板,0113 三轮复盘,详见 docs/planning/2026-11-08-apihttp-0113-复盘.md)

api.http 与 e2e 等自动化只在 test(8082)跑;dev 只做手工 UI 测试,含准真实数据,禁自动清、禁自动化指 dev。api.http 新增节必须自含(节内自建前置,变量后缀隔离)或文件头声明整文件按序跑;交付用户前先脚本逐请求复刻全绿。用户报错先确认「哪个入口/环境/有无新产物」再归因;e2e 归因纪律见 `troubleshooting.md`。

## 手工验收

流程与勾选纪律见 `development.md` §手工测试用例落档;环境前提见 `environment.md` 三 profile。
