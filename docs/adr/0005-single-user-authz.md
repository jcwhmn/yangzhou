# 0005 — 授权模型:单用户全局权限

本系统当前为**单用户 + 虚拟成员**形态:登录账号(唯一有凭据的 member)对全部 Workspace/Project/Item 拥有无差别读写权,无 per-project ACL、无角色层级。虚拟成员是纯数据人(匹配引擎的 Capability 载体),不登录、不持有权限。

决策:显式不做权限矩阵,理由:单人全职开发,ACL 的管理成本远超收益;需求分级(min-level)与角色永不进匹配输入(ADR-0003)已覆盖"谁能做什么"的真实语义。

实现锚点:所有"当前操作者"语义收敛在 `MemberService.current()`(JWT subject → member)。写 API(service 层)凡需行为者,调 `current()`,不自行解析 SecurityContext——埋点即权限评估点:未来若引入多用户,在该方法收敛出 per-project 校验即可,调用面不改(spec 0051 埋点评估结论:current() 调用点约 20 处,全部在 service 层,收敛良好)。

审计配套:created_by / updated_by 记 member.id(spec 0051),为多用户演进留存操作者痕迹,当前恒为 "me"。
