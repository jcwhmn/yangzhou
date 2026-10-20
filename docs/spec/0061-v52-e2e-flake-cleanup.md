# 0061 — YPJ-52 E2E 并行 flake 收尾:repeat-each=3 高频跑归因,修实锤、记档偶发

> 状态:**已修复待用户 debug**(TC 见 §3;PR 前)
> 票:YPJ-52(V22 收尾最后一张)· 影响:web/e2e 两用例(纯测试侧,零组件/零后端改动)

## 1. 需求

### 背景

E2E 全量并行跑时历史上有「不同用例轮换失败」的 flake 现象(全局 me/成员池共享状态 + 4 worker 负载,本地 retries=0)。V22 期间已顺手修掉两个实锤根因(PR #129,已合 main):

1. **number-jump pre-hydration 竞态**:AppNav 受控跳转框 SSR 直出,hydration 前输入被首轮渲染抹掉 → `AppNavClient.tsx` 改 dynamic(ssr:false) 客户端-only;
2. **工时建议「从GITHUB生成」偶发 500**:网络抖动 IOException 原样上抛 → `wrap()` 翻译为 400 可重试;顺带修 PR 回溯老 PR 漏检(`findPrByHead` 平台侧过滤)。

### 本票目标

- `--repeat-each=3` 全量高频跑(156 用例次),收集**残留失败**;
- 逐个归因:有实锤根因 → 小修 + 测试;归因不出/未复现 → 记档 As-built 已知残留清单;
- 关票条件:repeat-each=3 全绿,或残留均有明确归因与处置。

### 非目标

- 不改 CI 策略、不动 workers/retries 配置;
- 不重写用例隔离架构(fixture 唯一 key 项目、数据 API 预置的现状保持);
- 不为「理论并发」加防御性等待。

## 2. 调查结果与修复(实测归因)

高频跑(2026-10-20)结果:**4 failed / 152 passed**,归因出**两类新实锤根因**,均已修复:

### 根因 A:date-input「draft 不外泄」3/3 确定性失败 —— 日期敏感 × React value-tracker 去重

**现象**:日段显示 12/20/2026 完整,但 `data-value=""` 恒不变;`年.blur()` 无效。

**取证链**(trace 截屏帧 + 三轮 probe 用例,init script 包装 focus()/focusin/focusout/beforeinput 记时序):

1. `dd.fill("20")` 后**无任何 focus 调用**、无 focusout/focusin、activeElement 停在日段;
2. 对照实验:同状态改 fill("21")(值变)→ 正常跳年段;键盘键入 2、0(同值覆写)→ 正常跳年段;
3. 唯一区分变量:**目标值与日段现值相同**。

**机制**:空值聚焦时 DateInput 预填今天(probe 当天 = 2026-10-**20**)→ 日段已是 "20";`fill("20")` 同值覆写 → DOM value 未变 → **React value-tracker 判定无变化,onChange/onBeforeinput 均不派发** → §7.1 满段跳年段永不触发 → 焦点留在日段 → 测试 `年.blur()` 落在未聚焦元素上是 no-op → commit 链断。**此用例只在每月 20 号确定性失败**(真实用户键盘路径走 keydown 拦截 writeDigit,不依赖 change 事件,同值也跳段,不受影响)。

**修复**(`web/e2e/date-input.spec.ts`):日段改键盘写入 `pressSequentially("20")`,并注释根因。键盘路径与 PRD 用户语义一致,且对所有日期稳健。

### 根因 B:milestone 空态 1/3 —— 点击落在 hydration 完成前被吞

**现象**:点「管理」后 5s 内 `getByRole("dialog")` 无内容;失败快照里 AppNav 跳转框已有 `KEY-` 预填。

**归因**:跳转框预填是 hydration 后 useEffect 的产物 → 证明点击发生在 hydration 完成前(RSC 按钮无 onClick),点击被吞、对话框永不打开;hydration 稍后完成,留下预填痕迹。这是全 suite 已知 flake 类:**release.spec.ts 已有完全相同的注释与 toPass 重试先例**,milestone 未覆盖到。

**修复**(`web/e2e/milestone.spec.ts`):按 release.spec 先例,click + 弹层断言包进 `expect(...).toPass({ timeout: 10_000 })` 重试。

### 排除项(记录备查)

- AppNav 跳转框 `KEY-` 预填 = YPJ-63 特性(`/p/{key}` 页内只输编号),非 bug;
- `fillDate` 助手不受根因 A 影响:逐段显式 `focus()` + 结尾显式 `年.blur()`,不依赖自动跳段。

### 终验

修复后全量 `--repeat-each=3`:**156/156 全绿**(3.5m,本地 4 workers)。

### 否决的备选

- 调大 retries 掩盖 → 违背「retries=0 暴露问题」的调查目的;
- 串行跑绕开并发 → 失去对并行 flake 的检验能力;
- 组件侧「同值 fill 也跳段」防御 → 无事件可依(框架语义),且真实用户路径不受影响,不做投机抽象。

## 3. 手工测试用例(用户执行)

| # | 步骤 | 期望 |
|---|---|---|
| TC1 | 标准命令全量(web/ 下,env 照 AGENTS) | 全绿 52/52 |
| TC2 | 同上加 `--repeat-each=3` | 全绿 156/156 |

> 注:根因 A 的原始现象只在每月 20 号复现;修复后任何日期均稳健(键盘路径不依赖 change 事件)。
