-- 测试库清空脚本:本地 yangzhou_test 残渣治理(spec 0021)
-- 表清单以 2026-10-09 pg_tables 实查为准;唯一保留 flyway_schema_history(Flyway 历史,删了会重放迁移炸库)
-- 手动执行:cd web && npm run db:reset(经 web/e2e/reset-db.mjs 驱动 docker exec psql)
TRUNCATE
  workspace, member, team, team_member, attribute_definition, capability,
  project, project_member, project_repo, project_workflow_rule,
  status, status_transition, item, item_activity, item_dependency, item_git_ref,
  item_group, item_group_member, checklist_item, comment, favorite,
  milestone, notification, requirement, time_entry
RESTART IDENTITY CASCADE;
