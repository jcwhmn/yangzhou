-- JCW-140: V10-S2 item 优先级(spec docs/spec/0008-v10-depth.md)
-- P0-P3 可空;空 = 未设置

alter table item add column priority varchar(2);
alter table item add constraint ck_item_priority check (priority in ('P0', 'P1', 'P2', 'P3'));
