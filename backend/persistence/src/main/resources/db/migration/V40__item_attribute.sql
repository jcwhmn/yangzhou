-- YPJ-103 item 自定义属性(spec 0082):attribute_definition 词表第三挂载
-- kind 加 custom(描述性字段);item_attribute 值表与 requirement 同构(交叉挂载,永不进匹配引擎)。

alter table attribute_definition drop constraint ck_attribute_definition_kind;
alter table attribute_definition add constraint ck_attribute_definition_kind
    check (kind in ('skill', 'label', 'category', 'custom'));

create table item_attribute (
    id                      bigint generated always as identity primary key,
    object_id               uuid         not null,
    item_id                 bigint       not null,
    attribute_definition_id bigint       not null,
    value                   varchar(500) not null,
    created_at              timestamptz  not null default now(),
    updated_at              timestamptz  not null default now(),
    created_by              bigint,
    updated_by              bigint,
    constraint uk_item_attribute_object_id unique (object_id),
    constraint uk_item_attribute_item_attr unique (item_id, attribute_definition_id),
    constraint fk_item_attribute_item foreign key (item_id) references item (id) on delete cascade,
    constraint fk_item_attribute_attr foreign key (attribute_definition_id) references attribute_definition (id)
);

create index idx_item_attribute_item on item_attribute (item_id);

-- 属性变更留痕(同 requirement_changed 惯例)
alter table item_activity drop constraint ck_item_activity_kind;
alter table item_activity add constraint ck_item_activity_kind
    check (kind in ('created', 'status_changed', 'title_changed', 'description_changed',
                    'assigned', 'unassigned', 'requirement_changed', 'github_status_changed',
                    'github_branch_created', 'dates_changed', 'attribute_changed'));
