# yz — yangzhou CLI

`yz` 是 OpenAPI 契约的瘦客户端(API-first):管项目、管 item、自评能力、查可行性——不开浏览器管完一天。

## 构建

```bash
cd backend && gradle :cli:jar
# 产物:cli/build/libs/yz-<version>.jar(fat-jar,一命令运行)
```

## 快速开始

```bash
java -jar yz.jar login --server http://localhost:8080 -u me -p secret   # 全新服务器自动 bootstrap;会话存 ~/.yangzhou/session.json
java -jar yz.jar projects create CHE chess
java -jar yz.jar feasibility CHE
```

## 手工 smoke 清单(每版本跑一遍)

前置:API 已启动(`gradle :api:bootRun`),会话文件已删(模拟首登)。

- [ ] `login --server <URL> -u me -p secret` → `已登录:<URL>`;再跑一次(server 已初始化)→ 仍成功(自动 login)
- [ ] `attrs create Java` → `属性 Java(skill/分级)已创建`;`attrs create 后端 --kind label` → 类型 label 不分级
- [ ] `attrs list` → 表格含上述属性;`--json` → JSON 数组
- [ ] `caps set Java 4` → `已设置:Java = 4`;`caps set 架构`(无数字)→ `未评级`;`caps set Java 9` → 错误:等级只能是 1-4
- [ ] `caps list` → 表格(Java=4、架构=未评级);`caps rm 架构` → `已移除`
- [ ] `projects create CHE chess` → `项目 CHE 已创建`;重复 key → 409 错误
- [ ] `projects list` → 表格(含 CHE,三态 workflow)
- [ ] `items create CHE "架构决策落地" --req "Java>=3" --req "架构>=2"` → `已创建 CHE-1:...`
- [ ] `items create CHE "websocket" --req "Java"` → presence 需求(不带等级)
- [ ] `items list CHE` → 表格:编号/状态/标题/需求(`Java≥3,架构≥2`)
- [ ] `items move <itemId> "In Progress"` → `已迁移:CHE-1 → In Progress`(itemId 从 `items list --json` 取);非法状态名 → 错误并列出可用状态
- [ ] `feasibility CHE` → 聚合信号(✔/△/✗)+ 逐条判定行(形态同 DOMAIN.md:有余/差 N 级/缺能力/未评级)
- [ ] `feasibility CHE --json` → 与 API `/api/projects/CHE/feasibility` 同构 JSON
- [ ] `feasibility CHE --item <itemId>` → 单 item 判定
- [ ] 未登录(删会话文件后)任意命令 → `错误:未登录...`
- [ ] 中文与 ✓△✗ 在 Windows 控制台不乱码(CLI 强制 UTF-8 输出)

## 与 API 的关系

CLI 不含业务逻辑——所有校验(词表存在性、等级范围、状态迁移约束、防环)都在 API;CLI 只是编排与展示。新增能力先在 API(契约),CLI 加一行调用。
