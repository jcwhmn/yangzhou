# 0006 — 并发策略:不上 @Version,行锁 + 终写胜出

并发写同一聚合(如两人同时改一个 item)不做乐观锁(@Version):单用户系统冲突概率趋零,版本号列与全量 copy(...) 更新面带来的复杂度不值。

决策:采用**终写胜出(last-write-wins)+ 行锁兜底**——

1. 更新走 `repository.save(copy(...))`,后提交者覆盖先提交者,不报冲突;
2. 需要原子性的跨表/计数场景(如 item 编号分配、workspace 重算)用数据库行级锁(`SELECT ... FOR UPDATE` / advisory lock)串行化,冲突以 409 显式拒绝(V18 并发 PUT 500 的修复先例,spec 0022);
3. 并发回归由集成测试兜底(如 ItemsApiTest「并发创建编号不重不漏」),nightly 全量。

若未来演进为多用户协作(ADR-0005 的前提变化),重开本决策:届时先加 `updated_at` 前置比对(已有 audit 四元组,零迁移成本)再考虑 @Version。
