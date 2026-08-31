-- JCW-92: Linear 联邦(单向拉取)——外部引用
-- external_ref 格式:"linear:<identifier>"(来源前缀留扩展);同 project 内唯一(镜像不重复的 DB 保证)

alter table item add column external_ref varchar(200);

create unique index ux_item_project_external_ref
    on item (project_id, external_ref)
    where external_ref is not null;

create index idx_item_external_ref on item (external_ref);
