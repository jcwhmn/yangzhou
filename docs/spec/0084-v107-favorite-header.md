# spec 0084 V43 YPJ-107 收藏入口进项目内页

票:YPJ-107。纯 web,2 文件,零 API 改动。

> **设计变更(2026-10-29 两轮拍板)**:先改顶栏「⭐ 收藏」下拉内加「加入收藏」切换项(已实现);同日用户确认**双入口**——项目内页 header 星标恢复,两处共享同一后端状态,互不广播。

## 一、需求

### 范围
- AppNav 的「⭐ 收藏」下拉菜单顶部加当前项目收藏切换项(仅在 `/p/{key}` 项目上下文显示):
  - 当前项目未收藏 → `☆ 加入收藏 {KEY}` → PUT `/api/projects/{key}/favorite`
  - 已收藏 → `★ 取消收藏 {KEY}` → DELETE(与列表内逐项「移除」并存,按钮语义对称)
- **项目内页 header 星标(双入口之二)**:`web/app/p/[key]/layout.tsx` 侧边栏项目 KEY 旁加星标 IconButton,项目下所有子路由页可见:
  - 挂载/key 变更时拉 `/api/favorites` 判定当前项目是否已收藏(失败静默视为未收藏)
  - 点星乐观切换 → PUT/DELETE,失败静默回滚;与下拉入口同源,无广播
- 操作后重拉 `/api/favorites`,列表即时反映;AppNav 已有"打开即重拉"机制,无需广播事件。
- 收藏状态判定用已加载的 favs 缓存(打开菜单时刷新),key 比较不区分大小写。

### 非目标
- 不改首页项目卡 ☆/★ 与下拉逐项「移除」的既有交互。
- 不做 loading 态/toast;失败静默(catch 空块,同既有 removeFav 约定)。PUT/DELETE 幂等,缓存陈旧误点无实害。
- 非 `/p/{key}` 页面不显示切换项(无当前项目上下文,无处可加)。

## 二、详细设计

改 3 文件:

1. `web/lib/texts.ts`:`t.fav` 加 `addCurrent: "☆ 加入收藏"`、`removeCurrent: "★ 取消收藏"`(文案外置)。✅ 已实现
2. `web/components/AppNav.tsx`:✅ 已实现
   - `projectKey` 已有(usePathname 解析,大写化);派生 `isFav = favs.some(f => f.key.toUpperCase() === projectKey)`。
   - Menu 顶部渲染切换按钮;`toggleCurrentFav`:按 `isFav` PUT/DELETE → `await loadFavs()`;catch 静默。
3. `web/app/p/[key]/layout.tsx`:header 星标
   - `const [fav, setFav] = useState<boolean | null>(null)`(null=加载中,按钮 disabled 防闪误点)。
   - 挂载/key 变更 effect 内拉 `/api/favorites` → `setFav(favs.some(f => f.key.toUpperCase() === key 大写))`;catch → `setFav(false)`。
   - KEY 文字后、TuneIcon 前插 `<IconButton size="small" aria-label="favorite" disabled={fav === null} onClick={toggleFav}>`,`fav ? <StarIcon/> : <StarBorderIcon/>`(MUI 图标,配该行既有图标风格)。
   - `toggleFav`:乐观 `setFav(!fav)` → PUT/DELETE,失败回滚 `setFav(fav)`(静默)。

否决的备选:
- 收藏后广播事件刷新首页/其它入口——各入口打开/挂载时自拉,无此需求。
- 已收藏时隐藏下拉切换项只靠列表「移除」——语义不对称,保留显式取消入口。
- 每页各自加星标——重复代码,N 处维护,否决;layout 一处覆盖全域。

## 三、手工测试用例(用户 build/debug 阶段执行)

1. 项目页下拉内加入→列表出现、按钮翻转;取消→移除、翻转回。
2. 项目侧边栏 KEY 旁星标:已收藏项目显示实心 ★,未收藏显示空心 ☆;点击切换,状态与下拉/首页卡片一致。
3. 项目内切换页面(overview→board→sprint)星标态保持不闪烁错方向;跨项目切换星标跟随当前项目。
4. 在首页(非项目页)打开下拉 → 无切换项;列表与「移除」照常。
5. 回首页项目卡 → 星标态与内页/下拉一致(同一后端状态)。

## As-built(完工后补)

- 实际改动:`web/lib/texts.ts`(+2 文案)、`web/components/AppNav.tsx`(isFav 派生 + toggleCurrentFav + 菜单顶部切换项)。
- 验证:手工用例 1–5 通过(待用户执行)。
