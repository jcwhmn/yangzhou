-- JCW-147: V11-S1 登录后进入上次项目(spec docs/spec/0009-v11-usability.md)
-- member.last_project_key 可空;看板页 mount 时写入;登录成功后读取跳转

alter table member add column last_project_key varchar(10);
