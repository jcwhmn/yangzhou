-- V50 / YPJ-152(spec 0103):清理存量项目中无组的残留 sprint 类型(数据变更:物理删行,用户拍板)
-- 来源:V48 保留语义未删行(spec 0100 切片A);0101 案B 后,无组但有 level-1 类型的项目
-- 侧栏仍显示 sprint 段,违背「sprint 概念完全用户配置」原则。
-- 删除条件 = 删除守卫语义(0100 拍板:builtin||hasGroups)的自动化版:有组不动,release 不动;
-- 新项目不再种 sprint(0100 切片B),迁移后世界不会再产生该形态。
delete from item_group_type t
where t.type_key = 'sprint'
  and not exists (
    select 1 from item_group g
    where g.project_object_id = t.project_object_id
      and g.type = t.type_key
  );
