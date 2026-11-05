-- spec 0100 切片A(YPJ-147):组类型层级化——level 原生列(每 level 恰一类型,唯一索引兜底)+ timed 时间盒开关;
-- sprint 去内置:存量回填 level=1/timed=true/builtin=false(语义保留,身份去内置);release 保持 builtin,level=NULL(非嵌套交付轴)。
alter table item_group_type add column level int;
alter table item_group_type add column timed boolean not null default false;

update item_group_type
   set level = 1,
       timed = true,
       builtin = false
 where type_key = 'sprint';

create unique index ux_item_group_type_project_level
    on item_group_type (project_object_id, level)
    where level is not null;

-- sprint 去内置后 type 列不再有隐式默认,插入必须显式指定(实体已去掉 Kotlin 默认)
alter table item_group alter column type drop default;
