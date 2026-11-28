-- spec 0177(S3/YPJ-299)§3.2:project_member 去角色化——池行回归纯池成员资格(可见性+item 级门),角色判定全走 member_role

-- backfill:既有 leader 行 → (member, editor, project) 行(父 spec §3.2;同 member+project 已有 editor 行则跳过)
insert into member_role (object_id, member_id, role, project_id)
select gen_random_uuid(), m.id, 'editor', p.id
from project_member pm
join member m on m.object_id = pm.member_object_id
join project p on p.object_id = pm.project_object_id
where pm.role = 'leader'
  and not exists (
      select 1 from member_role mr
      where mr.member_id = m.id and mr.role = 'editor' and mr.project_id = p.id
  );

-- 去角色化:池表不再承载角色(V39 的逆)
alter table project_member drop constraint ck_project_member_role;
alter table project_member drop column role;
