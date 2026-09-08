-- JCW-104 W1: WIP 限制(列级别,超出禁止拖入)
alter table status add column wip_limit integer;

alter table status
    add constraint ck_status_wip_limit check (wip_limit is null or wip_limit > 0);
