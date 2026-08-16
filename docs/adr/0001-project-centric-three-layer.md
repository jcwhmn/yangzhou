# 0001 — Project 为中心的容器模型(不采用 Linear 的 team-centric)

业界存在两种容器模式:Jira/Mantis/kaneo 以 **Project** 为编号与 workflow 容器;Linear 以 **Team** 为编号容器,Project 降级为 Team 内集合。基于四代真实用例分析(Linear 个人多项目、Jira 企业多团队、华为项目矩阵、Mantis 单机)决定采用三层模型:**Workspace**(隐形租户,持有 Member 目录与属性词表,保证人员可跨 Project/Team 共享)/ **Project**(编号序列 + workflow + 视图的容器,用户世界观的中心)/ **Team**(仅成员分组,匹配引擎的规模旋钮,不管事)。

理由:①个人多项目在 Linear 模式下编号失去项目辨识(实测痛点);②矩阵式组织要求人员在上层共享;③企业形态由多 Project + Team 组合表达,v2 无需重写。

Consequences:workflow 的家在 Project——需要独立 workflow 的团队必须拥有独立 Project;单 Project 多 Team 场景下 workflow 按 Project 统一,团队用视图过滤。
