-- JCW-129: V9-S1 读模型冗余(feas_signal,spec docs/spec/0007-v9-read-model-collab.md)
-- signal = 当前唯一登录成员视角(V9-Q14);null = 无 item 未计算

alter table item add column feas_signal varchar(10);
alter table project add column feas_signal varchar(10);

alter table item add constraint ck_item_feas_signal
    check (feas_signal in ('GREEN', 'YELLOW', 'RED'));
alter table project add constraint ck_project_feas_signal
    check (feas_signal in ('GREEN', 'YELLOW', 'RED'));
