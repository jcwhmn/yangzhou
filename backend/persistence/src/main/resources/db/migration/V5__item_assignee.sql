-- JCW-88: 候选建议 + 指派(V2 spec 0002)
-- 单 assignee,可空;成员删除时置空(人走了,事还在)

alter table item add column assignee_object_id uuid;

alter table item
    add constraint fk_item_assignee foreign key (assignee_object_id) references member (object_id) on delete set null;

create index idx_item_assignee on item (assignee_object_id);
