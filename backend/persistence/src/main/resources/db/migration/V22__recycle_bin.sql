-- JCW-134: V9-S6 回收站(item 软删,spec docs/spec/0007-v9-read-model-collab.md)
-- deleted_at 可空;非空 = 已删(全列表类查询过滤);恢复 = 清空;物理删 = 真删(级联照旧)

alter table item add column deleted_at timestamptz;
create index idx_item_deleted_at on item (deleted_at) where deleted_at is not null;
