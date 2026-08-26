-- JCW-86: workflow 显式起点
-- 存量 'To Do' 状态回填为起点(默认 workflow 语义不变);position 回归纯排序。

alter table status add column is_start boolean not null default false;

update status set is_start = true where name = 'To Do';
