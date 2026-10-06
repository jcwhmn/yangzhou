-- YPJ-4: V15 Release(spec docs/spec/0012-v15-release.md)
-- Release = ItemGroup type='release'(PRD §7.3/§27):交付集合,membership 即成员事实
-- V27 注释预留「Phase 3 追加 'release' = 一条 ALTER」;§26.3 裁决:release.milestone_id 可空 N:1
-- planned→released 单向,支持事后补建(创建即 released,§8.2)

alter table item_group drop constraint ck_item_group_type;
alter table item_group add constraint ck_item_group_type check (type in ('sprint', 'release'));

alter table item_group drop constraint ck_item_group_status;
alter table item_group add constraint ck_item_group_status check (status in ('planned', 'active', 'completed', 'released'));

alter table item_group add column released_date date;
alter table item_group add column milestone_id bigint;

alter table item_group add constraint fk_item_group_milestone foreign key (milestone_id) references milestone (id);

create index ix_item_group_milestone on item_group(milestone_id);
