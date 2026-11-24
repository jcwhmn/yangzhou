# 0167 · YPJ-258 item 关闭理由 SRS

## 一、目的与范围

item 转终态(FINAL)时记录「关闭理由」:预设枚举(缺省=完成)+ 可选备注,承载"非完成性收口"(不做/过期/重复/被取代)。不填即完成,不打扰正常路径。

范围:迁移、PATCH 契约扩展(请求 DTO 加字段,零新端点)、detail 页展示与修改入口、列表/看板标识、CLI `items move` 透传。不改 workflow 状态机,不弹强制窗。

## 二、术语

- **终态(FINAL)**:`status.status_type = 'final'` 的状态,项目内可多个。
- **关闭理由(closeReason)**:封闭枚举,含 `done`(完成)为缺省;终态票恒有值。
- **关闭备注(closeNote)**:自由文本,仅随非 done 理由存在。

## 三、数据语义

迁移 V71(SQL identity + 命名约束),`item` 表加两列:

- `close_reason VARCHAR(20) NULL`,`CONSTRAINT ck_item_close_reason CHECK (close_reason IN ('done','wontfix','expired','duplicate','superseded'))`
- `close_note TEXT NULL`

不变式:
- 任何入口转终态(看板拖列 / 详情页改状态 / CLI `items move` / import)时,若未指定理由 → 后端写 `done`(缺省);指定则写指定值。终态票 `close_reason` 恒非空。
- `close_note` 仅随非 done 理由写入;reason=done 时忽略/清空 note。
- reopen(终态→非终态,经任何入口)→ 清空两列(D2);再关再按当时输入定。
- 存量已 Done 票:迁移回填 `close_reason = 'done'`(存量正常完成,与缺省一致;非完成关闭的存量无法追溯,接受)。
- 删 item → 列随行走,无额外钩子。

## 四、分支真值表

| 场景 | 行为 |
|---|---|
| 任意入口转终态,不带 closeReason | 状态迁移 + `close_reason = done` |
| 任意入口转终态,带 closeReason=枚举值 | 写该值(+note 若有) |
| 带非法 closeReason | 400(Bean Validation) |
| 转非终态却带 closeReason | 400(统一错误形状:仅转终态可填) |
| PATCH 终态票只改 closeReason/closeNote(不动状态) | 更新理由(reason→done 则清 note) |
| reopen | 清空两列 |
| detail 页:终态票 | 显示理由文案(+备注);旁边下拉可改理由,非 done 时显示备注输入 |
| 列表/看板:终态票 reason≠done | 状态旁「非完成关闭」标识(reason 文案) |
| 列表/看板:reason=done | 无额外标识(现状) |
| CLI `items move <ID> <终态名> [--reason <枚举>] [--note 文本]` | 透传进 PATCH;--note 无 --reason 或 reason=done → CLI 报错 |

## 五、REST 契约(OpenAPI,只写增量;既有 `PATCH /api/items/{itemId}`)

```yaml
openapi: 3.0.3
info: { title: YPJ-258 增量, version: '0167' }
paths:
  /api/items/{itemId}:
    parameters:
      - name: itemId
        in: path
        required: true
        schema: { type: string, format: uuid }
    patch:
      tags: [items]
      summary: 改 item(既有端点;本票请求体加 closeReason/closeNote)
      operationId: patchItem
      requestBody:
        content:
          application/json:
            schema:
              $ref: '#/components/schemas/PatchItemRequest'
      responses:
        '200':
          description: 更新后的 item
          content:
            application/json:
              schema:
                $ref: '#/components/schemas/ItemDto'
        '400':
          $ref: '#/components/responses/BadRequest'
components:
  schemas:
    PatchItemRequest:
      type: object
      description: 既有字段(statusItemId/title/description/assigneeObjectId/…)略;仅列增量
      properties:
        statusItemId:
          type: string
          format: uuid
          nullable: true
          description: 目标状态;转终态且未带 closeReason → 后端写 done
        closeReason:
          allOf: [{ $ref: '#/components/schemas/CloseReason' }]
          nullable: true
          description: 仅目标状态为终态时允许;非终态带此字段 → 400
        closeNote:
          type: string
          nullable: true
          maxLength: 2000
          description: 仅随非 done 理由存;reason=done 时忽略
    CloseReason:
      type: string
      enum: [done, wontfix, expired, duplicate, superseded]
      x-enum-labels: { done: 完成, wontfix: 不做, expired: 过期, duplicate: 重复, superseded: 被取代 }
    ItemDto:
      type: object
      description: 既有 DTO,仅列增量字段
      properties:
        closeReason:
          allOf: [{ $ref: '#/components/schemas/CloseReason' }]
          nullable: true
          description: 终态票恒非空(存量迁移回填 done);非终态 null
        closeNote:
          type: string
          nullable: true
  responses:
    BadRequest:
      description: 非终态带理由 / 枚举非法 / 备注超长 / 仅改理由但票非终态(统一错误形状 code/message/path/fields)
```

## 六、验收标准

- [ ] 任意入口转终态不带理由 → `done`;带理由 → 写入;非终态带理由 → 400;非法枚举 → 400;
- [ ] reopen 清空;再关按输入;
- [ ] 终态票 PATCH 只改理由可改;改回 done 清 note;
- [ ] detail 页终态票显示理由,可改;非 done 可填备注;
- [ ] 列表/看板 reason≠done 有「非完成关闭」标识;
- [ ] CLI `items move` 支持 --reason/--note;
- [ ] 迁移 V71(含存量回填 done)+ api.http 用例 + e2e 覆盖上表。

## 七、开放决策(动工前须用户拍板)

| # | 事项 | 状态 |
|---|---|---|
| D1 | 契约形状:复用 PATCH 加字段,零新端点 | **已拍板**(2026-11-24 用户) |
| D2 | reopen 时理由清空 | 推荐 A 清空;待确认 |
| D3 | 枚举集:done + 4 非完成值 | **已拍板**(2026-11-24 用户) |
| D4 | 活动留痕:转终态带非 done 理由是否写一条 activity | 待定(与 YPJ-263 汇合处) |
| D5 | 存量 Done 票迁移回填 `done` | 推荐(与缺省一致);待确认 |
