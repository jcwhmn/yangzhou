# Instructions

- Following Playwright test failed.
- Explain why, be concise, respect Playwright best practices.
- Provide a snippet of code with the fix, if possible.

# Test info

- Name: core.spec.ts >> 详情——编辑需求入口打开对话框
- Location: e2e\core.spec.ts:161:5

# Error details

```
Error: expect(page).toHaveURL(expected) failed

Expected: "http://localhost:3000/"
Received: "http://localhost:3000/login?"
Timeout:  5000ms

Call log:
  - Expect "toHaveURL" with timeout 5000ms
    13 × locator resolved to <html lang="zh-CN">…</html>
       - unexpected value "http://localhost:3000/login?"

```

```yaml
- heading "扬州 yangzhou" [level=5]
- text: 用户名
- textbox "用户名"
- text: 密码
- textbox "密码"
- button "登录"
- text: 全新服务器将以首个账号自动初始化
```

# Test source

```ts
  1   | import { expect, test } from "@playwright/test";
  2   | 
  3   | /**
  4   |  * 核心链路(serial):登录 → 建项目 → 建 item → 评论 → 切状态 → 活动日志 → 看板 → 通知页。
  5   |  * 断言走用户可见文案(中文)。数据隔离:随机项目 key,不清库。
  6   |  * 前置:本机 Postgres(共享 compose)+ 后端 `gradle :api:bootRun`(8080);Next dev 由 webServer 拉起(已跑则复用)。
  7   |  */
  8   | 
  9   | const KEY = `E2E${Date.now() % 100000}`;
  10  | 
  11  | test.beforeAll(async ({ request }) => {
  12  |   // 后端在场检查 + 空库种子(CI 是全新库):bootstrap;已有用户则 409 → 登录确认
  13  |   let ok = false;
  14  |   try {
  15  |     const boot = await request.post("http://localhost:8080/api/auth/bootstrap", {
  16  |       data: { username: "me", password: "secret" },
  17  |     });
  18  |     ok = boot.ok() || boot.status() === 409;
  19  |     if (ok) {
  20  |       const login = await request.post("http://localhost:8080/api/auth/login", {
  21  |         data: { username: "me", password: "secret" },
  22  |       });
  23  |       ok = login.ok();
  24  |     }
  25  |   } catch {
  26  |     ok = false;
  27  |   }
  28  |   if (!ok) {
  29  |     throw new Error("后端未就绪:请先起本机 Postgres 并运行 `gradle :api:bootRun`(8080),再跑 npm run e2e");
  30  |   }
  31  | });
  32  | 
  33  | async function login(page: import("@playwright/test").Page) {
  34  |   await page.goto("/login");
  35  |   await page.getByLabel("用户名").fill("me");
  36  |   await page.getByLabel("密码").fill("secret");
  37  |   await page.getByRole("button", { name: "登录" }).click();
> 38  |   await expect(page).toHaveURL("/");
      |                      ^ Error: expect(page).toHaveURL(expected) failed
  39  | }
  40  | 
  41  | test("登录进首页", async ({ page }) => {
  42  |   await login(page);
  43  |   await expect(page.getByRole("heading", { name: "项目", exact: true })).toBeVisible();
  44  | });
  45  | 
  46  | test("建项目并打开看板(默认五列)", async ({ page }) => {
  47  |   await login(page);
  48  |   await page.goto("/");
  49  |   await page.getByLabel("KEY(如 CHE)").fill(KEY);
  50  |   await page.getByLabel("名称").fill("E2E 冒烟项目");
  51  |   await page.getByRole("button", { name: "创建项目" }).click();
  52  |   await expect(page.getByText(KEY, { exact: true })).toBeVisible();
  53  |   await page.goto(`/p/${KEY}`);
  54  |   await expect(page.getByRole("heading", { name: "To Do(0)" })).toBeVisible();
  55  |   for (const col of ["In Progress(0)", "In Review(0)", "QA(0)", "Done(0)"]) {
  56  |     await expect(page.getByRole("heading", { name: col })).toBeVisible();
  57  |   }
  58  | });
  59  | 
  60  | test("建 item → 详情 → 评论 → 切状态 → 活动日志留痕", async ({ page }) => {
  61  |   await login(page);
  62  |   await page.goto(`/p/${KEY}`);
  63  |   await page.getByPlaceholder("新建 item").fill("E2E 冒烟 item");
  64  |   await page.getByRole("button", { name: "新建 ITEM" }).click();
  65  |   await page.getByText("E2E 冒烟 item").first().waitFor();
  66  |   await page.getByText("E2E 冒烟 item").first().click();
  67  | 
  68  |   await expect(page.getByRole("heading", { name: "谁来做" })).toBeVisible();
  69  | 
  70  |   // 评论
  71  |   await page.getByPlaceholder("写评论…").fill("E2E 冒烟评论");
  72  |   await page.getByRole("button", { name: "发送" }).click();
  73  |   await expect(page.getByText("E2E 冒烟评论")).toBeVisible();
  74  | 
  75  |   // 指派给我(V4-S3:离开起点须有主)
  76  |   await page.getByRole("button", { name: "指派给我" }).click();
  77  |   await expect(page.getByText("👤 me").first()).toBeVisible();
  78  | 
  79  |   // 切状态到 QA(MUI Select 键盘流:当前 To Do,ArrowDown×3 到 QA)
  80  |   await page.getByRole("combobox", { name: "item 状态" }).click();
  81  |   const listbox = page.getByRole("listbox");
  82  |   await expect(listbox).toBeVisible();
  83  |   await listbox.getByRole("option", { name: "QA" }).click();
  84  |   await expect(listbox).toBeHidden();
  85  |   await expect(page.getByRole("combobox", { name: "item 状态" })).toHaveText("QA");
  86  | 
  87  |   // 活动日志:created + 状态变更留痕
  88  |   await expect(page.getByText("活动日志")).toBeVisible();
  89  |   await expect(page.getByText(/状态变更: To Do → QA/)).toBeVisible();
  90  | });
  91  | 
  92  | test("日期与超期标识——详情设置日期,看板红标,甘特可见", async ({ page }) => {
  93  |   await login(page);
  94  |   await page.goto(`/p/${KEY}`);
  95  |   await page.getByPlaceholder("新建 item").fill("E2E 超期 item");
  96  |   await page.getByRole("button", { name: "新建 ITEM" }).click();
  97  |   await page.getByText("E2E 超期 item").first().waitFor();
  98  |   await page.getByText("E2E 超期 item").first().click();
  99  | 
  100 |   // 详情页设置过去日期
  101 |   await page.getByLabel("开始日期").fill("2020-01-01");
  102 |   await page.getByLabel("截止日期").fill("2020-01-15");
  103 |   await page.getByRole("button", { name: "保存" }).click();
  104 |   await expect(page.getByLabel("开始日期")).toHaveValue("2020-01-01");
  105 | 
  106 |   // 看板红标
  107 |   await page.goto(`/p/${KEY}`);
  108 |   await expect(page.getByText("⚠ 超期 2020-01-15")).toBeVisible();
  109 | 
  110 |   // 甘特:行可见 + 未排期组存在
  111 |   await page.goto(`/p/${KEY}/gantt`);
  112 |   await expect(page.getByText("E2E 超期 item")).toBeVisible();
  113 |   await expect(page.getByText(/未排期\(/)).toBeVisible();
  114 | });
  115 | 
  116 | test("看板反映新状态;通知页可达", async ({ page }) => {
  117 |   await login(page);
  118 |   await page.goto(`/p/${KEY}`);
  119 |   const item = page.getByText("E2E 冒烟 item").first();
  120 |   await item.waitFor();
  121 |   await item.click();
  122 |   await expect(page.getByRole("combobox", { name: "item 状态" })).toHaveText("QA");
  123 | 
  124 |   await page.goto("/notifications");
  125 |   await expect(page.getByRole("heading", { name: "通知" })).toBeVisible();
  126 |   await expect(page.getByRole("button", { name: "全部已读" })).toBeVisible();
  127 | });
  128 | 
  129 | test("收藏——卡片☆切换与导航下拉直达", async ({ page }) => {
  130 |   await login(page);
  131 |   // 清理历史收藏(收藏随 DB 持久,跨运行会累积同名条目)
  132 |   await page.evaluate(async () => {
  133 |     const token = localStorage.getItem("yz-token");
  134 |     const favs = await fetch("/api/favorites", { headers: { Authorization: `Bearer ${token}` } }).then((r) => r.json());
  135 |     for (const f of favs) {
  136 |       await fetch(`/api/projects/${f.key}/favorite`, { method: "DELETE", headers: { Authorization: `Bearer ${token}` } });
  137 |     }
  138 |   });
```