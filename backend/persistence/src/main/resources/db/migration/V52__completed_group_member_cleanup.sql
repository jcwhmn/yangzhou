-- V52 / YPJ-158(spec 0109):清理 FINAL 组的 open 成员行中 item 非 FINAL 者(组先行 completed 后旧行非 live 永不删的存量残行)。
-- member 表 = 当前归属真相:被处置转出/回池的 item 不应再挂在完成组;Done 成员行保留(0106 D1)。
-- 删行 + 关闭 history open 区间(removed_at=now()),历史事实由 history 表承载。

-- ① 关闭 history open 区间
update item_group_member_history h
set removed_at = now()
from item_group g,
     item_group_type t,
     group_status gs,
     item i,
     status st
where h.group_object_id = g.object_id
  and h.removed_at is null
  and t.project_object_id = g.project_object_id
  and t.type_key = g.type
  and gs.group_type_id = t.id
  and gs.name = g.status
  and i.object_id = h.item_object_id
  and st.object_id = i.status_object_id
  and gs.status_type = 'FINAL'
  and st.status_type <> 'FINAL';

-- ② 删 member 行(与 ① 同条件)
delete from item_group_member m
using item_group g,
      item_group_type t,
      group_status gs,
      item i,
      status st
where m.group_object_id = g.object_id
  and t.project_object_id = g.project_object_id
  and t.type_key = g.type
  and gs.group_type_id = t.id
  and gs.name = g.status
  and i.object_id = m.item_object_id
  and st.object_id = i.status_object_id
  and gs.status_type = 'FINAL'
  and st.status_type <> 'FINAL';
