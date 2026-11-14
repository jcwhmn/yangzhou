# spec 0126 — YPJ-180 工作量预估·项目级刻度配置

> 状态:v2 已编码(spec 随绿点 1 入库;web 面板随绿点 2)。
> 拆票:本票=项目级配置;item 级设置(选档位/自由输入/展示/CLI)= **YPJ-186**,另立 spec。

## 一、需求

### 范围

1. **项目级刻度集合可配置**:每个项目可自定义工作量刻度档位——增、删、改;每档含稳定 key、显示名、数值+单位(单位如「天/小时」,数值+单位均可配;默认 微=2天/小=3天/中=5天/大=8天/超大=13天 仅为初始占位)。
2. **项目级 required 开关**:可配置「item 预估必须非空」(布尔,默认关)。**本票只落配置与校验数据面,item 写入侧的消费(立票强制)在 YPJ-186 实现。**
3. 未配置的项目使用内置默认五档。
4. **web 设置面板**:刻度编辑区(增删改档位)+ required 开关。

### 非目标(→ YPJ-186 或明确不做)

- item 上的 estimate 字段、立票/编辑选择或自由输入、表格列、看板角标、CLI 参数(全部 YPJ-186)。
- required 的立票强制校验(YPJ-186)。
- 父子汇总、匹配引擎联动、实绩对比。

## 二、详细设计

### 数据(V60 迁移)

```sql
ALTER TABLE projects ADD COLUMN estimate_scales TEXT NULL;     -- JSON 数组,null=内置默认
ALTER TABLE projects ADD COLUMN estimate_required BOOLEAN NOT NULL DEFAULT FALSE;
```

- estimate_scales JSON 结构:`[{"key":"xs","label":"微","value":2,"unit":"天"},…]`
  - 校验(service 层):1–10 档;key 唯一且 `^[a-z0-9_-]{1,32}$`;label 非空 ≤16 字符;value 数值 >0 ≤999;unit 非空 ≤8 字符。
  - **无 CHECK 约束**:档位集合是动态数据不是代码枚举,库级 CHECK 无法表达;service 校验兜底。注:偏离「枚举=CHECK」仓纪律,有意为之。
- key 是稳定标识(item 侧将来只引用 key,label/value/unit 改名不破坏历史引用)。

### 后端

- 内置默认:`EstimateScales.DEFAULT`(domain 或 api 内 companion 常量;纯数据,放 project slice 同包)——微/小/中/大/超大 = 2/3/5/8/13 天,key `xs/s/m/l/xl`。
- `Project` 实体 + `estimateScales: String? = null`、`estimateRequired: Boolean = false`。
- ProjectService settings 读:解析 JSON → 结构化 DTO;解析失败 warn 降级返回默认(不 500);null → 默认五档 + `usingDefault=true` 标记(前端据此显「恢复默认」态)。
- PATCH `/projects/{key}/settings` 增可选字段:`estimateScales: List<EstimateScaleDto>?`(null=不改,显式清回默认=传空数组?→ **约定:传数组即整体替换;恢复默认=传默认五档副本,null=不改**;不引入第三态,面板「恢复默认」直接提交默认值)、`estimateRequired: Boolean?`。
- 校验失败 400,统一错误形状(fields 带 index)。
- api.http:projects 区块补 PATCH settings 带 estimateScales/estimateRequired;负例区补「key 重复 400」「value=0 400」「0 档 400」。

### web(ProjectSettingsPanel)

- 「工作量刻度」编辑区:每行 [key(新档可输,已存只读)] [label] [value] [unit] [删];「+ 加一档」;「恢复默认」;与面板其它设置同一「保存」提交 PATCH。
- required 开关:复选「立票必须填预估(立票强制在 item 级票实现)」。
- 前端同规则校验(key 重复/value≤0/空档),禁保存+行内提示;后端兜底 400。

### CLI

- 无(设置走 web;yz 有需要将来另补)。

### 否决的备选

- ~~独立刻度行表~~:单项目 ≤10 档、无关联查询,JSON 列最简;将来要统计再迁移(text→行表迁移零损)。
- ~~全局刻度+项目覆盖两层~~:一层项目级已覆盖,两层是投机抽象。
- ~~unit 枚举(天/小时/周)~~:用户明说单位自由(「8小时之类」),存自由字符串。

### 前端操作流程(状态×动作真值表)

| 面板状态 | 动作 | 结果 |
|----|----|----|
| 项目未配置(null) | 打开面板 | 显示内置默认五档,「恢复默认」置灰 |
| 编辑中 | 增一档(填 key/label/value/unit) | 行入列表,key 前端即时查重 |
| 编辑中 | 删档 | 行移除;≤1 档时禁删最后一档 |
| 编辑中 | key 重复/value≤0/unit 空 | 保存禁用+行内错误 |
| 编辑中 | 保存(合法) | PATCH estimateScales+estimateRequired,成功重拉项目 |
| 已配置 | 恢复默认 | 表单回填默认五档(仍需点保存才落库) |
| required 开 | 保存 | estimateRequired=true 落库(item 侧强制在 YPJ-186 接) |

### 示意图

```
┌ 工作量刻度 ─────────────────────────────────────┐
│ key    名称     数值   单位                       │
│ xs     [微   ]  [ 2 ]  [天 ]              [删]   │
│ s      [小   ]  [ 3 ]  [天 ]              [删]   │
│ m      [中   ]  [ 5 ]  [天 ]              [删]   │
│ …                                                │
│ [+ 加一档]                       [恢复默认]      │
│ ☐ 立票必须填预估                                  │
│ (随面板统一 [保存] 提交)                          │
└──────────────────────────────────────────────────┘
```

## 三、手工测试用例(用户 build/debug 阶段执行)

1. 默认:新建项目不配置 → 面板显示默认五档(微2天…超大13天),恢复默认置灰。
2. 改值:「中」数值改 8、单位改「小时」→ 保存 → 重进面板显示 8小时。
3. 增删:加档「巨=21天」成功;删到剩 1 档时「删」禁用。
4. 负例:key 重复 / value=0 / 清空 label → 保存禁用+行内提示;api.http 直打 → 400。
5. required 开 → 落库(项目详情/settings 回读 true);item 侧暂无行为(186 接)。
6. 恢复默认 → 保存 → 存储回默认五档。

## 四、As-built(2026-11-14 编码完成,PR 待开)

### 落点

- V60__project_estimate_scales.sql:projects + estimate_scales TEXT NULL / estimate_required BOOLEAN DEFAULT FALSE(无 CHECK,校验集中 service,spec §二已述)
- EstimateScales.kt(project slice):EstimateScaleDto + DEFAULT 五档 + parse(readTree 手解,损坏/空降级默认 warn)/serialize(mapOf 数组)/validate(1–10 档、key 正则唯一、label≤16、value(0,999]、unit≤8)
- ProjectService:ProjectDto + estimateScales/estimateRequired/estimateScalesIsDefault;updateSettings null=不改(首版 `?.let` 会冲存量,单测合并语义例抓出修正);ObjectMapper 构造注入
- ProjectSettingsPanel:刻度行编辑 + newIdx 标记新档(key 可编辑;已存档与非默认占位锁定)、加一档 ≤10、删保 ≥1、恢复默认、行内校验禁保存;estimateRequired 开关;maxWidth xs→sm
- 测试:单测 EstimateScalesTest 4 例 + ProjectServiceUnitTest 合并语义 1 例(默认回显/PATCH 替换/null 不改);集成 ProjectsApiTest 1 例(默认五档+isDefault/PATCH 往返/key 重复与零档 400/库不被坏请求污染)
- api.http:projects 区块 settings 刻度节 + 文尾负例区 3 请求(key 重复/零档/value=0)
- entities.puml:Project 加两字段

### 验证

- gradle test 全绿(373→380 例);web tsc + next build 绿
- 集成测试 = merge 前本人跑(工单纪律);手工用例 §三 6 例待用户 build/debug 走查

### 范围外备忘

- item 写入侧(estimate 字段/选档或自由输入/表格列/看板角标/CLI/required 强制)= YPJ-186
