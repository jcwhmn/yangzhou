# 0117 — onBoard 语义收窄:仅无组项目;转换落 backlog;有组拉入=挂 sprint

> 日期:2026-11-13。上游:YPJ-172 收尾会话发现 109/156「幽灵票」(看板有、sprint/backlog 两无),用户逐步裁定。
> 性质:**领域语义变更**——收窄 spec 0099 引入的 onBoard 适用范围,消灭有组项目的「纯标记」第三态。

## ⚠ 显要标注(语义变更,推翻 0099 两个边界)

本 spec **推翻 0099 的边界 1(完成态留板靠标记)与边界 3(开始 sprint 不自动挂纯标记)的有组部分**,并收窄 onBoard 适用范围:

- **onBoard 仅服务于无组项目(S0)**——S0 没有任何组,票无家可归,标记是唯一进板通道(0099 初衷中仍成立的部分)
- **有组项目回到二元模型:item ∈ sprint ∨ backlog**,看板 = active sprint 成员(+空窗期 planned 成员,见 §二)
- 用户裁定原文(2026-11-13):①无组项目接受 onBoard;②「sprint 空窗」不成立(V28 收必有/创 V29,系统应构造性消灭悬空);③「完成后留板」不成立(Done 的历史归属是 completed sprint,收口自然滚出;S0 补集视图终态本来就在板上,不靠标记)

## 一、需求

### 1.1 背景

- V29 收尾发现:看板 4 张 To Do,V29 只有 2 张;YPJ-109/156 `onBoard=true` 且无 sprint,backlog 谓词 `!onBoard` 又把它们排除——三不管幽灵票
- 成因两路,均为 0099 规则的合法产物:①sprint 空窗期拉入(只置标记没挂上);②S0→有组转换无处理(S0 合法标记票原地变悬空)
- 根因:**「纯标记」态在有组项目没有生命周期管理**(无提醒、无兜底、视图不可见)

### 1.2 范围

1. **拉入分流**:`PUT /items/{id}/board-membership {onBoard:true}` 按项目是否已有 level-1 组类型分流——S0=置标记(现状零改动);有组=**不置标记**,挂最早 active sprint;无 active 则挂最早 planned sprint **并同事务激活它**(planned→active,复用 workflow `start` 出边);零 sprint 409 拒拉(2026-11-13 用户拍板:自动挂 planned 同时激活,构造性消灭空窗)
2. **转换触发**:项目创建**首个**组类型(获得 L1,即 S0→有组)的事务里,清该项目全部 `on_board` 标记——票落 backlog,排期归人
3. **看板取数**:有组(active 分支)去掉 `|| it.onBoard` 并集;降级分支(无 active)与 backlog 谓词**不动**(S0 仍依赖;有组项目标记恒 false 后自然空转)
4. **存量迁移 V56**:清所有「有 L1 组类型的项目」的标记(实际命中 prod 的 109/156)

### 1.3 非目标

- 不废 `on_board` 列(S0 仍用);不动 S0 拉入/退回行为与看板补集视图
- 不加看板「未排期」区、不改 backlog/sprint 页 UI(409 错误走既有错误透传)
- 不动退回(onBoard=false)逻辑:清标记+移出非 completed sprint 行,对二元模型天然正确
- 不处理「给项目删光组类型回到 S0」的逆向转换(无此流程,0099 加 level 票也未含)

## 二、详细设计

### 2.1 后端(backend/api `ItemGroupService`)

**boardMembership(拉入分支改造)**:

```kotlin
if (request.onBoard) {
    val l1 = level1Key(item.projectObjectId)
    if (l1 == null) {                       // S0:现状,置标记,无组可挂
        itemRepo.save(item.copy(onBoard = true))
    } else {                                // 有组:不置标记,挂 sprint
        val l1Groups = groups.findByProjectObjectIdOrderByCreatedAt(item.projectObjectId)
            .filter { it.type == l1 }
        val target = l1Groups.firstOrNull { it.status == "active" }
            ?: l1Groups.firstOrNull { it.status == "planned" }?.let { startGroup(it) }
            ?: throw ConflictException("无 active/planned sprint 可挂,请先创建 sprint")
        // 挂成员行(幂等,复用现有 rows.none 判断)+ memberHistory.recordAdd
    }
} else { /* 退回分支不动:清标记 + replaceMembership(groupId=null, groupType=l1) */ }
```

**startGroup(新增私有辅助)**:`workflow.applyAction(typeOfPid(pid, g.type), g.status, "start")` + `groups.save(g.copy(status = target))`——复用 sprint(timed)模板 planned→active 的 START 出边与白名单意图校验,同事务;不加 leader 门(拉入本身无门,现状语义;若要收紧另票)。自定义非 timed L1 工作流若无 `start` 出边 → applyAction 409,拉入拒(可接受:自定义拓扑的开关语义人定)。

**board()**:active 分支 filter 改 `itemService.list(key).filter { it.itemId in objectIds }`(去 `|| it.onBoard`);降级分支/backlogOnly 谓词零改动。

**createType(转换触发)**:`used.isEmpty()`(此前无任何用户类型,首类型必得 level=1)时,保存类型后同事务内批量清标记——`ItemRepository` 新增 `@Modifying @Query`(原生 SQL,同 0444 房风):`UPDATE item SET on_board = false WHERE project_object_id = :pid AND on_board = true`。

### 2.2 迁移 V56(`V56__onboard_s0_only.sql`)

```sql
-- spec 0117:onBoard 收窄至无组项目——有 L1 组类型的项目标记清零(存量幽灵票 109/156 落 backlog)
UPDATE item SET on_board = false
WHERE project_id IN (SELECT project_id FROM item_group_type WHERE level = 1);
```

无新表新列;cleanDatabase 表清单不动。

### 2.3 web(前端)

零改动。依据:拉入按钮/backlog 页/看板拉入对话框全部复用现有 PUT;409 文案由后端 message 透传既有 setError 通道;根页 S0 快速建票已按「无组概念」门控(page.tsx L238),不动。

### 2.4 测试

- **集成 `BoardMembershipApiTest` 扩展**(双轨纪律:extends AbstractApiTest,黑盒走 REST):①有组+active 拉入=成员行+`onBoard=false` ②有组无 active 有 planned 拉入=挂 planned **且 sprint 变 active** ③有组零 sprint 拉入=409 ④S0 拉入=纯标记(回归;需真 S0 项目——测试基建 `createProject` 默认补种 sprint 类型,须加参数跳过) ⑤S0 建首个组类型 → 该项目标记全清、票回 backlog ⑥转换清标记幂等安全(无标记项目建类型不炸)
- **实现级发现(编码时实锤)**:`AbstractApiTest.createProject` 恒补种 timed level-1 sprint 类型(spec 0100 切片B 后的测试形状)→ 既有测试项目全是有组项目;S0 用例需 `createProject` 加 `seedSprint: Boolean = true` 参数(默认不变,不影响其它测试)
- **单元**:无新纯函数,不加
- **e2e**:既有 board-membership 用例回归即可,行为不变(S0 项目用例)

### 2.5 api.http

PUT `/items/{id}/board-membership` 节补负例:有组项目无 sprint 拉入 → 409(末尾负例区,自含前置)。

### 2.6 否决备选

- **转换时自动挂首个 sprint**:批量代做排期决策,违背「排期归人」
- **保留标记+看板「未排期」区**:即用户不要的第三态,还搭 UI 工作量
- **废 on_board 列回 0098 补集视图**:S0 进板途径回归无解
- **触发点=首个 sprint 创建而非首个组类型**:类型建了 sprint 未建时项目已进二元世界(backlog/sprint 语义就位),晚触发会漏清

## 三、手工测试用例(用户 build/debug;test 实例)

1. **S0 项目**(新建无组项目):backlog 拉入 → 看板见票;退回 → 回 backlog(回归,行为应与现状全同)
2. **有组+active**:backlog 拉入 → 票进 sprint 成员列表、看板见(因成员非标记);`GET item` 核对 `onBoard:false`
3. **有组+空窗**(全部 sprint completed,有 planned):拉入 → 挂最早 planned **且该 sprint 自动激活(planned→active)**;看板立即见票
4. **有组+零 sprint**:拉入 → 409,前端显示「无 active/planned sprint 可挂…」
5. **转换**:S0 项目拉两票上板 → 创建首个组类型 → 两票出现在 backlog、看板标记消失;再建第二个类型 → 无变化
6. **迁移**:rebuild 后 YPJ 项目看板只剩 sprint 成员(2 张 To Do),109/156 出现在 backlog
7. api.http 负例区新例跑绿

## 四、拍板记录(2026-11-13 用户定)

- **拉入无 active 有 planned → 自动挂 planned 并激活该 sprint**(原两案「仅挂 planned」/「409 拒拉」均否;理由:拉入=「现在就要做」,顺手把下个 sprint 开起来,空窗态在系统里不再存在)
- 附加决策:激活不加 leader 门(拉入本身无门);自定义非 timed L1 无 `start` 出边 → 拉入 409
