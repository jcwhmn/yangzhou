# Milestone 与 Release PRD

> 版本 1.1(2026-11-12;v1.1 = Release 独立实体修正)· 状态:待评审 · 上游:`ItemGroup PRD.md`(细化其 §7.3/§8/§9–12/§24–26 相关节,冲突处以本文为准)
> 对标输入:Obsidian vault 两份外部规格书(`Milestone_项目里程碑功能需求规格说明书.md`、`Release_版本发布功能需求规格说明书.md`,企业级 PM 工具视角,2026-11-10)——**独立实体建模与本项目一致,可参考**;本文吸收其产品语义,裁掉企业级重特性。
> 关联票:YPJ-165(Release 域移除,backlog)。

---

## 0. 架构注记(显要,先读)

> **v1.1 修正(2026-11-12,用户拍板)**:Release 与 Milestone **同为独立实体**——各自一张表、一个领域切片,与 ItemGroup 机制无关,彼此也正交无关。v1.0 误将 Release 描述为 ItemGroupType,作废。

1. **Release = 独立实体。** 现状代码是「release 挂在 item_group(type=release)+ 独立 ReleaseService」的混合形态,两头不靠:YPJ-165 移除旧组实现后,按本文 §4.1 以独立实体重建。上游 ItemGroup PRD §7.3/§8/§24 的「Release 是 ItemGroup」裁决就此**范围回退**(架构破坏显要标注)。
2. **Milestone = 独立实体(现状,已上线)。** 上游 PRD §9/§27 裁决维持;「是否收编组机制」的旧问题就此关闭——不收编。
3. **两者正交**:Milestone 与 Release 之间无外键、无归属、无状态联动,各自独立演进(§4.3)。

---

## 1. 一句话语义(认知模型)

沿用上游 PRD §27 的问答,本文负责其中两问:

| 概念 | 回答的问题 | 用户心智 |
|---|---|---|
| **Milestone(里程碑)** | 我们希望**什么时候达到什么目标**? | 项目时间轴上的目标节点(阶段检查点),如 MVP / Beta / GA |
| **Release(发布)** | 我们**实际交付了什么**? | 一批 Item 的交付集合(计划中的或已发生的),如 R1.0 |

两者语义正交、可关联、不互相替代(§4.3)。一个里程碑可以有多个发布支撑;一次发布也可以不对应任何里程碑。

---

## 2. 对标文档的吸收与裁剪

### 2.1 吸收(转化为本项目语义)

| 对标来源 | 吸收点 | 本项目落点 |
|---|---|---|
| FR-M16 / FR-REL-018 | **逾期是计算标记,不是状态**——状态由用户驱动,逾期由日期推导,两者可同时成立 | Release/列表显示「已逾期」徽标(end_date/target_date 已过且未达终态),不新增状态值 |
| FR-M23/24 | **完成率只是参考,永不自动完成**——范围项全完成 ≠ 验收通过 | 组内 item 完成率在 Release/Milestone 详情展示;完成动作仍走工作流,系统不代打 |
| §8(PRD)/ Release Draft→Released | **Planned / Historical 二态**:Release 可事后补建(发布完再建,直接挂已完成的 item) | 上游 §8.1/8.2 语义保留;随独立实体重建(US-R5) |
| FR-REL-014 | **一个交付 Item 只归属一个目标 Release**,避免重复统计 | 不变量:同 item 至多属一个未终态 release(应用层校验,新实体重建时保留) |
| PRD §11 | Milestone 数量模型:**0..* 完成 / 0..1 进行中 / 0..* 未来** | 已由部分唯一索引硬约束落地,维持 |
| FR-M10 / FR-REL-045(精神) | **改计划不抹历史**——目标日期可改,已完成记录不被覆盖 | Milestone 改 target_date 不影响已完成节点的历史;Release released 后不可改范围(终态冻结) |
| §13/§14(两份) | **报表口径先定义后实现**:完成率/按期率/逾期天数的分母分子规则 | 本文 §8 给出口径;实现另立票(P2) |
| 两份 §12 AC 风格 | 用可测验收标准收尾 | 本文 §9 |

### 2.2 裁剪(明确不做,理由)

| 对标特性 | 裁剪理由 |
|---|---|
| 发布审批链(Go/No-Go)、职责分离、多角色权限矩阵 | 本项目单人使用为主,权限已由 leader guard 覆盖;多用户(YPJ-166)落地后再评估 |
| Build / Artifact / 部署记录 / 多环境 / 回滚 / Hotfix | 属 CI-CD 域,本项目边界是「管理交付集合」不是「执行交付」;系统外(GitHub)已有真相 |
| 质量门禁 checklist、发布准备度 | Item 级 checklist 已有;组级门禁属投机抽象,真需求出现再做 |
| Milestone 验收条件 + 证据体系(FR-M17–19) | 同上;且本项目完成语义走工作流终态,不另建验收子系统 |
| 里程碑间依赖链(FR-M25–27)、基线日期/预测日期、变更原因强制 | 单人节奏下维护成本 > 收益;真有多里程碑排程需求再议 |
| 主动通知/提醒(到期/逾期/变更) | notification 域为 item 中心(事件驱动、无调度器);2026-11-12 评审已提出——被动徽标进 P1(US-M5),主动站内通知 = US-M10 另票 |
| 导出 CSV/XLSX、跨项目/产品线版本、多时区、模板批量 | 明确非目标 |
| 范围冻结 + 快照 | 终态(released)即天然冻结;变更留痕由新实体自建历史(US-R6),快照不做 |

---

## 3. 术语与边界

| 本项目术语 | 对标文档对应 | 边界说明 |
|---|---|---|
| Item | Epic/Story/Task/Bug 统称 | 本项目不区分工作项层级,Epic 由 item 树承担(上游 §28.1) |
| ItemGroup / 组 | (对标无此概念) | 通用容器机制;Sprint 是其类型化实例(v1.1 起 Release 不再是组) |
| Sprint | Sprint | 时间盒,循环节奏;与 Release 正交 |
| Release | Release | 交付集合,**非时间盒**;可事后补建 |
| Milestone | Milestone | 目标节点,**非容器**;本身不挂 item(§12 待拍板项) |
| gate item(验收依据) | Linked Work Item(对标 FR-M20) | 挂到 milestone 作关口依据的少量 item;**tracks 引用非归属**,可多站共用(GA ⊇ MVP 是常态);零 gate 的事件站合法 |
| 目标日期 | Target Date | Release = target_date;Milestone = target_date |
| 实际发布日 | Actual Release Date | released_date(仅 released 终态有值) |

用户可见文案:**Milestone(里程碑)/ Release(发布)**,中文优先,与现有手册/前端一致。

---

## 4. 领域模型

### 4.1 Release(目标模型,独立实体)

```text
release(新表,与 milestone 同构形态)
├── name          「R1.0」
├── status        planned → released(自有状态字段,单向)
├── target_date   目标发布日(可空)
├── released_date 实际发布日(仅 released 终态有值)
└── 成员          item ↔ release 挂接(挂接表/item 引用,spec 定;不变量:同 item 至多属一个未终态 release)
```

- 生命周期由 Release **自有状态机**管理,不走组工作流。
- 排序口径:planned 按 target_date 升序,released 按 released_date 降序。
- 侧栏 Releases 段可通过项目设置关显隐(数据不删)。
- 旧组实现(type=release 的 item_group 行 + ReleaseService/Controller + item_group_type 种子)随 YPJ-165 移除,存量数据处置见 §12。

### 4.2 Milestone(现状实锤 + 目标)

```text
milestone
├── name          「MVP 验收」
├── description   目标/描述:为什么设这个节点(P1,可空)
├── owner         负责人(P1,可空;member 引用)
├── status        planned → in_progress → completed;planned/in_progress → cancelled
├── target_date   目标日期(可空)
├── gate items    验收依据(P1 可选,tracks 引用非归属,可多站共用)
└── 不变量        每项目 0..1 in_progress(部分唯一索引硬约束)
```

- **双形态**:事件站(零 gate item,如「需求调研结束」「团队驻场」,完成 = 人确认)与关口站(有 gate item,完成走硬门禁)。
- 侧栏只显示当前站;Dashboard 北极星卡(P1)。
- 上游「挂接 v1 不做」裁决(§26.4/§28.1)就此推翻:挂接 = gate 依据(tracks),进 P1 票 2(2026-11-12 grill)。

### 4.3 Milestone × Release 关系:正交无关,石碑 vs 货箱

- **无外键、无归属、无状态联动**(v1.1 用户拍板;上游 §26.3 的关联讨论撤销,V29 建的 `item_group.milestone_id` 列随旧组实现一并退役)。
- **分界**:Release = 货箱(成员 = 交付物,归属,单归属);Milestone = 石碑(成员 = 验收依据,tracks 引用,多归属,可零)。「V1.0 上线」可同时建 release(装货)与 milestone(立碑),各管各。
- Release 回答「交付了什么」,Milestone 回答「目标达成了没有」——发布不推动里程碑,里程碑不约束发布。

---

## 5. 状态模型(状态 × 动作真值表)

### 5.1 Release(自有状态机)

| 当前状态 | 动作 | 结果 | 备注 |
|---|---|---|---|
| planned | 发布 | released + 记录 released_date | 用户显式动作,系统不自动;终态 |
| planned | 删除 | 有成员才拒绝 | 保护性守卫 |
| planned | 改名/改目标日/挂摘 item | 允许 | 范围自由调整 |
| released | 改名/改日期/挂摘 item/再发布 | 拒绝 | 终态冻结;补发内容 → 新建 release(US-R5 事后补建不受限) |

### 5.2 Milestone

| 当前状态 | 动作 | 结果 | 备注 |
|---|---|---|---|
| planned | 开始 | in_progress | 全项目同时仅一个 in_progress,冲突返回 409(服务层友好提示) |
| planned | 取消 | cancelled | 终态 |
| in_progress | 完成 | completed | 记录完成事实(updated_at);**关口站:gate items 全终态才可完成,否则 409**(P1);事件站:人确认 |
| in_progress | 取消 | cancelled | 终态 |
| completed / cancelled | 重开/删除 | 拒绝(终态不可删,面板置灰) | 重开属未来需求,待拍板 |

### 5.3 计算标记(非状态)

| 对象 | 定义 | 展示 |
|---|---|---|
| Release | status=planned 且 target_date < 今天 | 「已逾期」徽标(红) |
| Milestone | status ∈ {planned, in_progress} 且 target_date < 今天 | 「已逾期」徽标(红) |
| Milestone 临期 | status ∈ {planned, in_progress} 且 target_date ∈ [今天, 今天+3)(与 item dueSoon 同窗) | 「临期」徽标(黄) |

---

## 6. Release 功能点(user story)

> 全部为**新建**(现状组实现随 YPJ-165 移除;工作量评估见 §14)。

**P0(最小闭环)**

- **US-R1 实体与 API**:release 独立表 + REST CRUD(名称/状态/目标发布日),leader guard,迁移 + api.http。
- **US-R2 管理面板与侧栏**:新建(名称 + 目标发布日)/编辑/删除(有成员拒删);侧栏 Releases 段(名称 + 成员数 + 状态 + 目标日);项目设置显隐开关。
- **US-R3 item 挂接**:item 详情 Release 下拉挂/摘;不变量「同 item 至多属一个未终态 release」,冲突 409。
- **US-R4 发布动作**:planned → released,记实际发布日;终态冻结(§5.1)。
- **US-R5 事后补建**:直接以「已发布 + 实际日期」创建(Historical Release)。
- **US-R6 变更留痕**:成员增删记录历史。

**P1(下一轮)**

- **US-R7 逾期徽标**:planned 且 target_date 已过,列表/侧栏红标(计算,不落库)。
- **US-R8 范围完成率**:挂接 item 的完成率展示,标注「仅供参考,不等于发布资格」。

**P2 / Defer until asked**

- **US-R9** 成员变更历史的 UI 展示。
- **US-R10** 发布按期率报表(§8 口径 2)。
- **US-R11** 版本命名规则校验(如 semver)——单人现状自由命名够用。
- ~~关联 Milestone~~ 撤销(§4.3 正交)。

---

## 7. Milestone 功能点(user story)

**P0(现状已上线,零开发)**

- **US-M1** 管理面板:新建(名称 + 目标日期)/改名/改目标日期/删除(终态拒删)。
- **US-M2** 生命周期:planned → in_progress → completed / cancelled;全项目 0..1 进行中(冲突 409 友好提示)。
- **US-M3** 侧栏 Milestones 段 + 项目设置显隐开关。
- **US-M4** Dashboard「进行中 milestone」。

**P1(2026-11-12 grill 定稿,三票垂直切片,各自独立验证绿)**

*票 1 = YPJ-170(交付完整「事件型站 + 定位器」)*

- **US-M5** 逾期/临期双徽标:overdue 红(target_date < 今天且未终态)/ dueSoon 黄(3 天窗,与 item 同口径),计算不落库。
- **US-M6** 侧栏收敛 + Milestone 页:侧栏只显示当前(进行中)里程碑;全量(当前/未来/历史)+ 创建/编辑/删除收进新页面 `/p/{key}/milestones`;管理弹窗退役;新增 **description(目标/描述)** 与 **owner(负责人)** 可空字段。

*票 2(交付「关口」)*

- **US-M7** gate 挂接:item 详情 Milestone **多选**字段(tracks 依据,非归属,可多站共用)+ 页内依据清单;零范围事件站合法;不做全量拉取(以结果为中心)。
- **US-M8** 关口硬门禁:gate items 存在未终态 → completed 被 409 拒。
- **US-M9** 关口就绪度:gate items 终态比例,页内展示;量「验收证据齐不齐」,非任务进度。
- **US-M10** Dashboard 北极星卡:`▶ 当前站 · 目标日 · 剩/超 N 天 · 关口就绪 x/y`。

*票 3*

- **US-M11** 时间线:Milestone 页内时间轴区块(甘特泳道 defer)。

**P2 / 待拍板**

- **US-M12** completed 重开(记录原因)。
- **US-M13** 临期/逾期站内通知(需定时任务 + 非 item 收件人;notification 域现状 item 中心)。
- 基线/预测日期、并行多「进行中」(0..1 放宽):待真实团队反馈。

---

## 8. 报表口径(先定义,后实现)

| 口径 | 定义 | 备注 |
|---|---|---|
| 1. 关口就绪度(milestone) | 已终态 gate item 数 / gate item 总数 × 100% | 量「验收证据齐不齐」非任务进度;零范围站不显示 |
| 1b. 范围完成率(release) | 已完成挂接 item 数 / 挂接有效 item 总数 × 100% | Release 重建时实现;「有效」= 排除已取消/回收站 item |
| 2. 发布按期率 | released_date ≤ end_date 的 release 数 / 有 end_date 且已 released 的 release 数 × 100% | 分母排除无目标日的;口径仅统计,不干预流程 |
| 3. 里程碑按期率 | completed 且完成时点 ≤ target_date 的 milestone 数 / 有 target_date 且已 completed 的 milestone 数 × 100% | cancelled 不入分母 |
| 4. 逾期天数 | 今天 − 目标日期(自然日) | 仅对 §5.3 命中的对象展示,不落库 |

> 对标文档的「基线按期率 vs 批准计划按期率」双口径**不吸收**:本项目不记基线,单一口径。

---

## 9. 验收标准(本文范围的最小可测集)

| 编号 | 场景 | 预期 |
|---|---|---|
| AC-1 | planned release 的 end_date 已过 | 列表/侧栏显示逾期徽标,状态仍 planned |
| AC-2 | item 已属 release A(planned),再挂 release B(planned) | 拒绝(409),提示先摘除 |
| AC-3 | release 内 item 全部完成 | 完成率 100% 展示,状态**仍为 planned**,不自动发布 |
| AC-2b | 同一 gate item 挂 MVP 与 GA 两站 | 允许(多归属) |
| AC-3b | 关口站 gate items 有未终态时 ✓完成 | 409 + 未完成清单;全终态后可完成 |
| AC-3c | 事件站(零 gate item)✓完成 | 直接完成(人确认) |
| AC-4 | released release 尝试挂 item / 改名 | 拒绝 |
| AC-5 | 已有 in_progress milestone 时开始另一个 | 409,提示当前进行中的是哪个 |
| AC-6 | milestone target_date 已过且未 completed/cancelled | 逾期徽标;completed 后徽标消失 |
| AC-6b | milestone target_date 在 3 天内且未终态 | 黄「临期」徽标;终态后消失 |
| AC-7 | release 完成发布 | 对任何 milestone 状态零影响(正交,无联动) |
| AC-8 | 项目设置关闭 Milestone/Release 段 | 侧栏段消失,API 数据不受影响 |

---

## 10. 页面示意(贴合现状,ASCII)

侧栏(项目内):

```text
┌─ 侧栏 ────────────────┐
│ ▤ 看板                 │
│ ▤ backlog              │
│ ▤ Sprints        ▦     │
│   V29        ● 11-14   │
│ ▤ Milestones     →     │   ← 点击进 Milestone 页(全量+编辑+时间线)
│   ▶ MVP 验收  进行中 ⚠  │   ← 侧栏只显示当前(进行中,P1 收敛)
│ ▤ Releases       ⚙     │   ← 独立实体;⚙=管理 Release(面板形态同 Milestone)
│   R1.0 (3)  规划中 · 12-20
│   R0.9 (7)  已发布 09-15
└────────────────────────┘
```

Release 管理面板 / item 详情(现状字段 + P1 增量标注):

```text
┌─ 管理 Release ────────────────────────┐
│ 新建:[名称] [目标发布日]              │
│ R1.0  规划中 · 目标 2026-12-20  [编辑] │
│ R0.9  已发布 · 2026-09-15       [只读] │
└────────────────────────────────────────┘
item 详情 ▸ Release:[ R1.0 ▾ ]          ← 未终态才可挂
```

---

## 11. 与上游 ItemGroup PRD 的冲突处置

| 上游章节 | 处置 |
|---|---|
| §7.3 / §8 / §24(Release = ItemGroup) | **推翻**(架构破坏显要标注,即 YPJ-165「Phase 3 范围回退」):Release 独立实体化;交付集合/事后补建/Planned-Historical 语义保留 |
| §9–12(Milestone) | **全部维持**(独立表,已上线) |
| §25.3 P1 清单 | 关联展示撤销(正交);Timeline 收敛为「复用 Timeline 渲染」 |
| §26.3 / §26.4 | §26.3 撤销(无关联);§26.4 维持不做,待拍板 |

---

## 12. 待拍板事项(人裁,agent 不代选)

1. ~~收编 ItemGroupType~~ 已拍板:不收编,独立实体(2026-11-12)。
2. ~~item ↔ Milestone 挂接~~ 已拍板:进 P1 票 2,gate 依据 tracks 多归属(2026-11-12 grill)。
3. **YPJ-165 存量 release 数据处置**(A 不迁移[推荐] / B Flyway 清)——票内已列,开工时拍。
4. **Release 挂接策略暂定**(字段化挂接 + 批量/自动规则 defer)——到 release 实现时才确认(2026-11-12)。
5. **API 删除 milestone 补终态守卫**(spec 0116 §6 #1,唯一未拍项)。

---

## 13. 实施承接

- 本 PRD 不排期;切片顺序按 §14 工作量结论。
- YPJ-165(移除旧组实现)按票内 A/B 切片执行,开工前照例出 spec 三件套;**其票描述「移除后 Release/Milestone 语义归 ItemGroup 承接」一句与本文架构相悖,开工前须修正为「移除后 Release 以独立实体重建(见 Milestone-Release PRD)」**。
- Release 重建各票动 `release` schema / API 形状时,同步 entities.puml 与 api.http(仓库纪律)。

## 14. 工作量评估与排序(2026-11-12,粗估)

| 域 | 已有基础 | 剩余工作量 | 票数 | 相对规模 |
|---|---|---|---|---|
| **Milestone** | P0 四条已上线 | 票1 页面+描述/负责人+双徽标+侧栏 ≈1.5 天;票2 gate 挂接+门禁+就绪度+北极星 ≈1.5–2 天;票3 时间线 ≈0.5–1 天 | 3 张票 | **≈2(grill 后满配)** |
| **Release** | 无(旧组实现还要先拆) | US-R1 后端实体+API+迁移+测试(≈1.5 天)→ US-R3 挂接+不变量(≈0.5)→ US-R2/R4/R5 前端面板+动作(≈1)→ US-R7/R8(≈0.5)→ YPJ-165 移除+存量(≈0.5–1) | 3–4 张票 | **≈4** |

**结论:两域正交,先做 Milestone(≈3.5–4.5 天 vs ≈4 天;grill 后 milestone 满配,量级追平 Release,但价值命题已立住:定位器+关口)。** Milestone 三票收口后再启 Release 重建,垂直切片推进:后端实体 → 挂接 → 前端 → 移除旧实现,各切片独立验证绿。
