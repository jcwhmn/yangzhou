# 0046 — V20 收尾 / YPJ-60:项目删除能力(级联硬删 REST+CLI)+ member 删除引用检查

> 需求源:YPJ-59 清库 todo 的前置能力(2026-10-16 grill 拍板 5 项:形态=Java service+REST+CLI、硬删、防护在 CLI 层、member 复用 `members rm`+补 409 化、新立票本票)。**本票落地后用该能力执行 YPJ-59**(清 default 库 13 个垃圾项目,仅留 YPJ),执行验收归 YPJ-59。

## 一、需求

### 背景
api.http 曾误指 8081(default 库 yangzhou),留下 13 个垃圾项目(CHE*×11、SMK*×2)及配套垃圾成员(约 28 个虚拟成员)。现状:**project 没有任何删除路径**(无端点、无 archived 字段),`members rm` 存在但对 restrict 引用(评论/动态等)会以 DB 异常 500 而非业务 409 拒绝。

### 范围
1. **ProjectService.deleteProject(key)**:物理删除项目及全部从属数据,事务内按 FK 依赖序硬删;含软删(recycle bin)中的 item。
2. **REST**:`GET /api/projects/{key}/deletion-preview`(计数预览)+ `DELETE /api/projects/{key}`(204;404 未知 key)。
3. **CLI**:`yz projects delete <KEY> [--force]`——默认 dry-run 打印将删数据计数,**`--force` 才真删**(防护全在 CLI 层,拍板 Q3)。
4. **MemberService.delete 补引用检查**:评论/动态/状态历史/工时/视图/notification-actor 有引用 → 409 带计数(修复 500,拍板 Q4)。
5. **last_project_key 清引用**:删项目时把指向该 key 的 `member.last_project_key` 置 NULL(登录回退,否则跳 404)。
6. 测试 + api.http 同步。

### 非目标
- project 软删/回收站(拍板 Q2 硬删;item 级回收站不动)。
- Web UI 删除入口(CLI 足够;后加另票)。
- 批量删除端点(`--all-but-YPJ` 之类)——13 次 CLI 调用可审计,危险面不做大。
- GitHub 远端分支/repo 清理(只删库内 `project_repo` 行)。
- YPJ-59 执行本身(本票只交付能力)。

### 验收(票面)
- [ ] `yz projects delete KEY` dry-run 显示计数,`--force` 后项目及从属数据物理消失,GET 404
- [ ] 被删项目内活跃过的虚拟成员此后可被 `members rm` 删除;仍被 YPJ 引用的成员 rm → 409(非 500)
- [ ] `last_project_key` 指向已删项目的成员登录正常(回退,不 404)

## 二、详细设计

### 1. FK 依赖序(硬删核心)

依据 `docs/architecture` 实际迁移 FK 图(project 的 restrict 子 = status/item/item_group/milestone/status_transition/project_view,其余 cascade;item 子表除 requirement/item_group_member/parent 自引用外全 cascade)。Postgres 默认 NO ACTION 语句末校验 → 单语句删全量 item 安全(purgeSubtree 先例)。`item_group.milestone_id` restrict → item_group 先于 milestone。

`ProjectService.deleteProject`(`@Transactional`,注入 `JdbcTemplate`,参数化 project_id):

```sql
DELETE FROM requirement      WHERE item_id IN (SELECT id FROM item WHERE project_id = :pid);  -- restrict
DELETE FROM item_group_member WHERE item_id IN (SELECT id FROM item WHERE project_id = :pid)
                              OR group_id IN (SELECT id FROM item_group WHERE project_id = :pid); -- 双向 restrict
DELETE FROM item             WHERE project_id = :pid;   -- comment/git_ref/notification/time_entry/
                                                        -- dependency/checklist/status_history/igmh/activity 全级联
DELETE FROM item_group       WHERE project_id = :pid;   -- 先于 milestone(release=sprint 同表,一并走)
DELETE FROM milestone        WHERE project_id = :pid;
DELETE FROM status           WHERE project_id = :pid;   -- transition/workflow_rule 级联
DELETE FROM project_view     WHERE project_id = :pid;
UPDATE member SET last_project_key = NULL WHERE last_project_key = :key;
DELETE FROM project          WHERE id = :pid;           -- repo/rule/favorite/project_member 级联
```

- 软删 item 一并物理删(`WHERE project_id` 不过滤 deleted_at)→ 回收站随之清空。
- **否决**:存储过程(逻辑逃出 Kotlin、不可 AbstractApiTest 测试);repo 逐表 deleteAll(派生删除要给 6+ repo 加方法,一条 SELECT 子查询的 JDBC 更直白,同 ItemService.purgeSubtree 先例)。

### 2. deletion-preview

`GET /api/projects/{key}/deletion-preview` → 200 / 404。一条 JDBC 查询回计数(不给 6 个 repo 加 countBy):

```kotlin
data class DeletionPreviewDto(
    val key: String, val name: String,
    val itemCount: Long, val groupCount: Long, val milestoneCount: Long,
    val viewCount: Long, val statusCount: Long, val memberCount: Long, // project_member 行数
)
```

### 3. Controller

`ProjectController` 加两个方法(`@DeleteMapping("/projects/{key}")` → 204 NoContent;`@GetMapping("/projects/{key}/deletion-preview")`),复用类级 `@RequestMapping("/api")`。OpenAPI `@Tag` 沿现状。

### 4. MemberService.delete 增强

现有逻辑(登录账号不可删、进行中 assignee item 409)不动,其前追加 restrict 引用检查,有则 409 汇总提示:

| 引用 | 派生查询(新增) |
|---|---|
| comment.author_member_id | `CommentRepository.countByAuthorMemberId(Long)` |
| item_activity.actor_member_id | `ItemActivityRepository.countByActorMemberId(Long)` |
| item_status_history.actor_member_id | `ItemStatusHistoryRepository.countByActorMemberId(Long)` |
| time_entry.member_id | `TimeEntryRepository.countByMemberId(Long)` |
| project_view.creator_id | `ProjectViewRepository.countByCreatorId(Long)` |
| notification.actor_member_id | `NotificationRepository.countByActorMemberId(Long)` |

提示形如:`该成员仍有引用数据(评论 2、动态 5、工时 1),先删除相关项目或数据`。capability 随删、team_member/project_member/notification(recipient)/favorite FK 级联,均现状不动。

### 5. CLI(Main.kt)

`"projects delete" ->` 分支:无 `--force` → GET preview 打印中文计数清单 + 「加 --force 执行物理删除」;`--force` → `DELETE /api/projects/{key}` 成功打印「已删除」。usage 增补一行。**否决**:REST 加 confirm 参数(单用户系统,DELETE 语义保持纯净)。

### 6. api.http

project 区块加序号条目:`GET /api/projects/{{projKey}}/deletion-preview`、`DELETE /api/projects/{{projKey}}`(注:跑完删了 projKey,后续条目用例需重建项目,条目注释写明);负例区补「删未知 key → 404」「preview 未知 key → 404」。

### 7. 测试设计(集成 extends AbstractApiTest,REST 黑盒)

- **删除主链**:建项目 B + 父子 item + 评论 + 视图 + sprint group + milestone;软删一个 item(进回收站)→ `DELETE /api/projects/B` → 204;GET B → 404;recycle-bin 空;B 的 items 404;YPJ(对照项目)不受影响。
- **成员可删链**:B 内建虚拟成员并产生评论 → 删 B 前 `DELETE` 该成员 409(引用计数);删 B 后同成员 → 204。
- **last_project_key**:成员 lastProjectKey=B → 删 B → me 接口返回 null。
- **preview**:计数与实建数据一致;未知 key 404。
- **409 化**:对 YPJ item 写评论的虚拟成员 → DELETE member → 409(修复前 500);仍指派进行中 item 的成员 → 409(现状回归)。

## 三、手工测试用例(用户 build/debug 执行,server 指 8081)

1. `yz projects delete CHE656626` → 打印计数,**库未变**(projects list 仍 14)。
2. `yz projects delete CHE656626 --force` → 已删除;projects list 13;web 项目列表/看板正常。
3. 13 个垃圾项目清完 → projects list 仅剩 YPJ;全库巡检项目数=1(**YPJ-59 验收**)。
4. `yz members rm 小李046619` → 成功;`yz members rm <被 YPJ 引用的成员>` → 409 带计数提示;`yz members rm me` → 409(登录账号,现状回归)。
5. 曾把 lastProjectKey 设为垃圾项目的账号重新登录 → 正常进入,不 404。
6. api.http preview/DELETE 链 + 负例全过;全量 e2e 绿。
