-- YPJ-165(spec 0131):移除旧 Release 组实现(PRD v1.2 §0:Release 独立实体重建的前置)
-- 存量处置拍板(PRD §12.3):A 不迁移——type='release' 组行及成员关系废弃删除,不转换
-- release_enabled(project 列,V49)保留,独立 release 实体(US-R1+)复用
-- 注意:V41 已把 milestone_id 改造为 milestone_object_id(FK 改名 fk_item_group_milestone_object_id,
-- 索引 ix_item_group_milestone 重建在 milestone_object_id 上),本迁移按 V41 后形态拆

delete from item_group_member_history where group_object_id in (select object_id from item_group where type = 'release');
delete from item_group_member where group_object_id in (select object_id from item_group where type = 'release');
delete from item_group where type = 'release';

alter table item_group drop constraint fk_item_group_milestone_object_id;
drop index ix_item_group_milestone;
alter table item_group drop column milestone_object_id;
alter table item_group drop column released_date;
