-- spec 0111(YPJ-111):ItemGroup 固定两层嵌套——父引用同表 object_id(同 item 树模式);
-- 两层约束(父=level-2、子=level-1、父无父)走应用层校验(CHECK 做不了跨表 join,spec 0111 §2.1)。
alter table item_group add column parent_object_id uuid;

alter table item_group
    add constraint fk_item_group_parent foreign key (parent_object_id) references item_group (object_id);

create index ix_item_group_parent on item_group(parent_object_id);
