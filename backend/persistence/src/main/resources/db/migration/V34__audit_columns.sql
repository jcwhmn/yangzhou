-- spec 0051 / YPJ-66:audit 四元组补全(20 张表 + item 补 updated_by;item.created_by 已由 V15 建列)。
-- 行为者存 member.id(bigint,无 FK:workspace↔member 互引,加 FK 会成循环依赖,cleanDatabase 无法按序清(V15 的 item.created_by FK 除外,item 先于 member 清,无碍));nullable、不回填历史。

alter table workspace add column created_by bigint, add column updated_by bigint;
alter table member add column created_by bigint, add column updated_by bigint;
alter table team add column created_by bigint, add column updated_by bigint;
alter table team_member add column created_by bigint, add column updated_by bigint;
alter table comment add column created_by bigint, add column updated_by bigint;
alter table attribute_definition add column created_by bigint, add column updated_by bigint;
alter table capability add column created_by bigint, add column updated_by bigint;
alter table project add column created_by bigint, add column updated_by bigint;
alter table project_member add column created_by bigint, add column updated_by bigint;
alter table status add column created_by bigint, add column updated_by bigint;
alter table status_transition add column created_by bigint, add column updated_by bigint;
alter table checklist_item add column created_by bigint, add column updated_by bigint;
alter table project_repo add column created_by bigint, add column updated_by bigint;
alter table item_git_ref add column created_by bigint, add column updated_by bigint;
alter table project_workflow_rule add column created_by bigint, add column updated_by bigint;
alter table requirement add column created_by bigint, add column updated_by bigint;
alter table item_group add column created_by bigint, add column updated_by bigint;
alter table milestone add column created_by bigint, add column updated_by bigint;
alter table item_status_history add column created_by bigint, add column updated_by bigint;
alter table project_view add column created_by bigint, add column updated_by bigint;
alter table item add column updated_by bigint;
