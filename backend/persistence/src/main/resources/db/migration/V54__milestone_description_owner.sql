-- spec 0116(YPJ-170):Milestone 页面化 P1 票 1——目标/描述与负责人字段(US-M6)
-- 两列均可空,无回填;owner 引 member.object_id(语义同 item assignee,人删除时置空)
alter table milestone add column description text;
alter table milestone add column owner_object_id uuid;

alter table milestone
    add constraint fk_milestone_owner foreign key (owner_object_id) references member (object_id) on delete set null;
