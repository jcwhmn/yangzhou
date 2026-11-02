# 0094 — v133 yz CLI:items 评论 + sprints create(YPJ-133)

> 2026-11-02 立项。背景:给 YPJ-130 留停置评论时发现 CLI 无评论子命令;且当日的 sprint 开立(建→激活→挂票)全程无 CLI 可用。API 端点均已存在,本票纯补 CLI 瘦壳,**零 REST API 改动**(api.http 不动)。

## 1. 需求

### 范围

- `items comment <KEY-N|itemId> <文本...>`:给 item 加评论(POST `/api/items/{itemId}/comments`,body `{body}`)
- `sprints create <KEY> <名称>`:建 sprint(POST `/api/projects/{key}/sprints`,body `{name}`;planned 起步)
- usage 文本同步;SmokeTest usage 锚点同步(spec 0090 约定:新命令合并时须加锚点)

### 非目标

- 评论的 list / delete(API 有,web 已覆盖;CLI 只补「写」的缺口)
- sprint 日期参数(`--start/--end`,CreateSprintRequest 支持,需要时再加)
- CLI 命令面其余缺口(评估见 §4,另行拆票排期)

## 2. 详细设计(代码级)

| 文件 | 改动 |
|---|---|
| `cli/Main.kt` usage | 加两行命令说明 |
| `cli/Main.kt` dispatch | 两分支:`"items comment"`(rest[0]=item 引用,rest.drop(1).joinToString(" ")=文本,空则报错)→ `api.json("POST", "/api/items/$id/comments", mapOf("body" to text))`,打印 `已评论:YPJ-N`;`"sprints create"`(rest[0]=KEY,rest.drop(1).join=名)→ `api.json("POST", "/api/projects/$key/sprints", mapOf("name" to name))`,打印 `已建 sprint:<名>(planned)` |
| `cli/SmokeTest.kt` | 锚点数组加 `"items comment"`、`"sprints create"`;补一例缺参先于网络(`items comment` 无文本 → 非 0) |

**否决备选**:命令名 `comments add`(名词族与现有 items/sprints 不一致)✗;评论文本走 `--text` flag(位置参数更顺手,PowerShell 引号负担同现有 --desc)✗;本票顺带 items show(见 §4,另行拆票)✗。

## 3. 手工测试用例(用户 build/debug 执行)

1. `java -jar yz-*.jar items comment YPJ-130 "停置于 2026-11-02,并入表格搜索系统,见 docs/planning/table-view-search/"` → 打印已评论;web 打开 YPJ-130 详情可见该评论
2. `sprints create YPJ "V27 工作流与地基"` → 打印已建(planned);`sprints list YPJ` 可见
3. 缺文本:`items comment YPJ-130`(无文本)→ 非 0 退出报缺参,无网络请求

## 4. CLI 命令面缺口评估(本票附带产出,拆票另行排期)

| 缺口 | 价值 | 建议 |
|---|---|---|
| `items show <KEY-N>`(单票详情) | 高——考古/脚本取状态必用,现只有 list | 下一票优先 |
| `items comments <KEY-N>`(列评论) | 中——只读考古 | 与 items show 同票 |
| `milestone list` / `release list` | 中——与 sprints list 同构只读 | 低优 |
| `items open <KEY-N>`(开浏览器到详情) | 中——CLI↔web 跳转 | 低优 |
| `attrs rm / rename`、checklist、time 登记族 | 低-中——web 已覆盖主路径 | defer |
| dashboard / metrics / view / notification | 低——web 交互性强,CLI 价值存疑 | 不做,除非脚本化需求出现 |
