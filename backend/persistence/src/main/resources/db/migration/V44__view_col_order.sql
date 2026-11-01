-- YPJ-131: 视图增存列顺序(spec 0091 §6);可空,后端透传不解释
alter table project_view
    add column col_order text;
