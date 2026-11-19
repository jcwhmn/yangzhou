# 0147 — YPJ-222 甘特 S5:计划 vs 实际线 + 偏移量 + A 级小件

> 状态:已拍板(2026-11-19:Q1=案B 双行 / Q2=遮罩=清单完成率% / Q3=配色重整纳入本票 / Q4=PRD 废弃标注可)。票面:实际条 = status_history 首迁非 START → 首迁 FINAL/今天;偏移量 ±Nd;A 级小件 = 进度遮罩/hover 卡/折叠徽标/今日线入视口。PRD §二R2 / §六A。

## 一、需求

### 1.1 范围

1. **实际条**:每个叶子 item 行画实际条——起点 = `item_status_history` 首次迁入非 START 状态的时间,终点 = 首次迁入 FINAL 的时间;未完成(未达 FINAL)延伸到今日线。
2. **偏移量徽标**:行上标 `+Nd` / `−Nd`——= (实际结束 ?? 今天) 与计划 due 的日历日差;延期红、提前绿、0 不显示。
3. **A 级小件(4 件)**:
   - 进度遮罩:条上遮罩,宽度 = 清单完成率(done/total),深色覆盖浅色底;
   - hover 详情卡:悬停条区显示 编号/标题/状态/负责人/计划起止/实际起止(进行中)/清单 done/total;
   - 折叠聚合徽标:父行折叠时汇总条上显示「+N 子任务」(N = 被隐藏的全部后代数);
   - 今日线入视口:首次加载数据后,今日线在视口外则滚动至视口中部(每会话一次)。
4. **配色重整(Q3 纳入)**:汇总条灰 → 橙系;实际条用青绿系;计划条保持现状蓝;overdue 红 / FINAL 灰不变。参考图未立票,按下表实现,build/debug 阶段可调色。

| 元素 | 现状 | 新色 |
|---|---|---|
| 汇总条(父) | text.secondary 灰 45% | `warning.main` 橙(不透明度提至 0.75) |
| 实际条 | (新增) | `success.main` 青绿;FINAL 实际条 = text.disabled 灰 |
| 计划条(叶) | primary 蓝 / overdue 红 / FINAL 灰 | 不变 |

### 1.2 非目标

- 小时级精度(TIME 迁移,item 域另票;本期 date 级);
- 基线快照、拖拽、依赖连线(B 级另票);
- 父行实际汇总条 / 后代聚合完成率(父行只画计划汇总条);
- due-only(里程碑菱形)行与未排期行不画实际条;
- 配色重整细节调色(§1.1.4 已按上表实现,微调属 build/debug 阶段用户手调);

## 二、详细设计

### 2.0 后端:实际起止推导(`ProjectService.gantt`)

数据源 = `item_status_history`(已有写入点:创建时 source=created、状态变更 source=manual、github sync)。`gantt()` 已加载全项目 statuses(`statusById`,含 statusType)——历史行的 `toStatusObjectId` 直接查表得类型,**不需要新 join status**。

新增批量查询(`Repositories.kt`,ADR-0004 授权扩展点,不碰 JDBC client):

```kotlin
// ItemStatusHistoryRepository
@Query("""
    SELECT h.* FROM item_status_history h
    JOIN item i ON i.object_id = h.item_object_id
    WHERE i.project_object_id = :projectId AND i.deleted_at IS NULL
""")
fun forProject(projectId: UUID): List<ItemStatusHistory>
```

推导(服务层内存分组,按 `createdAt` 升序、`id` 升序破并列):

- `actualStart` = 最早一条 `toStatus` 类型 ≠ START 的行时间;无则 null;
- `actualEnd` = 最早一条 `toStatus` 类型 = FINAL 的行时间;无则 null。

附加字段(同一次 `gantt()` 调用顺带取,均为批量):

- `assigneeName`:`MemberRepository.findAllByObjectIdIn`(已有)——hover 卡要负责人;
- `checklistDone` / `checklistTotal`:新投影查询分组计数:

```kotlin
// ChecklistItemRepository;projection 按 Spring Data JDBC 构造器投影,别名对齐属性名
data class ChecklistCount(val itemId: UUID, val total: Long, val done: Long)

@Query("""
    SELECT ci.item_object_id AS itemId, COUNT(*) AS total,
           SUM(CASE WHEN ci.done THEN 1 ELSE 0 END) AS done
    FROM checklist_item ci
    JOIN item i ON i.object_id = ci.item_object_id
    WHERE i.project_object_id = :projectId AND i.deleted_at IS NULL
    GROUP BY ci.item_object_id
""")
fun countByProject(projectId: UUID): List<ChecklistCount>
```

**契约增量(additive,GanttRow 追加 5 字段)**:

```
actualStart: String?   actualEnd: String?   assigneeName: String?
checklistDone: Int?    checklistTotal: Int?   // total=0 或无清单 → 两字段均 null
```

否决的备选:①前端逐 item 拉 `/items/{id}/activity` 推导——N+1 且 activity 是另一张表;②history 单独端点——多一往返,gantt 本就该一行式返回;③JOIN status 一次查平——status 已在内存,白加查询复杂度。

无 schema 变更(两图不动);无新端点(api.http 不动);域纯函数与原型比对不受影响。

### 2.1 前端:轴与类型

- `GanttRow` 类型追加 5 字段(page.tsx L30)。
- **轴范围纳入实际日期**:`axis` 的 dates 锚定集合追加非父行的 `actualStart/actualEnd`(否则实际条超出计划区间时被裁在 timelineW 外)。today 已是兜底锚点,不变。

### 2.2 前端:实际条与偏移量(已拍板:案 B 双行)

**[已拍板 Q1=案 B,以下案 A 留档不实现]**

```text
│  YPJ-217  时区适配   ┌╌╌╌╌╌╌╌╌╌┐                     │
│  (title col)         └╌╌╌╌╌╌╌╌╌┘  ←计划=描边空心      │
│                      ███████████████ +2d              │ ←实际=实心细条,未完成→今日线
│                       (遮罩画在计划条内,如 ▓▓▓ 覆盖)   │
```

- 优点:行高不变、单条视觉紧凑;偏移徽标与条对位自然。
- 缺点:计划短/实际长时形状互侵(靠描边兜底);遮罩画在计划条但视觉上与实际条分离,语义易混;密集缩放级两段难分辨。

**[案 B,已拍板实现]** 双行(上下两条)——行高 44 不变:计划条 top 5 h 12(样式同现状),实际条 top 24 h 12;进度遮罩画在**实际条**上;偏移徽标对齐实际条行。

```text
│  YPJ-217  时区适配   ┌────────┐                        │
│                      └────────┘   ←计划(上,同现状样式) │
│                        ████████████████▓▓ +2d         │
│                        ↑实际(下):实心,未完成→今日线,   │
│                        遮罩 ▓▓ = 清单完成率             │
```

- 优点:计划/实际永不互遮;遮罩、徽标、hover 命中区各自独立,后续 B 级(拖拽)也不打架。
- 缺点:每行两细条,信息密度高;与菱形/汇总条的单条行有视觉档差。

**偏移徽标(两案共用)**:`offsetDays = datePart(actualEnd ?? today) − datePart(dueDate)`(仅 dueDate 存在时);`>0` 红 `+Nd`、`<0` 绿 `−Nd`、`=0` 不显示; FINAL 用 actualEnd(冻结),未完成用今天(活的)。无 dueDate 不显示。

**语义含糊标注(请复核)**:PRD §六A「进度遮罩:条上按状态分色,已过去时段深色/未过浅色;%用清单完成率推导」——时间分色与 % 遮罩两种读法混在一句。**取 % 遮罩读法**(宽度 = 清单完成率),因 PRD 明说「% 用清单完成率推导」且时间分色已由「未完成延伸到今日线」承担。

### 2.3 前端:A 级小件

- **hover 卡**:MUI `Tooltip`,挂在条区的透明命中块上(现整行 Link 覆盖全行,hover 卡需锚在条矩形——在 Link 内加绝对定位命中区,坐标即条的 left/width/top)。内容 Stack:编号+标题 / 状态 Chip / 负责人 / 计划 start→due / 实际 start→end 或「进行中→今日」/ 清单 done/total。无日期行无卡。
- **折叠徽标**:汇总条上加 Chip「+N 子任务」;N = 该父被折叠隐藏的后代数(现有 `hiddenByCollapse` 思路反向计数,任一祖先折叠即计入)。
- **今日线入视口**:rows 首次落位且 containerW 已测后,`todayX` 不在 `[scrollLeft, scrollLeft+containerW]` → `scrollRef.scrollLeft = todayX − containerW/2`(夹 ≥0);ref 守卫每会话一次,不与用户滚动/缩放打架。

### 2.4 测试计划

- **集成(AbstractApiTest,gantt 切片已有)**:建 item(有 start/due)→ 经 API 迁状态(START→ACTIVE→FINAL)→ 断言 gantt 行 `actualStart/actualEnd`;补负例:无迁移 item 两字段 null;checklist 增删后计数对齐;assignee 名回填。**写测试前先读 backend-testing skill**。
- **e2e(`web/e2e/gantt.spec.ts` 追加)**:fixture item 经 API 迁 ACTIVE → 断言实际条元素出现(data-testid 供断言);偏移徽标文本;折叠徽标;hover 卡出现。@smoke 不加(非核心路径,CI 冒烟不扩)。
- **单元**:推导逻辑(首迁非 START/FINAL、并列破序)放集成覆盖即可,service 无独立单元 seam(与既有 gantt 测试同口径)。

## 三、手工测试用例(用户 build/debug 阶段)

前置:test 环境(8082 + e2e 前端,或 dev 3001 手工)建带排期 item。

1. **实际条-进行中**:item 设 start/due → 看板迁入 In Progress → 甘特该行出现实际条(起点=迁入时刻取整到日,终点=今日线)。
2. **实际条-完成**:迁入 Done → 实际条终点=完成时刻;徽标 = actualEnd − due。
3. **提前完成**:due 定未来、立即完成 → 徽标绿 `−Nd`。
4. **延期进行中**:due 定昨天、迁 ACTIVE → 徽标红 `+Nd`,实际条过今日线无终点。
5. **偏移 0**:due=今天完成 → 无徽标。
6. **无 due**:只有 start 的 item → 实际条画、无徽标。
7. **进度遮罩**:item 加 4 条清单完成 1 条 → 实际条前 25% 深色遮罩;删清单 → 遮罩消失。
8. **hover 卡**:悬停实际条 → 卡片显示编号/标题/状态/负责人/计划/实际/清单;悬停行空白 → 无卡。
9. **折叠徽标**:父展开无徽标;折叠 → 汇总条「+N 子任务」,N 与隐藏行数一致;展开恢复。
10. **今日线入视口**:切到远期范围(如 fit 后拖远)刷新 → 自动滚到今日线;会话内只滚一次。
11. **回归**:里程碑菱形、父汇总条、FINAL 置灰、blocked 标记、范围过滤五档(220)、标题列拖宽(237)、缩放(219)全部不回归。

## 四、拍板结果(2026-11-19)

- **Q1 = 案 B 双行**(§2.2 实现)。agent 倾向 B,用户拍板一致。
- **Q2 = 同意**:遮罩宽度 = 清单完成率 %,时间分色读法废弃。
- **Q3 = 可**:配色重整纳入本票,按 §1.1.4 色表实现(参考图未立票,build/debug 阶段可调)。
- **Q4 = 可**:PRD v2 加年/季/月/周档位废弃标注(219 v2 spec 0140 已废,周/月为自动门控刻度行),一笔文档改,随本票提交。

## 五、As-built(2026-11-19)

实现与 spec 的差异与落地细节:

- **轴纳入实际日期(§2.1)保留**:逾期未完成 item 的实际条(延伸到今日线)依赖此语义;代价 = fit 视图在「老计划日期 + FINAL(now)」组合下整体压缩(A22,测试 fixture 已改近期动态日期;产品语义正确,勿回退)。
- **今日线入视口**:落地为「每次挂载至多一次」(ref 守卫),且仅无视点日(首次打开)时生效——比 spec 的「每会话一次」更保守,不与 sessionStorage 视点日恢复冲突。
- **偏移徽标定位**:`badgeLeft = max(计划条尾(叶)/菱形右侧(due-only), 实际条尾) + 6`;due-only 行按 §真值表不画实际条但徽标照出。
- **后端投影**:`ChecklistCount(itemId,total,done)` 构造器投影,列别名 snake_case 经命名策略映驼峰(同 Metrics*Row 模式);history 查询 ORDER BY item,created_at,id 保证破并列序。
- **实际条最小 3px**(与计划条同规则);actualStart=actualEnd(瞬时完成)亦 3px 可见。
- **本票顺手修复 A24**:api/build.gradle.kts 集成编译显式依赖 test 类(坏 build cache 事故的接线补丁)。
- 验证矩阵:`gradle build` ✓ / `integrationTest` 70 类 0 失败 ✓ / e2e 全量 113 ✓ / api.http 全绿 ✓ / 手工 11 例用户过 ✓。新增 e2e 1 例(gantt.spec.ts:实际条/偏移/遮罩 50%/折叠徽标/hover 卡)。
