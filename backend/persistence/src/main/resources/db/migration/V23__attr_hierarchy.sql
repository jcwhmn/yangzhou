-- JCW-139: V10-S1 feasibility 层次结构(spec docs/spec/0008-v10-depth.md)
-- 两层固定:kind='category' 行为分类根(parent 恒 null);skill/label 挂 parent_id(可空=未分类)
-- capability 仍只挂叶子;分类删除有子项 409

alter table attribute_definition add column parent_id bigint;
alter table attribute_definition add constraint fk_attr_parent
    foreign key (parent_id) references attribute_definition (id);

alter table attribute_definition drop constraint ck_attribute_definition_kind;
alter table attribute_definition add constraint ck_attribute_definition_kind
    check (kind in ('skill', 'label', 'category'));

create index idx_attr_parent on attribute_definition (parent_id) where parent_id is not null;
