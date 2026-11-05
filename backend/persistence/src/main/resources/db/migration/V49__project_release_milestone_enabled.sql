-- V49 / YPJ-151:项目级 release/milestone enable 开关(默认开,存量无感;仅 UI 门控)
alter table project add column release_enabled boolean not null default true;
alter table project add column milestone_enabled boolean not null default true;
