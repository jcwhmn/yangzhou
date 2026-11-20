-- SRS v4 §3.2(2026-11-21 拍板):工作制缺省 24h 全天窗;实体缺省同步(Entity.kt)。
-- 既有项目存值不动(YPJ 由设置接口显式改 24h)。
alter table project alter column workday_start set default '00:00';
alter table project alter column workday_end set default '24:00';
