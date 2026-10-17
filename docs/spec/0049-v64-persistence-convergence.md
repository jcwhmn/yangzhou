# 0049 — V64 / YPJ-64:持久层收敛——清退 JDBC client,全部 Spring Data JDBC

## ⚠ 架构破坏现状(置顶,按 2026-10-17 新规「破坏必须显要标注」)

**main 上三处 `JdbcTemplate/JdbcOperations` 直接违反 ADR-0004「@Query 唯一授权的持久层扩展点」**(repositories.kt:221 头注原文):

| 位置 | 用法 | 破坏来源 |
|---|---|---|
| `ItemService` | subtreeUpdate(软删/恢复)、purgeSubtree(物理删)、ensureNoCycle(防环游标) | 历史遗留,未标破坏 |
| `ProjectService` | deletion-preview(queryForMap)+ 九步级联 DELETE/UPDATE | **0046 spec 明文设计,埋在细节未标破坏** |
| `SearchService` | search(ILIKE 查询) | 历史遗留;0048 仅加一个分支,未标破坏 |

用户拍板三条:①永不用 JDBC client ②全部改 Spring Data JDBC ③破坏必须显要标注(已入 AGENTS.md,commit e144f47)。本票执行②。

## 一、需求

### 范围
1. repositories.kt 新增 @Query/@Modifying 接口(照 MetricsRepository 既有模式:标记接口 + 行投影类 + snake_case 列别名),覆盖三 service 全部 SQL。
2. 三个 service 删除 `jdbc` 构造参数,改注入新 repository;**对外方法签名不变,SQL 逐句平移零语义变化**。
3. 顺带修:subtreeUpdate 的 `deleted_at` 用字符串插值拼 SQL——改参数化(消插值面)。
4. AGENTS.md 铁律已另行提交 main(e144f47,待 push)。

### 非目标
- 不改 SQL 语义、不动 Flyway、不动 ADR 正文(repositories.kt 头注 + AGENTS.md 已载规则)。
- 零新增测试:纯行为保持重构,存量集成覆盖全链(ProjectDeletionApiTest 6 例、回收站软删/恢复/彻底删链、SearchApiTest 3 例、e2e 全量)。
- 不动 `/search` 与大搜索(后议票)。

## 二、详细设计(代码级修改计划)

### 1. repositories.kt 新增(三接口 + 三行投影,照 MetricsRepository 样式)

**Boot 4.0 = Spring Data JDBC 4.x,`@Modifying @Query` 支持**(org.springframework.data.jdbc.repository.query.Modifying)。

```kotlin
// ---------- YPJ-64 持久层收敛(spec 0049):JDBC client 清退,@Query 唯一扩展点 ----------

data class SearchHitRow(val itemId: UUID, val projectKey: String, val number: String, val title: String, val status: String?)

interface SearchRepository : Repository<Item, Long> {
    @Query("""SELECT i.object_id AS item_id, p.key AS project_key,
            p.key || '-' || i.number AS number, i.title, s.name AS status
            FROM item i JOIN project p ON i.project_id = p.id
            LEFT JOIN status s ON i.status_object_id = s.object_id
            WHERE (i.title ILIKE :q OR i.description ILIKE :q OR (p.key || '-' || i.number::text) ILIKE :q)
            AND i.deleted_at IS NULL ORDER BY p.key, i.number LIMIT 50""")
    fun search(@Param("q") q: String): List<SearchHitRow>
}

data class DeletionPreviewRow(val itemCount: Long, val groupCount: Long, val milestoneCount: Long,
                              val viewCount: Long, val statusCount: Long, val memberCount: Long)

interface ProjectDeletionRepository : Repository<Project, Long> {
    // 预览:一条 SELECT 六个标量子查询(逐句平移现 queryForMap)
    @Query("""SELECT (SELECT count(*) FROM item WHERE project_id = :pid)          AS item_count,
            (SELECT count(*) FROM item_group WHERE project_id = :pid)    AS group_count,
            (SELECT count(*) FROM milestone WHERE project_id = :pid)     AS milestone_count,
            (SELECT count(*) FROM project_view WHERE project_id = :pid)  AS view_count,
            (SELECT count(*) FROM status WHERE project_id = :pid)        AS status_count,
            (SELECT count(*) FROM project_member WHERE project_id = :pid) AS member_count""")
    fun deletionPreview(@Param("pid") pid: Long): DeletionPreviewRow

    // 九步级联:逐句平移,FK 依赖序不变;service 侧调用顺序即现状
    @Modifying @Query("DELETE FROM requirement WHERE item_id IN (SELECT id FROM item WHERE project_id = :pid)")
    fun purgeRequirements(@Param("pid") pid: Long): Int
    @Modifying @Query("DELETE FROM item_group_member WHERE item_id IN (SELECT id FROM item WHERE project_id = :pid) OR group_id IN (SELECT id FROM item_group WHERE project_id = :pid)")
    fun purgeItemGroupMembers(@Param("pid") pid: Long): Int
    @Modifying @Query("DELETE FROM item WHERE project_id = :pid")
    fun purgeItems(@Param("pid") pid: Long): Int
    @Modifying @Query("DELETE FROM item_group WHERE project_id = :pid")
    fun purgeItemGroups(@Param("pid") pid: Long): Int
    @Modifying @Query("DELETE FROM milestone WHERE project_id = :pid")
    fun purgeMilestones(@Param("pid") pid: Long): Int
    @Modifying @Query("DELETE FROM status WHERE project_id = :pid")
    fun purgeStatuses(@Param("pid") pid: Long): Int
    @Modifying @Query("DELETE FROM project_view WHERE project_id = :pid")
    fun purgeProjectViews(@Param("pid") pid: Long): Int
    @Modifying @Query("UPDATE member SET last_project_key = NULL WHERE last_project_key = :key")
    fun clearLastProjectKey(@Param("key") key: String): Int
    @Modifying @Query("DELETE FROM project WHERE id = :pid")
    fun deleteProject(@Param("pid") pid: Long): Int
}

interface ItemTreeRepository : Repository<Item, Long> {
    // 软删/恢复共用:at=null 即恢复(参数化,替换现 $rootId/$atClause 插值)
    @Modifying @Query("""WITH RECURSIVE subtree AS (
            SELECT id FROM item WHERE id = :rootId
            UNION ALL
            SELECT i.id FROM item i JOIN subtree s ON i.parent_object_id = s.object_id)
        UPDATE item SET deleted_at = :at WHERE id IN (SELECT id FROM subtree)""")
    fun updateSubtreeDeletedAt(@Param("rootId") rootId: Long, @Param("at") at: Instant?): Int

    @Modifying @Query("""WITH RECURSIVE subtree AS (
            SELECT id FROM item WHERE id = :rootId UNION ALL
            SELECT i.id FROM item i JOIN subtree s ON i.parent_object_id = s.object_id)
        DELETE FROM requirement WHERE item_id IN (SELECT id FROM subtree)""")
    fun purgeRequirementsInSubtree(@Param("rootId") rootId: Long): Int
    @Modifying @Query("""WITH RECURSIVE subtree AS (
            SELECT id FROM item WHERE id = :rootId UNION ALL
            SELECT i.id FROM item i JOIN subtree s ON i.parent_object_id = s.object_id)
        DELETE FROM item WHERE id IN (SELECT id FROM subtree)""")
    fun purgeItemsInSubtree(@Param("rootId") rootId: Long): Int

    // 防环游标:单查父链
    @Query("SELECT parent_object_id FROM item WHERE object_id = :objectId")
    fun findParentObjectId(@Param("objectId") objectId: UUID): UUID?
}
```

- **CTE 必须保留 `id, object_id` 两列**:递归成员 `JOIN subtree s ON i.parent_object_id = s.object_id` 依赖 CTE 的 object_id 列(父链走公共 id,非内部 id);首版平移曾收敛单列致 BadSqlGrammar,集成测试拦下(留档教训)。
- `findParentObjectId` 标量返回(SD JDBC 单列标量支持);若命名策略坑则退接口投影(实现期定,两种均为仓内已验证模式)。
- 行投影类与接口同文件放置(随 repositories.kt 既有惯例;吵了再拆)。

### 2. Service 改造

- **SearchService**:删 `JdbcOperations`;注入 `SearchRepository`;`search()` 行投影 → 现 `SearchResult` DTO 映射(字段一一对应;status 可空语义不变)。
- **ProjectService**:删 `JdbcTemplate`;注入 `ProjectDeletionRepository`;`deletionPreview` row→`DeletionPreviewDto`;`deleteProject` 九句调用顺序不动(末句用 `deleteProject(pid)`,不用 derived deleteById——避免引入「先加载后删」语义差)。
- **ItemService**:删 `JdbcOperations`;注入 `ItemTreeRepository`;`subtreeUpdate`/`purgeSubtree`/`ensureNoCycle` 改调 repo,方法签名与调用点不变。

### 3. 否决备选
- **自定义 repository fragment**(接口 + Impl 类):样板最多,能表达的全部 @Query 能表达——否决。
- **@Modifying 分布到各实体 CrudRepository**:动 6+ 个既有接口,集中独立接口最小 diff 且不扰存量——否决。
- **保留 jdbc client + 注释标注**:违反用户铁律①,否决。

### 4. 测试
零新增。安全网 = 存量集成(ProjectDeletionApiTest 6 例、回收站链、SearchApiTest 3 例)+ e2e 全量 + `gradle build`。

## 三、手工测试用例(用户 build/debug 阶段;8081 或 test profile 均可)

1. 回收站全链:item 软删 → 出现在回收站 → 恢复(deleted_at 清空)→ 再软删 → 彻底删除(物理消失,候选/看板无痕)。
2. 防环:把父 item 拖到自己的子 item 下 → 409「不能把 item 挂到自己的后代下」。
3. 项目删除链:api.http 16 节 preview 计数 → force 删除 → GET 404;被删项目虚拟成员可 rm,YPJ 引用成员 409。
4. search:`/search` 或顶栏跳转框搜标题词、搜 `YPJ-59` 编号均命中。
5. e2e 全量绿(含 number-jump 3 例)。

## 验收清单(票面)
- [ ] `git grep -e JdbcTemplate -e JdbcOperations -- backend/api/src/main` 归零
- [ ] 全量 integrationTest 绿;`gradle build --no-daemon` 绿;e2e 全量绿
- [ ] subtreeUpdate 无字符串插值(参数化)
- [ ] AGENTS.md 铁律在 main(随本票或已提交)
- [ ] spec 0049 补 As-built
