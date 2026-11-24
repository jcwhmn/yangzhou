# 0167 · YPJ-258 item 关闭理由 SRS

> 状态:草案,待拍板(§八)。REST 增量以运行时 `/v3/api-docs`(springdoc,OpenAPI 3.1.0)实况为准,§六按真实 schema 切 diff。

## 一、目的与范围

item 转终态(FINAL)时记录「关闭理由」:预设枚举(缺省=完成)+ 可选备注,承载"非完成性收口"(不做/过期/重复/被取代)。是否询问理由由 **workflow 源状态**配置决定,正常路径不打扰。

范围:迁移(status 配置列 + item 理由列)、状态配置契约扩展、PATCH item 契约扩展、看板/详情页转终态弹窗、详情页修改入口、列表标识、CLI 透传。不改 workflow 状态机迁移规则。

## 二、术语

- **终态(FINAL)**:`status.status_type = 'final'`,项目内可多个。
- **关闭理由(closeReason)**:封闭枚举,`done` 为缺省;终态票恒有值。
- **需问理由(requireCloseReason)**:状态级布尔配置。从该状态迁出到任一终态时,前端弹窗询问;后端校验非 done 或显式 done 皆可,只是不再静默缺省。

## 三、枚举表

| 值 | 中文 | 何时用 | 备注 |
|---|---|---|---|
| `done` | 完成 | 正常做完(缺省) | 不存 note |
| `wontfix` | 不做 | 决定不做(优先级/方向变化) | 可填 note |
| `expired` | 过期 | 时效已过,做了也无意义 | 可填 note |
| `duplicate` | 重复 | 与另一票重复(建议 note 写票号) | 可填 note |
| `superseded` | 被取代 | 被新票/新方案替代(建议 note 写替代票号) | 可填 note |

文案外置;DB 存枚举值,CHECK 约束封闭。

## 四、数据语义

### 4.1 状态配置(迁移 V71)

`status` 表加列 `require_close_reason BOOLEAN NOT NULL DEFAULT false`。

- 语义:从此状态迁出到终态时,前端**必须**弹窗让用户选理由(默认选中 `done`,不静默)。
- 存量状态全 false → 行为与现状一致,零打扰;项目自己按需打开。
- 管理入口:项目设置的状态编辑表单加开关(与 wipLimit 同区)。

### 4.2 item 理由(同迁移 V71)

`item` 表加两列,**可空**:NULL = 票未处于终态(未关或已 reopen),这是列的自然状态,不是缺数据。

- `close_reason VARCHAR(20) NULL`,`CONSTRAINT ck_item_close_reason CHECK (close_reason IN ('done','wontfix','expired','duplicate','superseded'))`
- `close_note TEXT NULL`

不变式:
- 任何入口转终态:带 closeReason 写该值;不带 → 写 `done`。**终态票 `close_reason` 恒非空**——应用层不变式(CHECK 跨行做不到,不上 trigger)。
- `close_note` 仅随非 done 理由存;reason=done 时清空 note。
- reopen(终态→非终态,任何入口)→ 清空两列。
- 删 item → 列随行走。

### 4.3 存量数据(Flyway V71 同文件 DDL + DML)

只回填**未删除的终态票**为 `done`;非终态票留 NULL(正确状态,非缺数据);软删除票(`deleted_at IS NOT NULL`)不回填(2026-11-24 用户拍板),回收站恢复终态票时由恢复逻辑补 `done`。

```sql
alter table status add column require_close_reason boolean not null default false;
alter table item add column close_reason varchar(20);
alter table item add column close_note text;
alter table item
  add constraint ck_item_close_reason
  check (close_reason in ('done', 'wontfix', 'expired', 'duplicate', 'superseded'));

update item i
   set close_reason = 'done'
  from status s
 where s.object_id = i.status_object_id
   and s.status_type = 'final'
   and i.deleted_at is null;
```

口径:存量终态票全视为正常完成——历史上是否有非完成关闭无法追溯,接受(2026-11-24 用户拍板)。

## 五、分支真值表

| 场景 | 行为 |
|---|---|
| 看板拖列/详情页改状态 → 终态,**源状态 requireCloseReason=false** | 直接迁移,后端写 `done`(现状零打扰) |
| 同上,**源状态 requireCloseReason=true** | **弹窗**(§七 UI):下拉默认 `done`,选非 done 出备注框;确认后 PATCH 带 closeReason(+note);取消 = 不迁移 |
| 任意入口转终态,带 closeReason=枚举值 | 写该值(+note) |
| 带非法 closeReason / 转非终态却带 closeReason | 400 |
| PATCH 终态票只改 closeReason(不动状态) | 更新;改回 done 清 note |
| reopen | 清空两列 |
| CLI `items move <ID> <终态名>` | 不弹;不带 --reason 即 `done`;源状态 requireCloseReason=true 时 **CLI 提示**「源状态要求理由,已记 done;用 --reason 指定」 |
| CLI `--note` 无 `--reason` 或 `--reason done` | CLI 报错 |
| 详情页:终态票 | 状态旁显示理由文案(+备注);下拉可改,非 done 显示备注框 |
| 列表/看板:终态票 reason≠done | 状态旁「非完成关闭」标识(理由文案) |
| 列表/看板:reason=done | 无标识(现状) |

## 六、REST 契约(OpenAPI 增量;基于 `/v3/api-docs` 实况切 diff)

完整文档 = 运行中后端 `GET /v3/api-docs`(JSON)/`/v3/api-docs.yaml`;UI 在 `/scalar-ui.html`。下面只列本票**改动点**(`+` 新增),schema 名与实况一致。

### 6.1 状态配置:`UpdateStatusRequest` / `CreateStatusRequest` / `StatusDto`

```yaml
# 现状(实况):name, icon, statusType, position;Update 无 required
UpdateStatusRequest:
  properties:
    name: { type: string }
    icon: { type: string }
    statusType: { type: string, enum: [START, FINAL, ACTIVE] }
    position: { type: integer, format: int32 }
+   requireCloseReason: { type: boolean }   # 从此状态迁出到终态时前端须弹窗
CreateStatusRequest:
  properties:
    # …同上 4 字段…
+   requireCloseReason: { type: boolean, default: false }
StatusDto:
  properties:
    statusId: { type: string, format: uuid }
    name: { type: string }
    icon: { type: string }
    statusType: { type: string, enum: [START, FINAL, ACTIVE] }
    position: { type: integer, format: int32 }
+   requireCloseReason: { type: boolean }
```

### 6.2 item:`UpdateItemRequest` / `ItemDto`(`PATCH /api/items/{itemId}` 等)

```yaml
UpdateItemRequest:   # 实况 10 字段:title description type statusItemId parentItemId startDate dueDate priority estimateValue estimateUnit
  properties:
+   closeReason:
+     type: string
+     nullable: true
+     enum: [done, wontfix, expired, duplicate, superseded]
+     description: 仅目标状态为终态时允许;转终态不带 → 后端写 done
+   closeNote:
+     type: string
+     nullable: true
+     maxLength: 2000
+     description: 仅随非 done 理由存;reason=done 时忽略并清空
ItemDto:             # 实况 31 字段,略
  properties:
+   closeReason: { type: string, nullable: true, enum: [done, wontfix, expired, duplicate, superseded] }   # 终态恒非空
+   closeNote: { type: string, nullable: true }
```

错误(统一形状 code/message/path/fields):转非终态带 closeReason → 400;枚举非法 → 400;note 超长 → 400。

## 七、窗口 UI

### 7.1 转终态弹窗(仅源状态 requireCloseReason=true)

触发:看板拖列落到终态列 / 详情页状态改为终态。

```text
┌─ 关闭 YPJ-258 ─────────────────────────┐
│ 关闭原因   [ 完成              ▾]      │
│ ┌───────────────────────────────────┐  │
│ │ 备注(可选)                        │  │
│ └───────────────────────────────────┘  │   ← 选 done 时隐藏
│                     [取消]  [确认关闭] │
└─────────────────────────────────────────┘
```

- 下拉默认 `done`,焦点在下拉;回车 = 确认。
- 选非 done → 下方展开备注框(多行,≤2000);切回 done → 收起并清内容。
- 确认 → PATCH `{statusItemId, closeReason, closeNote?}`;失败原地报错不关窗。
- 取消 → 不迁移;看板卡片回原列。

### 7.2 详情页修改(终态票,不弹窗)

状态行右侧:理由文案 chip(`完成` 灰 / 非完成 带色);hover 出「修改」。点开为行内 popover:同 7.1 的下拉 + 条件备注框 + 保存。保存 = PATCH 仅 `{closeReason, closeNote?}`。

### 7.3 列表/看板标识

reason≠done:状态 chip 旁加小标签,文案 = 理由中文(不做/过期/重复/被取代),颜色统一(非完成)。reason=done 不加。

### 7.4 状态设置

项目设置 → 状态编辑:在 wipLimit 旁加开关「转终态时询问关闭原因」。

## 八、验收标准

- [ ] 状态配置 requireCloseReason 可设/改/读;存量默认 false;
- [ ] 源状态 true 时,看板与详情页转终态弹窗;false 时不弹,行为同现状;
- [ ] 转终态不带理由 → done;带 → 写入;非终态带理由 → 400;非法枚举 → 400;
- [ ] reopen 清空;终态票只改理由可改,改回 done 清 note;
- [ ] 详情页修改入口;列表/看板 reason≠done 有标识;
- [ ] CLI 透传 + requireCloseReason=true 时提示;
- [ ] 回收站恢复终态票 → close_reason 补 done(§4.3 口径);
- [ ] 迁移 V71(两表 DDL + 存量终态票回填,软删除不回填)+ api.http 用例 + e2e 覆盖 §五。

## 九、开放决策

| # | 事项 | 状态 |
|---|---|---|
| D1 | 契约:复用 PATCH item 加字段,零新端点 | 已拍板 |
| D3 | 枚举:done + 4 非完成值 | 已拍板 |
| D6 | 弹窗策略:按源状态 requireCloseReason 配置 | 已拍板 |
| D2 | reopen 清空理由 | 推荐 A 清空;待确认 |
| D5 | 存量终态票迁移回填 done;软删除不回填;非终态留 NULL | **已拍板**(2026-11-24 用户) |
| D4 | 带非 done 理由转终态是否写一条 activity | 待定(与 YPJ-263 汇合) |
