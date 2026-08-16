# 0003 — 统一属性体系,授权正交

匹配输入的属性建模有两种方式:独立实体(Label 表、Skill 表、将来的 Certificate 表,各自代码路径)或统一实体。观察到"无等级的 skill 匹配"与"label 匹配"语义同源(均为 presence),等级只是一个开关,独立实体是人为复制。决定:**一个 Attribute 实体**——`kind` 细分(label/skill/…,纯 data)+ `leveled` 开关,词表住 Workspace 级;item 侧附 **Requirement** `(attribute, min-level?)`,member 侧附 **Capability** `(attribute, level?)`。label/skill 是预置 kind 而非独立实体,新增属性类型 = 加数据,不加代码路径。

同时裁定:**授权(RBAC,"谁可以做")与属性体系("谁做得好")正交分离**,Role 不作为匹配输入——否则"能做但做得差"无法表达。

Consequences:匹配引擎只有一条输入路径;失去属性类型级的编译期检查(可接受的代价);RBAC defer 到多人阶段,schema 预留。
