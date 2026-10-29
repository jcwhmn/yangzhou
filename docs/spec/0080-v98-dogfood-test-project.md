# spec 0080 — V25 YPJ-98 dogfood 测试项目(ItemGroup/workflow/webhook 多形态)

> 票:YPJ-98(2026-10-25 收口时立)。性质:**dogfood 验证票,不写产品代码**——建一个小测试项目,用真实管理动作压 ItemGroup、workflow 配置、GitHub webhook 三域的功能边界,发现喂后续票。

## 一、需求

### 范围
- 建测试项目 **DOG**(名「dogfood 多形态测试」),**保留不删**,作为后续功能(如 ItemGroup 层级元模型)落地的持续验证场地。
- 在 DOG 内按 §三 矩阵跑完五阶段,**管理动作优先走 yz CLI;CLI 未覆盖的走 REST**(python+urllib,token 复用 `~/.yangzhou/session.json`,脚本放 `.scratch` 即用即删)。
- 逐条记录**发现**:CLI 命令缺口、UX 摩擦、bug、契约疑问。每条标注处置(立新票 / 并入现有票 / defer / 不办),关票前处置完毕。
- 产出:矩阵执行记录 + 发现清单 → 本 spec As-built。

### 非目标
- 不修任何发现的 bug、不补 CLI 命令(均为另票;本票只记录)。
- 不做 ItemGroup parentType 层级(设计已对齐、未实现,另立大票)。
- 不动 Milestone/Release 语义(handoff 拍板:完全不动)。
- 不删 DOG 项目(与 YPJ-60 的删除能力验证解耦,那已在 api.http §15 覆盖)。
- 不验证错误 secret 403 等已由 spec 0073 集成测试覆盖的负例。

### 前置条件(开工确认)
- 8081 PM 实例在跑(default profile,库 `yangzhou`,env 带 `YANGZHOU_GITHUB_WEBHOOK_SECRET`——用户手起)。
- yz session 指向 8081(`yz items list YPJ` 已验证通)。
- GitHub 侧 webhook 已配置(spec 0073 手测实锤过:funnel URL + secret,events push/pull_request,指 8081)。

## 二、详细设计(dogfood 方案)

### 测试项目形态
- KEY `DOG`,名称「dogfood 多形态测试」。选中性流程型场景,让三类 ItemGroup 形态都有真实用武之地:
  - **sprint(内建)**:时间盒迭代,回归基线;
  - **phase(自定义,流程型)**:「设计/实现/收尾」阶段分组;
  - **quarter(自定义,日历型)**:「2026-Q4」带起止日期,验证 spec 0056 的时间盒最小语义。
- workflow 多形态:默认三态 + 自定义状态「评审」+ **受限迁移链**(待办→进行中→评审→完成),与 YPJ 的自由迁移形成对照。

### 执行方式
- CLI 可做的:projects create / items create / items move / items branch / assign / sprints list / export。
- REST 补的(group-types、groups、statuses、transitions、repos、sprint 建组):`.scratch/rest.py` 薄封装(urllib;读 session.json 取 token/基地址;stdout 包 TextIOWrapper utf-8,GBK 坑)。会话结束删脚本。
- 执行主体:agent 跑矩阵,关键节点(见 §三 标 ★)用户抽验。

### CLI 覆盖缺口预期(先记账,跑时确认)
group-types / groups / statuses / transitions / repos 均无 yz 命令 → 全部记为发现,处置建议「CLI 补齐」汇总一张票,不在本票实现。

## 三、测试矩阵(手工测试用例,即执行清单)

### 阶段 A:项目 + workflow 配置
| # | 步骤 | 期望 |
|---|---|---|
| A1 ★ | `yz projects create DOG dogfood 多形态测试` | 建成;详情见默认三态 workflow |
| A2 | REST `POST /projects/DOG/statuses` 加「评审」(position 3) | 201;项目详情四状态 |
| A3 | REST `PUT /projects/DOG/transitions` 配 待办→进行中→评审→完成 | 200;GET transitions 回读一致 |
| A4 | `yz items create DOG DOG-1 workflow 校验试验` → move 待办→进行中 | 合法迁移,过 |
| A5 ★ | move 进行中→完成(绕过评审) | 被 workflow 校验拒(4xx),DOG-1 状态不变 |
| A6 | move 进行中→评审→完成 | 走完整链,到完成 |

### 阶段 B:ItemGroup 多形态
| # | 步骤 | 期望 |
|---|---|---|
| B1 | REST 建 sprint「S1」(active);`yz sprints list DOG` | 可见;CLI 只读确认 |
| B2 | REST 建类型 phase「阶段」→ 通用组建组「设计」 | 成功;GET groups?type=phase 可见 |
| B3 | REST 建类型 quarter「季度」→ 建组「2026-Q4」带 startDate/endDate | 成功;时间盒字段回读一致 |
| B4 | 负例:重复 typeKey phase(409)/ 删 sprint 类型(409)/ 删有组的 phase 类型(409) | 三个 409 |
| B5 | `yz items create DOG ... --sprint S1` 挂 sprint;REST 把 DOG-1 加进组「设计」membership | 两处归属互不干扰(sprint 归属 + 自定义组成员并存) |
| B6 | 删 quarter 下无组场景演练:建类型 throwaway→建组→删组→删类型 | 全链成功(spec 0056 删除级联路径) |

### 阶段 C:成员/匹配轻验证
| # | 步骤 | 期望 |
|---|---|---|
| C1 | `yz members add` 虚拟成员 → REST 入 DOG 池 → `yz caps set` 自评 | 目录/池/能力就位 |
| C2 | DOG-1 挂需求属性 → `yz feasibility DOG-1` + `yz candidates DOG-1` | 非引擎语义(角色不进输入)在独立项目跑通 |

### 阶段 D:GitHub webhook(★全程用户观演)
| # | 步骤 | 期望 |
|---|---|---|
| D1 ★ | REST 挂 repo `jcwhmn/yangzhou` 到 DOG(复用服务端 PAT) | 挂载成功;同 repo 双项目挂载(YPJ + DOG)形态成立 |
| D2 | `yz items create DOG DOG-2 webhook 秒级试验` → `yz items branch DOG-2 test/DOG-2-webhook-probe --base main` → push 空提交 | ≤10s 内 DOG-2 详情出现分支记录(不等 5 分钟轮询);同时观察 YPJ 侧对同名分支的记录行为(双挂载差异记入发现) |
| D3 ★(可选,用户拍板) | 开 draft PR → item 转 In Review → 关闭 PR | PR 联动复验;主仓留一条已关 draft PR(可接受才执行) |

### 阶段 E:收尾
| # | 步骤 | 期望 |
|---|---|---|
| E1 | `yz export DOG` | JSON 全保真:自定义类型/组/受限 workflow/挂载全在 |
| E2 | 发现清单逐条处置(立票/并入/defer/不办) | 无悬空发现;As-built 完成 |

## 需用户开工前拍板
1. **D 阶段挂 `jcwhmn/yangzhou` 主仓**(默认方案):会在主仓留 `test/DOG-2-*` 分支痕迹(可删),D3 draft PR 可选;介意痕迹则改为你建一个 scratch repo 再挂。
2. 项目 KEY `DOG` / 名称 / **保留不删**,OK?
3. 自定义 workflow 状态名用中文「评审」,OK?
