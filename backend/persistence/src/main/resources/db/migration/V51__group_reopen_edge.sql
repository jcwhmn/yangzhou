-- V51 / YPJ-157(spec 0108):timed 类型 completed 态加 reopen→active 出边(存量数据变更)。
-- 语义:重开 = 撤销内部收尾判断(纯状态迁移,成员行不动);release 终态(released)不命中;自定义循环模板 completed 已有 switch 出边,双重条件收窄不覆盖。
update group_status
set transitions    = '[{"function":"reopen","target":"active"}]',
    updated_at     = now()
where name = 'completed'
  and group_type_id in (select id from item_group_type where timed = true);
