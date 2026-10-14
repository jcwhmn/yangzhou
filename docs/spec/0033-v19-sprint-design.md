# 0033 V19 Sprint 整体设计(快赢+地基)

> sprint 群:YPJ-41/42/43/44/37(44 已 Done,#103)。本文件是 **伞设计**:交代 sprint 目标、票间关系与各票设计方向;
> 每票开工仍按惯例出各自 spec 三件套(增量节),以各票 spec 为实现真相源。
> 排期镜像:`docs/planning/roadmap.md` §二。

## 一、Sprint 目标(三条线)

1. **Flow 地基**(YPJ-44,已交付):item 级状态迁移审计——V20 全部指标的数据前提。
2. **快赢**(YPJ-41/42/37):三张小票,清理 dogfood 中实证的交互/管线毛刺,都不碰 domain。
3. **质量地基**(YPJ-43,滚动票):CI 单元防回归网从近零建到有意义,贯穿本 sprint 与后续。

**非目标**:Flow 的查询/展示(V20)、动态类型(V21)、任何 REST 契约变更(本 sprint 三张小票均为既有端点/页面收敛,api.http 预计不动)。

## 二、已交付:YPJ-44(spec 0032,PR #103)

新表 `item_status_history`(from 可空=初始进入,source=created/manual/github,actor 空=系统);写入点 3 处(create / update no-op 守卫 / applyEvent);V31 从 item_activity 回填并保留原迁移时刻。PM 库实证:2026-10-06 起历史时点保留。**给 V20 的接口承诺**:表按 (item_id, created_at) 有序,消费一律走 repository 派生查询,不直接写 SQL 聚合(聚合形态 V20 spec 定)。

## 三、设计方向:YPJ-41 Sprint 页表格化(移除看板)

- **问题**:sprint 是时间盒(有界集合+燃尽语义),看板是流式列语义——当前 sprint 页同时提供两视图,语义重复且维护两套。
- **方向**:Sprint 页保留表格视图(存量实现),移除看板视图与切换入口;看板语义统一归项目级看板(spec 0023:active sprint items 并集)。
- **涉及面**:web sprint 页面结构(视图 tab / 切换态);不动后端 board 端点(项目级看板仍消费它)。
- **验收(票面)**:Sprint 页仅表格;看板入口移除。
- **边界**:burndown 不在本票(V20 YPJ-46)。

## 四、设计方向:YPJ-42 提交历史按需加载

- **问题**:`yz log` / commits 端点全量拉分支提交;大仓噪音大、耗时长。
- **方向**:`item_git_ref` 已记分支创建时刻(YPJ-27 起)——commits 查询按 `ref.createdAt` 之后过滤;CLI 与 web 详情页透传同一语义。GitHub 侧用 since 参数(能力已有)优先,响应内过滤兜底。
- **涉及面**:`GithubCommitService` 查询路径;CLI `log` 命令;无 schema 变更。
- **验收(票面)**:commits 结果不含分支创建之前的提交。

## 五、设计方向:YPJ-37 分支自动命名

- **问题**(dogfood 实测):web 建分支预填 slug 对 CJK 标题产出 ASCII 残迹(`YPJ-27-yz-wrapper-dev-dev-…-j`),且缺 `cwjiang/` 前缀,每次手改。
- **方向**(三选一的伞建议,票内三件套定稿):**以 CLI `items branch` 显式命名为正路径**(已落地、命名精确),web 预填退化为极简安全默认 `cwjiang/YPJ-{N}`(CJK 剥除后过短/为空即退票号,不再拼 slug 残迹)。后端 createBranch 兜底校验仅做合法性(字符集/长度),不做语义修正。
- **涉及面**:web 预填 useEffect(`web/app/p/[key]/i/[itemId]/page.tsx` ~L1014);`yz` CLI 文档措辞;后端校验可选加固。
- **验收(票面)**:CJK 标题无语义残迹/超长碎片;默认名含约定前缀。

## 六、设计方向:YPJ-43 后端单测加固(滚动票)

- **问题**:CI(github action)只跑 `gradle test`,unit 面近零(4 个 domain 引擎测试),服务层逻辑回归等 nightly 兜底。
- **方向**:按 `.agents/skills/backend-testing` 需求矩阵滚动补:
  1. domain 引擎纯函数全覆盖(5 形态判定 + 绿黄红聚合规则)——优先;
  2. api 各垂直切片 service 主业务分支 focused 单测(匹配引擎 service、item/project 写路径先行);
  3. repo 自定义 SQL 按需(普通 CRUD 派生查询不写,矩阵既定)。
- **约束**:类级并行安全(无共享可变态/不碰 DB/网络/不起 Spring);本票不改测试约定双轨边界(mockk 修订属 YPJ-38 范畴,若 triage 决定并入则先改 AGENTS 再动代码)。
- **验收(票面)**:各切片 service 主路径有 focused 单测;domain 覆盖 5 形态与聚合规则;CI 绿且单元数有意义。

## 七、顺序与集成

- **建议序**:41 → 42 → 37(三张独立小票,可乱序,互不依赖);43 穿插滚动,每票收尾带一笔。
- **共同集成面**:无——三张小票分属 web sprint 页 / github commits / web 预填,唯一交集是 37 与 42 都 touch `yz`/branch 语义,验收时互测一遍 CLI 路径即可。
- **sprint 收口**:全票 Done → V19 组 completed,主动提醒 handoff。
