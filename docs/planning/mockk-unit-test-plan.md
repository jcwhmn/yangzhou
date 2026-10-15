# mockk 单测计划:service 层全覆盖(滚动)

> 2026-10-14 立版(用户指示:所有 service 都要 mockk 单测,不必一次完成,分析后计划)。
> 约定真相源 = `.agents/skills/backend-testing/SKILL.md` 需求矩阵(service 业务分支/校验 → 单元测试,本计划是其执行排布)。
> 执行载体 = YPJ-43(滚动票,贯穿 sprint);本文件是清点与阶段划分,完成一项勾一项。

## 现状(2026-10-14)

- api 模块 service 共 **30 个**;mockk 依赖**未引入**;service 层单测 **0**。
- domain 引擎纯函数测试 4 个(Judge/MatchItem/RankCandidates/RollupProject);api 测试 43 文件全为 REST 黑盒集成。
- 约束(SKILL 既有):单测不起 Spring、不碰 DB/网络,类级并行安全;时间敏感用 Clock 注入;controller 不做 mock 单测(留黑盒集成,双轨边界不动)。

## P0 基建(一次性,挂 YPJ-43)

- [x] `api/build.gradle.kts` 加 `testImplementation("io.mockk:mockk")`(2026-10-15,1.13.13)
- [x] 首个样板:ItemService **全方法**单测(用户定案:nested inner class 按方法分组;`src/test/kotlin/yangzhou/api/item/`)(2026-10-15,`ItemServiceUnitTest`,11 方法 38 例:@Nested Create/ListItems/Get/Update/ReplaceRequirements/Assign/Delete/Restore/Purge/RecycleBin/Activity)
- [x] 确认单测任务名不变(`gradle test` 即 CI 冒烟)

## 阶段清点(30 个;节奏供 triage 参考,执行随 43 滚动)

### P1 核心域(7)——建议 V20

- [ ] ItemService(写路径/状态迁移分支最多,样板先行)
- [ ] ProjectService
- [ ] WorkflowService(迁移规则/状态 CRUD)
- [ ] FeasibilityService(引擎编排/信号重算)
- [ ] CandidateService
- [ ] MemberService
- [ ] ProjectMemberService

### P2 GitHub 与群组域(8)——建议 V21

- [ ] GithubSyncService(applyEvent 防呆分支:final 封口/只前进/WIP 跳过)
- [ ] GithubConfigService
- [ ] GithubBranchService
- [ ] GithubCommitService
- [ ] SprintService
- [ ] ReleaseService
- [ ] MilestoneService
- [ ] StandupService

安全敏感:AuthService / JwtService 的安全分支(过期/伪造/登录失败消息笼统)若提前做,优先于本阶段其他。

### P3 支撑域(15)——V22 起清尾

- [ ] AttributeService
- [ ] CapabilityService
- [ ] ChecklistService
- [ ] CommentService
- [ ] DependencyService
- [ ] DashboardService
- [ ] ExcelExportService
- [ ] FavoriteService
- [ ] NotificationService
- [ ] SearchService
- [ ] TeamService
- [ ] TimeEntryService
- [ ] WorkspaceService
- [ ] AuthService(安全分支)
- [ ] JwtService(安全分支)

## 每服务测试形态(样板约定)

- service 直接 ` XXXService(repo = mockk(), …)` 实例化;协作者 mockk-relaxed 按需;
- 覆盖:主路径 1 条 + 关键业务抛错分支(BadRequest/Conflict/NotFound)+ 时间行为(Clock.fixed);
- 不覆盖:透传 DTO 组装、getter 链;controller 层不测(mock)。
- 收尾机制:每实现票收尾顺手带 1–2 个 service;sprint 收口清点本清单。
- P0 实证坑(样板约定沿用):①relaxed mock 对泛型 `save(entity: S): S` 返回 Object 强转炸——save 类 stub 一律 `answers { firstArg() }`;②`every { repo.findByX(uuid) }` 里现造 UUID 与调用时不同实例,匹配器用 `any()`;③service 内部 `get()` 复用 `list()` 查询链,stub 要覆盖到(repo.findByProjectId… 返回该 item),否则走软删分支标记「[已删除]」;④service 构造实体的 objectId 是**新随机 UUID**(data class 默认值)——repo.save 桩不能直接回传实参顶掉行身份,须 `arg.copy(id=行id, objectId=行objectId)`,否则后续按 objectId 的查询桩不匹配,relaxed 返回子 mock(projectId=0)引发诡异 NPE/NotFound;

## 与既有票的关系

- 吸收 YPJ-38(mockk 单测方向票;38 关票时票面注明并入 43);
- 矩阵既有行「repo 仅自定义 SQL 才写集成测试」不变;本计划只新增 service 层单测,不改双轨。
