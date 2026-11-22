-- YPJ-226(spec 0163,2026-11-22 拍板):轴制式项目级可选;存量项目不动(=wall 墙钟制),新建项目 API 缺省 work(工作制)。
alter table project add column work_mode varchar(8) not null default 'wall';
alter table project add constraint ck_project_work_mode check (work_mode in ('work', 'wall'));
