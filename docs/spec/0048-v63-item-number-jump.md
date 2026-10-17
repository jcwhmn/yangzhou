# 0048 — V63 / YPJ-63:item 编号直达(详情页编号 URL + 顶栏编号跳转框)

> 需求源:2026-10-17 会话——用户问「想看 YPJ-59 如何快速进入」。调查结论:`/search` 是孤儿页(全站零入口,大搜索后议);item 详情在列表页看见即可点,但凭编号无处可跳;详情路由只认 uuid。用户拍板:编号 URL 必须要 + 显性入口必须要,search 大功能后议。**纯前端,零后端改动。**

## 一、需求

### 背景
- 顶栏 `AppNav`(web/components/AppNav.tsx)现有入口:首页/能力/属性/成员/站会/回收站 + 铃铛/收藏下拉,**无搜索/跳转**。
- `/search` 页面存在且后端 `/api/search?q=` 工作正常,但全站无链接指向它。
- 详情路由 `web/app/p/[key]/i/[itemId]/page.tsx`(1142 行)所有数据请求用 `useParams().itemId`(uuid);编号传入时 `/api/items/YPJ-59` 后端 UUID 转换失败。

### 范围
1. **详情页兼容编号 URL**:访问 `/p/YPJ/i/YPJ-59` 正常渲染 item 详情,**地址栏保持编号**;刷新/收藏不坏;大小写不敏感(`ypj-59` 归一化);key 段与编号实际所属项目不符时自动纠正跳转。
2. **顶栏编号跳转框**:AppNav 加小输入框(placeholder 示例教用法),回车跳 `/p/{key}/i/{编号}`;非 `KEY-数字` 格式就地提示、不跳转。

### 非目标
- search 大功能(排序/全文/过滤/入口)——后议,`/search` 孤儿页现状不动。
- CLI 不动;后端零改动(api.http 无需同步)。
- 编号→uuid 的**后端**解析端点(不做,前端复用 search 即够)。

### 用户路径
- 发现性:用户**不需要记语法**——顶栏框 placeholder「编号跳转,如 YPJ-59」即教程;回车直达。
- 编号 URL 的价值:分享/收藏/从评论等文本里粘贴编号 URL 直接可开。

## 二、详细设计(代码级修改计划)

### 1. 详情页:编号解析 + 参数遮蔽(核心,~15 行)

`web/app/p/[key]/i/[itemId]/page.tsx`

```tsx
const UUID_RE = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
type SearchHit = { itemId: string; number: string; projectKey: string; /* search 页已有形状 */ };

function ItemDetailPage() {
  const { key, itemId: paramId } = useParams<{ key: string; itemId: string }>();
  const router = useRouter();
  const [resolvedId, setResolvedId] = useState<string | null>(
    UUID_RE.test(paramId) ? paramId : null,          // uuid 直通,零开销
  );
  const itemId = resolvedId ?? "";                    // ← 遮蔽:全页 ~30 处 api(`/api/items/${itemId}`) 零改动
  const [numNotFound, setNumNotFound] = useState(false);

  useEffect(() => {
    if (resolvedId || UUID_RE.test(paramId)) return;
    let dead = false;
    (async () => {
      const hits = await api<SearchHit[]>(`/api/search?q=${encodeURIComponent(paramId)}`);
      const hit = hits.find((h) => h.number.toUpperCase() === String(paramId).toUpperCase());
      if (dead) return;
      if (!hit) { setNumNotFound(true); return; }
      if (hit.projectKey !== key) {                   // /p/FOO/i/YPJ-59 → 纠正到正确项目 URL
        router.replace(`/p/${hit.projectKey}/i/${hit.number}`);
        return;
      }
      setResolvedId(hit.itemId);
    })();
    return () => { dead = true; };
  }, [paramId, key, resolvedId, router]);
  // 现有 load() 的 useEffect 依赖 [key, itemId]:itemId 为 "" 时不发请求(或首行 guard return)
  ...
  if (numNotFound) return <PageShell><Typography>{t.item.number_not_found}</Typography></PageShell>;
```

要点:
- **遮蔽技巧**:`itemId: paramId` 解构 + `const itemId = resolvedId ?? ""`,下游全部请求/操作函数不碰;`resolvedId` 就绪前 `itemId=""`,现有 `load` 的 useEffect 加一行 `if (!itemId) return` 守卫(或依赖数组天然等 resolvedId 就绪后重跑——按现依赖 `[key, itemId]` 会在 "" 时先跑一次,必须加守卫)。
- **否决 A**:解析后 `router.replace` 到 uuid URL——地址栏丢编号,违背「可读可收藏」目标。
- **否决 B**:把 ~30 处调用点改成显式 id 参数——大 diff 无收益,遮蔽一处即等效。
- key 不匹配走 `router.replace`(纠正 URL,重挂载后正常解析)。
- search 无精确命中 → `numNotFound` 态,展示未找到文案(新词条)。

### 2. 顶栏跳转框(AppNav,~20 行)

`web/components/AppNav.tsx`

```tsx
const [jump, setJump] = useState("");
const [jumpErr, setJumpErr] = useState(false);
const JUMP_RE = /^[A-Z]+-\d+$/;

function doJump() {
  const v = jump.trim().toUpperCase();
  if (!JUMP_RE.test(v)) { setJumpErr(true); return; }
  setJumpErr(false);
  router.push(`/p/${v.split("-")[0]}/i/${v}`);
}

<TextField size="small" placeholder={t.nav.jump_placeholder}
  error={jumpErr} helperText={jumpErr ? t.nav.jump_hint : undefined}
  value={jump} onChange={(e) => setJump(e.target.value)}
  onKeyDown={(e) => e.key === "Enter" && doJump()}
  sx={{ width: 190, "& .MuiFormHelperText-root": { whiteSpace: "nowrap" } }} />
```

- 放在 links 之后、铃铛/收藏之前;错误态就地提示(不弹窗)。
- 跳转目标即编号 URL,由详情页解析机制接管——**入口与解析解耦,各司其职**。
- 编号正则宽松(`[A-Z]+-\d+`),不存在的编号由详情页未找到态兜底。

### 3. 文案(web/lib/texts.ts,文案外置)

`t.nav` 增:`jump_placeholder: "编号跳转,如 YPJ-59"`、`jump_hint: "格式:项目KEY-编号"`;`t.item` 增:`number_not_found: "未找到该编号的 item"`。

### 4. e2e(web/e2e/number-jump.spec.ts,新文件,3 例)

fixture 走 helpers(独立 key 项目;新建项目首 item 编号恒为 `{KEY}-1`,确定性):

1. **跳转框主链**:API 建项目+item → 顶栏输入 `{KEY}-1` 回车 → 详情页标题可见,URL 含编号 → reload 仍渲染。
2. **编号 URL 直达**:`page.goto(/p/{key}/i/{KEY}-1)` → 详情可见。
3. **负例**:顶栏输入 `abc` 回车 → 格式提示可见;goto `/p/{key}/i/{KEY}-999` → 未找到提示可见。

不打 @smoke(导航辅助功能,merge 前本地全量覆盖)。

### 5. 不改的东西(自查)
- 后端零改动;api.http 无需同步(无新端点)。
- uuid URL 行为、所有列表页 `?from=` 跳转零触碰(遮蔽对 uuid 输入是恒等直通)。
- `/search` 页与大搜索不动。

## 三、手工测试用例(用户 build/debug 执行,web dev 或 8081 prod build 均可)

1. 顶栏输入 `YPJ-59` 回车 → 直达详情,地址栏 `/p/YPJ/i/YPJ-59`;F5 刷新不坏。
2. 新开标签直接粘贴 `/p/YPJ/i/YPJ-59` → 正常渲染。
3. 输入小写 `ypj-60` → 归一化直达。
4. 输入 `abc` 回车 → 输入框红 + 「格式:项目KEY-编号」提示,不跳转。
5. 输入 `YPJ-9999` 回车 → 详情位置显示「未找到该编号的 item」。
6. 访问 `/p/FOO/i/YPJ-59`(FOO 不存在)→ 自动落在 `/p/YPJ/i/YPJ-59`。
7. 回归:看板/表格/backlog 点 item → uuid URL 照旧;详情页内所有操作(改状态/评论/指派)正常(遮蔽未破坏任何调用点)。

## 验收清单(票面)
- [ ] 顶栏输入 YPJ-59 回车直达详情,地址栏保持编号
- [ ] 编号 URL 直接访问/刷新/收藏正常;大小写归一化
- [ ] key 不匹配自动纠正跳转
- [ ] 非法格式就地提示;不存在编号未找到提示
- [ ] uuid URL 与全部列表跳转回归不变
- [ ] e2e 3 例绿;全量 e2e + `tsc`/`next build` 绿
- [ ] spec 0048 补 As-built

## 设计修正(2026-10-17,e2e 2 例红后定案)

开工假设「纯前端、复用现有 /api/search 解析」有误:SearchService 只 ILIKE title/description,number 是读时拼接(key + "-" + number),**搜索从不匹配编号**——解析必空;/search 页搜「YPJ-59」同为空,属存量缺陷。

修正:SearchService SQL 补编号 ILIKE(`(p.key || '-' || i.number::text)`)+1 参数;新增 SearchApiTest 3 例(标题命中/编号命中含数字混合 key 与大小写/软删不命中);api.http 补全局搜索节。前端解析代码不变。偏离「零后端改动」——根因在共享层修一处,而非前端绕行。

## 补充迭代(2026-10-17,用户反馈;并入本票)

项目上下文预填:在 `/p/{key}` 页面内,顶栏跳转框预置 `"KEY-"`——只输编号回车即达本项目 item;清空前缀可跨项目输全号(如 `CHE-21`)。AppNav 按 `pathname` 解析项目 key,`useEffect` 同步预置与错误态清零;e2e 增预填用例 1 例。

## As-built(2026-10-17,PR #117 合并)

- **交付**:详情页编号 URL——`paramId` 非 UUID 时经 `/api/search` 精确匹配 number 解析,**参数遮蔽**(`const itemId = resolvedId ?? ""`)使全页 ~30 处请求零改动;地址栏保持编号可收藏/刷新;key 段不符自动 `router.replace` 纠正;未找到态复用 `!item` 早退块。顶栏跳转框(AppNav,正则 `[A-Z0-9]+-\d+`)+ 项目上下文预填 `KEY-`(pathname 解析 + useEffect 同步)。texts 增 3 条;e2e number-jump 4 例。
- **设计修正(前置节)**:`/api/search` 原本只 ILIKE title/description,number 为读时拼接——「复用 search 解析」假设落空;根因修在共享层(SQL 补编号 ILIKE + SearchApiTest 3 例,已随本 PR 进 main),`/search` 搜编号的存量缺陷随之修复。
- **实现坑(3)**:
  1. 跳转正则首版 `[A-Z]+-\d+` 漏数字 key——真实 key(CHE656626)与 E2E uniqueKey 都含数字,改 `[A-Z0-9]+-\d+`。
  2. 仓内 Playwright 版本无 `getByDisplayValue`,断言输入值用 `getByLabel(..., { exact: true })` + `toHaveValue`。
  3. 同面板连续点击会被落库重渲染吞掉(member.spec 色点 flake,快照实证),两次动作间插状态断言同步。
- **验证**:tsc 绿(release.spec 3 个错误为 main 既有,与本票无关)、`next build` 绿、e2e 全量 54 passed、双 profile 用户实弹验证(编号直达/预填)。
- **后续**:search 的 JDBC client 已由 0049 清退为 @Query(本票引入的 SQL 随迁)。
