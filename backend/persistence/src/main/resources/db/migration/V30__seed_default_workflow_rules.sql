-- YPJ-36:存量项目回填默认 workflow 规则(新项目在 ProjectService.create 同步种子,spec 0031)
-- 按状态名匹配,缺该状态的项目自然不种该槽;已手配的项目不覆盖(uk project_id+event_type 兜底)。
-- pr_closed_unmerged 有意不种:关闭未合并不回退,人管(票面裁决)。

insert into project_workflow_rule (object_id, project_id, event_type, status_object_id)
select gen_random_uuid(), s.project_id, 'branch_created', s.object_id
from status s where s.name = 'In Progress'
on conflict (project_id, event_type) do nothing;

insert into project_workflow_rule (object_id, project_id, event_type, status_object_id)
select gen_random_uuid(), s.project_id, 'pr_opened', s.object_id
from status s where s.name = 'In Review'
on conflict (project_id, event_type) do nothing;

insert into project_workflow_rule (object_id, project_id, event_type, status_object_id)
select gen_random_uuid(), s.project_id, 'pr_merged', s.object_id
from status s where s.name = 'Done'
on conflict (project_id, event_type) do nothing;
