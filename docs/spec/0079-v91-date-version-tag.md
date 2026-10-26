# spec 0079 — V25 merge 自动日期版本 tag + 页面版本号/footer(YPJ-101)

> 状态:**draft 待用户过目**。来源:2026-10-25 用户拍板——版本策略 C(日期版本,`vYYYY.MM.DD+N`,同日多 merge 加计数,全程免维护);版本号须显示在页面;补 footer `© Jiang Chuanwei`。

## 1. 需求

### 现状与问题

- `release-yz.yml` 由**手动**打的 `yz-v*` tag 触发 CLI 发版——没有任何自动打 tag 机制。
- 页面无版本号、无版权 footer。
- 用户要求:每次 PR merge(main push)自动生成日期版本 tag;版本号在页面上可见;底部版权信息。

### 范围

- 新 workflow:main push 自动打 `vYYYY.MM.DD[.N]` tag。
- 后端:`GET /api/version` 暴露版本号(构建期从 git 注入)。
- 前端:根布局加 Footer(版权 + 版本号)。

### 非目标

- 日期 tag **不触发** CLI 发版(前缀区隔:`yz-v*` 归 release-yz,`v*` 仅做版本标记);两者联动将来需要再说。
- 不做 CHANGELOG 生成、不做 semver 判断。

## 2. 详细设计

### 2.1 `.github/workflows/date-tag.yml`(新)

```yaml
on:
  push:
    branches: [main]
  workflow_dispatch:          # 本地可手动触发验证
permissions: { contents: write }
```

- checkout `fetch-depth: 0`(要读已有 tag)。
- 计算:`TODAY=$(date -u +%Y.%m.%d)`;`N=$(git tag -l "v$TODAY*" | wc -l)`;N=0 → `v$TODAY`,否则 `v$TODAY.$((N+1))`。
- `git tag` + `git push origin $TAG`。`concurrency: date-tag` 防并发 merge 竞态(单 merge 场景冗余,便宜保险)。
- 时区用 UTC,接受"北京时间晚上打的 tag 日期差一天"的边缘(免维护优先,不引 tz 依赖)。

### 2.2 后端版本注入 + 端点

- `backend/api/src/main/resources/version.txt`(新):内容占位 `__VERSION__`;`api/build.gradle.kts` 的 `processResources` 用 `git describe --tags --abbrev=0`(closest tag)展开,git 不可用/shallow clone 时落 `dev`(expand 失败不炸构建)。
- `general/VersionController.kt`(新):`GET /api/version` → `{"version":"v2026.10.26"}`;启动时读一次 classpath `version.txt` 缓存(值构建期不变,无需每次 IO)。
- `backend/script/api.http`:general 区新增强点 + 负例区不需要(无参只读)。

### 2.3 前端 Footer

- `web/components/Footer.tsx`(新,client):渲染 `© Jiang Chuanwei · {version}`;`useEffect` fetch `/api/version`,失败显示 `dev`。
- 文案按规矩外置:`web/lib/texts.ts` 加 `footerCopyright` / `footerVersionFallback`。
- 挂在 `web/app/layout.tsx`:`{children}` 之后,全站可见(含 login 页;登录页 fetch 401 → 显示 fallback,可接受)。

### 2.4 否决的备选

- **semver patch 自动递增**:需维护 minor 语义,违背"全程免维护"拍板。
- **版本号走 env(`YANGZHOU_VERSION`)**:bootRun 本地要手工传 env,易忘;构建期注入零维护。
- **前端 build 时读 git tag**:web 独立构建目录(.next-e2e)易缓存过期;版本真相源收敛在后端一处。

## 3. 手工测试用例(用户 build/debug 阶段执行)

1. `gradle :api:bootRun`(8080/8081 皆可)→ 浏览器任意页面底部见 `© Jiang Chuanwei · v<最新tag 或 dev>`;curl `GET /api/version` 值一致
2. 打一个本地 tag(`git tag v2026.10.26-test`)重启 bootRun → 版本号变为该 tag;删 tag 恢复 dev(验证注入链)
3. `gradle :api:test` 全绿(VersionController 单测:有值/占位兜底)
4. workflow 验证:PR 合并后 Actions 出现 date-tag run;`git tag -l "v2026*"` 见新 tag;同日二次 merge 得 `.2` 后缀(workflow_dispatch 可先手动验证)

## 4. As-built(2026-10-26,PR 待审)

- 偏离 spec 一处:`/api/version` 在 SecurityConfig **permitAll**(spec 未提)——版本号要在登录页也可见,走带 auth 的 api() 会触发 401 跳转死循环风险;版本号非敏感,直接放行。
- Gradle 注入实现细节:`expand` 占位符是 `${version}`(非 `__VERSION__`);`git describe --match "v*"` 只认日期 tag(yz-v* 不算);configuration cache 下 `filesMatching` lambda 不得捕获脚本实例(顶层 val 需局部化),踩了两坑记录在此。
- 验证实测:单元/集成/全 build 全绿;本仓尚无 v* tag → version.txt 当前为 `dev`,首个 date-tag 合入后即显示日期版本;web tsc 无新增报错(基线 5 个 e2e 历史报错与本票无关)。
