# 0071 — YPJ-84 item 详情页子条目区块

> 票:YPJ-84 · 影响:仅 `web/app/p/[key]/i/[itemId]/page.tsx`(或抽出小组件)
>
> 2026-10-24 用户拍板修订(二次):子条目是核心功能,**不做「依赖+清单」内区块、也不做页签,直接作为主页面直排区块,置于页签栏上方**(初版拍板的「独立页签」随后被用户纠正为「直接放主页面」)。

## 1. 需求

子 item(Y8 同质树,`parentItemId`)目前页面无处可见:侧边栏 Epic 段仅 `type=goal`,甘特图非默认视图,详情页无区块。本票在详情页主区域(页签栏上方)直排「子条目」区块,父 item 详情页直列其 children;页签保持原七区块不动。

### 范围

1. 「子条目」列表:编号、标题、状态 chip,点击跳子条目详情;空态文案「(无子条目)」;
2. 区块内「加子条目」:输入标题 → `POST /api/projects/{key}/items` 带 `parentItemId=当前 item` → 刷新列表;
3. 数据源:复用 `GET /api/projects/{key}/items` 全量列表,客户端 `parentItemId === itemId` 过滤(列表本就含该字段,不加后端端点)。

### 非目标

- 后端改动(不加 children 专用端点);侧边栏/甘特/看板任何变更;子条目树多层展开(只列直接 children,孙辈各自详情页再看);其余页签内容不动(仅新增一个页签)。

## 2. 详细设计

- 页签栏上方(`<Tabs>` 之前)插入「子条目」`<Box>` 区块(`mt: 3` 与上方留白一致);detailTabs 不变(七区块);
- 区块内容:`<Typography variant="h6">子条目</Typography>` + 列表 Stack + 行内新建;
- 列表行复用依赖区的行形态(Stack direction=row + Chip status + Link);
- 新建子条目:标题 TextField + 按钮,POST 后 reload 该页签数据;按钮沿用「加依赖」的小号 Button 风格;
- 不新建组件文件(区块小,与详情页同文件;吵了再拆)。

否决:后端 `/items/{id}/children` 端点(列表过滤足够,YAGNI);塞进「依赖+清单」页签;做成独立页签(2026-10-24 用户二次纠正:核心功能直接上主页面,不进页签)。

## 3. 手工测试用例

| # | 步骤 | 期望 |
|---|---|---|
| TC1 | 打开 YPJ-38 详情,页签栏上方 | 子条目区块列出 YPJ-80~83,状态 chip 正确,点击跳对应详情 |
| TC2 | 区块内新建「测试子条目」 | 列表即时多一行,详情页编号跳转正常;TC1 数据清理 |

## 4. 增量(2026-10-24 二次拍板:列表父前缀标识)

用户决策:① 子条目「加到 sprint 里」——现有语义已支持(sprint items = 已指派 items 全量过滤,子 item 指派后即出现;backlog 选择器也不过滤子条目),无需后端改语义;② 三列表页(sprint/table/backlog)补父前缀标识。

- 后端:`ItemDto` 新增 `parentNumber: String? = null`;`list()` 用同项目全量行内映射填充,`get()` 查父行填充;零 schema 变更(纯派生字段)
- 前端:sprint/table/backlog 三页编号列显示 `父编号 › 编号`;三页 Item type 加 `parentNumber?`
- 测试:ItemsApiTest 新增「列表与详情返回父编号前缀」(集成轨;merge 前本地 `gradle integrationTest`)
- 手测:看板/sprint/backlog 打开含子条目项目,子条目行编号列应显示 `YPJ-38 › YPJ-80` 形态;无父条目行不受影响

## 5. 增量(2026-10-24 三次拍板:父条目链接)

- 详情页:头部编号 Chip 前加父编号前缀链接(body2/primary,`YPJ-38 ›` 形态,与列表页一致;初版 outlined Chip 双 chip 并排被用户质疑易混,改为前缀链接保层级方向)——数据用 get() 已有的 parentNumber/parentItemId,零后端改动
- 看板卡片:编号 Chip 前加父编号 Link(caption/primary);页面 Item 类型补 parentItemId/parentNumber(看板数据源走 ItemDto,字段已在)
- 否决:父标题上卡片/详情——自身标题已占主要视觉,active-sprint 过滤下父条目可能不在看板数据里,点链接即达(YAGNI)
- 手测:开 YPJ-80 详情 → 头部见 YPJ-38 chip,点击达 YPJ-38;看板 YPJ-80 卡片编号前见 YPJ-38 › 链接
