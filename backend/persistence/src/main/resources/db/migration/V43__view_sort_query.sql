-- YPJ-127: 视图增存排序与搜索词(spec 0091 §5);可空,后端透传不解释
alter table project_view
    add column sort text,
    add column query text;
