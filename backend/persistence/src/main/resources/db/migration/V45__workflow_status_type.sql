-- YPJ-138(spec 0097 S1):Workflow 统一模型——status 三布尔 → statusType 单列;出边 JSON 内嵌,status_transition 表删除。
-- 契约破坏:isStart/isFinal/isInProgress 三布尔 → status_type(START/FINAL/ACTIVE,每状态恰属一类);
-- 出边迁移内嵌 status.transitions JSON(依附 status,不独立建表;存量无动作概念 → 全回填 switch)。

alter table status add column status_type varchar(20) not null default 'ACTIVE';
update status set status_type = 'START' where is_start;
update status set status_type = 'FINAL' where is_final;
-- 其余(含三值全 false 如 In Review)保持默认 ACTIVE
alter table status drop column is_start;
alter table status drop column is_final;
alter table status drop column is_in_progress;
alter table status add constraint ck_status_status_type check (status_type in ('START', 'FINAL', 'ACTIVE'));

-- 出边迁移内嵌为 JSON;自由迁移语义保留(空数组 = 全放行,DOMAIN.md 领域规则 3)
alter table status add column transitions text not null default '[]';
update status s
set transitions = coalesce((
    select jsonb_agg(jsonb_build_object('function', 'switch', 'target', ts.name) order by ts.name)
    from status_transition t join status ts on ts.object_id = t.to_status_object_id
    where t.from_status_object_id = s.object_id
), '[]'::jsonb)::text;
drop table status_transition;
