# 0168 — YPJ-257 详情页上一条/下一条导航(按看板列序,SRS:需求+设计合一)

> 票:YPJ-257(item detail 页上一条/下一条导航,按看板列序)
> 上游:spec 0166(列内排序键口径)、spec 0098(board ∩ backlog = ∅)

## 一、目的与范围

详情页(`web/app/p/[key]/i/[itemId]/page.tsx`)支持在同看板列内 consecutive 浏览:**Ctrl+← 上一条 / Ctrl+→ 下一条**(2026-11-25 用户拍板,原「左右键」否决)。解决「详情页想看下一条才发现没实现」的诉求。

### 1.1 范围补充(2026-11-25 用户追加)
导航不限于看板列:票在 **backlog** 或 **level-1 group(planned sprint)** 内时,同样可前后浏览(各自容器内的序)。

### 1.2 非目标
- 跨桶导航(只在 item 当前所在容器内);
- 后端改动(board 端点现成,零后端);
- 列表/表格/甘特视图内的同类导航(另票再说)。

## 二、术语

| 术语 | 义 |
|---|---|
| 看板列序 | 同 spec 0166:status 分桶,列内 `boardOrder ?? MAX_SAFE_INTEGER` ASC → number 自然序(前端 `byBoardOrder`,YPJ-238) |
| 在看板 | item 出现在 `GET /api/projects/{key}/board` 的 `items` 里 |
| backlog 序 | `GET /api/projects/{key}/backlog`,number 自然序(后端 `OrderByNumber`,现有页无重排) |
| L1 组序 | `GET /api/projects/{key}/groups/{groupId}/items`,number 自然序(同上,组员无 position) |
| 导航桶 | item 当前所在容器,优先级:看板列 → backlog → L1 组(spec 0098:board ∩ backlog = ∅,planned 组成员两处皆不在) |

## 三、数据语义

- 数据源 = 现成 `GET /api/projects/{key}/board`(`ItemGroupService.board(key)` → `BoardDto{activeGroups, items}`),零后端改动;
- 桶解析(依次尝试,命中即停):
  1. **看板列**:`board.items` 含该 item → 同 `status` 过滤 → `byBoardOrder` 排序;
  2. **backlog**:`GET /backlog` 含该 item → number 序;
  3. **L1 组**:其余票(必在 planned L1 组,spec 0098)→ `GET /groups` 取 L1 类型组,逐组 `/groups/{id}/items` 找到含该 item 的组 → 该组 number 序;
- L1 组多挂(同一 item 在多个 planned L1 组):按组 `createdAt` 序取首个命中(⚠ 断言:正常流程一票只挂一个 L1 组,请人复核);
- 跳转 = `router.push('/p/{key}/i/{number}')`(编号 URL,详情页既有编号直达能力)。
- 跳转 = `router.push('/p/{key}/i/{number}')`(编号 URL,详情页既有编号直达能力)。

## 四、分支真值表

| 场景 | 行为 |
|---|---|
| Ctrl+→,当前有下一条 | push 下一(number URL) |
| Ctrl+←,当前有上一条 | push 上一(number URL) |
| Ctrl+→ 于列尾 | 无操作(不循环) |
| Ctrl+← 于列首 | 无操作(不循环) |
| 焦点在 input/textarea/contenteditable | 让位,不拦截(常识项,不拍板) |
| item 不在任何桶(理论上不存在:非终态票必在看板/backlog/L1 组之一;终态票在 Done 列) | 导航不可用(形态待拍板 ①:隐藏 vs 禁用) |
| 桶内仅 1 条 | 前后均无邻居,等价列尾/列首 |
| 连续按键(快速 consecutive) | 每次 push 即导航,目标页重新加载 board 定位新邻居 |

## 五、验收标准

- [ ] 三种桶内 Ctrl+→/← 均按各自序切换:看板列(boardOrder)/ backlog(number)/ L1 组(number);桶首/尾不循环;
- [ ] 文本编辑聚焦时不触发;
- [ ] 不在看板的票导航不可用;
- [ ] `npx tsc --noEmit` + `npm run format` 绿;
- [ ] e2e 覆盖主路径(切上一/下、列尾边界)。

## 六、开放决策(已拍板 2026-11-25)

| # | 事项 | 拍板 |
|---|---|---|
| 1 | 票无邻居可用时导航形态 | 隐藏(无桶/单条不渲染;单侧无邻居只隐对应按钮) |
| 2 | 是否配套可见 UI | prev/next 小按钮 + 「n / N」计数(标题行右端),键盘是加速器 |

## 七、详细设计(代码级修改计划,拍板后细化)

- `web/app/p/[key]/i/[itemId]/page.tsx`:
  - 加载 item 后追加 fetch 桶数据(按桶解析依次:board → backlog → groups+groups/{id}/items);
  - 派生 `prev`/`next`/`index`/`total`;
  - `useEffect` 全局 `keydown` 监听:`e.ctrlKey` + ArrowLeft/ArrowRight;target 为可编辑元素时直接 return;命中时 `router.push`;
  - 拍板 ② 选 b 则标题行旁加 MUI `IconButton`(ChevronLeft/Right)+ 计数文本,复用文案外置 `t`。
- 不动 backend、不动看板/backlog/组页。
