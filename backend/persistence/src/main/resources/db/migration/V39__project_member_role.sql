-- YPJ-102 Leader 权限模型(spec 0081):项目成员池加 role 列
-- leader = 池内 role='leader' 行(可多个);存量项目创建者(若在池内)自动 leader,
-- 无对应池行的项目不设 leader → R2 兼容语义(无 leader 项目登录即可管)。

alter table project_member
    add column role varchar(20) not null default 'member';

alter table project_member
    add constraint ck_project_member_role check (role in ('member', 'leader'));

update project_member pm
set role = 'leader'
from project p
where p.id = pm.project_id
  and p.created_by is not null
  and p.created_by = pm.member_id;
