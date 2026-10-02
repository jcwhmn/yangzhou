import { expect, test } from "@playwright/test";

/**
 * 核心链路(serial):登录 → 建项目 → 建 item → 评论 → 切状态 → 活动日志 → 看板 → 通知页。
 * 断言走用户可见文案(中文)。数据隔离:随机项目 key,不清库。
 * 前置:本机 Postgres(共享 compose)+ 后端 `gradle :api:bootRun`(8080);Next dev 由 webServer 拉起(已跑则复用)。
 */

const KEY = `E2E${Date.now() % 100000}`;

test.beforeAll(async ({ request }) => {
  // 后端在场检查 + 空库种子(CI 是全新库):bootstrap;已有用户则 409 → 登录确认
  let ok = false;
  try {
    const boot = await request.post("http://localhost:8080/api/auth/bootstrap", {
      data: { username: "me", password: "secret" },
    });
    ok = boot.ok() || boot.status() === 409;
    if (ok) {
      const login = await request.post("http://localhost:8080/api/auth/login", {
        data: { username: "me", password: "secret" },
      });
      ok = login.ok();
      if (ok) {
        // 成员池用例依赖工作区成员「小王」(CI 全新库也要有;已存在 409 忽略)
        const token = (await login.json()).token;
        await request.post("http://localhost:8080/api/members", {
          data: { displayName: "小王" },
          headers: { Authorization: `Bearer ${token}` },
        });
      }
    }
  } catch {
    ok = false;
  }
  if (!ok) {
    throw new Error("后端未就绪:请先起本机 Postgres 并运行 `gradle :api:bootRun`(8080),再跑 npm run e2e");
  }
});

async function login(page: import("@playwright/test").Page) {
  await page.goto("/login");
  await page.getByLabel("用户名").fill("me");
  await page.getByLabel("密码").fill("secret");
  await page.getByRole("button", { name: "登录" }).click();
  // V11-S1:登录后可能落在 / 或上次项目 /p/<key>,两者都算登录成功
  await expect(page).toHaveURL(/:\d+\/(p\/[^/]+)?$/);
}

test("登录进首页", async ({ page }) => {
  await login(page);
  // V11-S1 登录后可能落在上次项目,主动回首页验证项目列表可达
  await page.goto("/");
  await expect(page.getByRole("heading", { name: "项目", exact: true })).toBeVisible();
});

test("建项目并打开看板(默认五列)", async ({ page }) => {
  await login(page);
  await page.goto("/");
  await page.getByLabel("KEY(如 CHE)").fill(KEY);
  await page.getByLabel("名称").fill("E2E 冒烟项目");
  await page.getByRole("button", { name: "创建项目" }).click();
  await expect(page.getByText(KEY, { exact: true })).toBeVisible();
  await page.goto(`/p/${KEY}`);
  await expect(page.getByRole("heading", { name: "To Do(0)" })).toBeVisible();
  for (const col of ["In Progress(0)", "In Review(0)", "QA(0)", "Done(0)"]) {
    await expect(page.getByRole("heading", { name: col })).toBeVisible();
  }
});

test("V11-S1 登录后进入上次项目", async ({ page }) => {
  await login(page);
  // 看板 mount 写 last_project_key(V26)
  await page.goto(`/p/${KEY}`);
  await expect(page.getByRole("heading", { name: "To Do", exact: false })).toBeVisible();
  // 清会话重新登录 → 落回上次项目
  await page.evaluate(() => localStorage.clear());
  await page.goto("/login");
  await page.getByLabel("用户名").fill("me");
  await page.getByLabel("密码").fill("secret");
  await page.getByRole("button", { name: "登录" }).click();
  await expect(page).toHaveURL(new RegExp(`/p/${KEY}$`));
});

test("V11-S3 项目列表搜索/排序/显示已归档", async ({ page }) => {
  await login(page);
  await page.goto("/");
  await page.getByText(KEY, { exact: true }).waitFor({ timeout: 15000 });
  // 搜索过滤
  await page.getByPlaceholder("搜索项目…").fill("冒烟");
  await expect(page.getByText("E2E 冒烟项目").first()).toBeVisible();
  await page.getByPlaceholder("搜索项目…").fill("");
  // 排序切换(首页唯一 combobox)
  await page.getByRole("combobox").click();
  await page.getByRole("option", { name: "按名称" }).click();
  await expect(page.getByText("E2E 冒烟项目").first()).toBeVisible();
  // 显示已归档开关(无已归档项目,列表不消失)
  await page.getByText("显示已归档").click();
  await expect(page.getByRole("checkbox").first()).toBeChecked();
  await expect(page.getByText("E2E 冒烟项目").first()).toBeVisible();
});

test("建 item → 详情 → 评论 → 切状态 → 活动日志留痕", async ({ page }) => {
  await login(page);
  await page.goto(`/p/${KEY}`);
  await page.getByPlaceholder("新建 item").fill("E2E 冒烟 item");
  await page.getByRole("button", { name: "新建 ITEM" }).click();
  await page.getByText("E2E 冒烟 item").first().waitFor();
  await page.getByText("E2E 冒烟 item").first().click();

  await expect(page.getByRole("heading", { name: "谁来做" })).toBeVisible();

  // 评论
  await page.getByPlaceholder("写评论…").fill("E2E 冒烟评论");
  await page.getByRole("button", { name: "发送" }).click();
  await expect(page.getByText("E2E 冒烟评论")).toBeVisible();

  // 指派给我(V4-S3:离开起点须有主)
  await page.getByRole("button", { name: "指派给我" }).click();
  await expect(page.getByText("👤 me").first()).toBeVisible();

  // 切状态到 QA(MUI Select 键盘流:当前 To Do,ArrowDown×3 到 QA)
  await page.getByRole("combobox", { name: "item 状态" }).click();
  const listbox = page.getByRole("listbox");
  await expect(listbox).toBeVisible();
  await listbox.getByRole("option", { name: "QA" }).click();
  await expect(listbox).toBeHidden();
  await expect(page.getByRole("combobox", { name: "item 状态" })).toHaveText("QA");

  // 活动日志:created + 状态变更留痕
  await expect(page.getByText("活动日志")).toBeVisible();
  await expect(page.getByText(/状态变更: To Do → QA/)).toBeVisible();
});

test("日期与超期标识——详情设置日期,看板红标,甘特可见", async ({ page }) => {
  await login(page);
  await page.goto(`/p/${KEY}`);
  await page.getByPlaceholder("新建 item").fill("E2E 超期 item");
  await page.getByRole("button", { name: "新建 ITEM" }).click();
  await page.getByText("E2E 超期 item").first().waitFor();
  await page.getByText("E2E 超期 item").first().click();

  // 详情页设置过去日期
  await page.getByLabel("开始日期").fill("2020-01-01");
  await page.getByLabel("截止日期").fill("2020-01-15");
  await page.getByRole("button", { name: "保存" }).click();
  await expect(page.getByLabel("开始日期")).toHaveValue("2020-01-01");

  // 看板红标
  await page.goto(`/p/${KEY}`);
  await expect(page.getByText("⚠ 超期 2020-01-15")).toBeVisible();

  // 甘特:行可见 + 未排期组存在
  await page.goto(`/p/${KEY}/gantt`);
  await expect(page.getByText("E2E 超期 item")).toBeVisible();
  await expect(page.getByText(/未排期\(/)).toBeVisible();
});

test("V11-S4 detail 优先级 Select 接通(P1 落库/清除)", async ({ page }) => {
  await login(page);
  await page.goto(`/p/${KEY}`);
  await page.getByText("E2E 冒烟 item").first().waitFor({ timeout: 15000 });
  await page.getByText("E2E 冒烟 item").first().click();
  await page.getByRole("combobox", { name: "优先级" }).click();
  const plist = page.getByRole("listbox");
  await plist.getByRole("option", { name: "P1" }).click();
  await expect(plist).toBeHidden();
  await page.getByRole("button", { name: "保存" }).click();
  await page.reload();
  await expect(page.getByRole("combobox", { name: "优先级" })).toHaveText("P1");
  // 空串=清除(V24 PATCH 语义)
  await page.getByRole("combobox", { name: "优先级" }).click();
  await plist.getByRole("option", { name: "无" }).click();
  await expect(plist).toBeHidden();
  await page.getByRole("button", { name: "保存" }).click();
  await expect(page.getByRole("combobox", { name: "优先级" })).not.toHaveText("P1");
});

test("看板反映新状态;通知页可达", async ({ page }) => {
  await login(page);
  await page.goto(`/p/${KEY}`);
  const item = page.getByText("E2E 冒烟 item").first();
  await item.waitFor();
  await item.click();
  await expect(page.getByRole("combobox", { name: "item 状态" })).toHaveText("QA");

  await page.goto("/notifications");
  await expect(page.getByRole("heading", { name: "通知" })).toBeVisible();
  await expect(page.getByRole("button", { name: "全部已读" })).toBeVisible();
});

test("收藏——卡片☆切换与导航下拉直达", async ({ page }) => {
  await login(page);
  await page.goto("/");
  await page.getByText(KEY, { exact: true }).waitFor({ timeout: 15000 });
  // 清理历史收藏(收藏随 DB 持久,跨运行会累积同名条目)
  await page.evaluate(async () => {
    const token = localStorage.getItem("yz-token");
    const favs = await fetch("/api/favorites", { headers: { Authorization: `Bearer ${token}` } }).then((r) => r.json());
    for (const f of favs) {
      await fetch(`/api/projects/${f.key}/favorite`, { method: "DELETE", headers: { Authorization: `Bearer ${token}` } });
    }
  });
  await page.goto("/");
  // KEY 项目卡上点 ☆ 收藏
  const card = page.locator(".MuiCard-root", { hasText: KEY }).first();
  await card.getByLabel("favorite").click();
  await expect(card.getByText("★")).toBeVisible();

  // 导航 ⭐ 下拉列出 KEY
  await page.getByRole("button", { name: "⭐ 收藏" }).click();
  await expect(page.getByRole("menu").getByRole("link", { name: "E2E 冒烟项目" })).toBeVisible();

  // 取消收藏(下拉内移除)→ 菜单里消失
  await page.getByRole("menu").getByRole("button", { name: "移除" }).first().click();
  await expect(page.getByRole("menu").getByRole("link", { name: "E2E 冒烟项目" })).toHaveCount(0);
});

test("V11 成员池色点——有非终态 item 🟢,无 ⚪", async ({ page }) => {
  await login(page);
  await page.goto(`/p/${KEY}`);
  await page.getByRole("button", { name: "成员池" }).click();
  // 池为空 → 加 me(冒烟 item 在 QA,非终态)与小王(无 item)
  await page.getByText("+ me", { exact: true }).click();
  await page.getByText("+ 小王", { exact: true }).click();
  await expect(page.getByLabel("进行中")).toHaveCount(1);
  await expect(page.getByLabel("空闲")).toHaveCount(1);
});

test("看板刷新按钮可用", async ({ page }) => {
  await login(page);
  await page.goto(`/p/${KEY}`);
  await page.getByRole("button", { name: "刷新" }).click();
  await expect(page.getByRole("heading", { name: "To Do", exact: false })).toBeVisible();
});

test("详情——编辑需求入口打开对话框", async ({ page }) => {
  await login(page);
  await page.goto(`/p/${KEY}`);
  await page.getByText("E2E 冒烟 item").first().click();
  await page.getByRole("button", { name: "编辑需求" }).click();
  await expect(page.getByRole("heading", { name: "需求" })).toBeVisible();
  await page.getByRole("button", { name: "关闭" }).click();
});

test("回收站——删除后可恢复", async ({ page }) => {
  await login(page);
  await page.goto(`/p/${KEY}`);
  await page.getByText("E2E 冒烟 item").first().waitFor({ timeout: 15000 });
  await page.goto(`/p/${KEY}`);
  await page.getByPlaceholder("新建 item").fill("E2E 回收站 item");
  await page.getByRole("button", { name: "新建 ITEM" }).click();
  await page.getByText("E2E 回收站 item").first().waitFor();
  await page.getByText("E2E 回收站 item").first().click();

  // 详情删除(原生 confirm 对话框,自动接受)
  page.once("dialog", (d) => d.accept());
  await page.getByRole("button", { name: "删除", exact: true }).click();
  await expect(page).toHaveURL(new RegExp(`/p/${KEY}$`));

  // 回收站列出 → 恢复 → 看板重现
  await page.goto("/recycle-bin");
  await page.getByText("E2E 回收站 item").waitFor({ timeout: 10000 });
  await page.getByRole("button", { name: "恢复" }).first().click();
  await page.goto(`/p/${KEY}`);
  await page.getByText("E2E 回收站 item").first().waitFor({ timeout: 10000 });
  await page.goto(`/p/${KEY}`);
  await expect(page.getByText("E2E 回收站 item").first()).toBeVisible();
});

test("V10 priority 设置与表格视图", async ({ page }) => {
  await login(page);
  await page.goto(`/p/${KEY}`);
  await page.getByText("E2E 冒烟 item").first().waitFor({ timeout: 15000 });
  // 表格视图页可达
  await page.goto(`/p/${KEY}/table`);
  await expect(page.getByText("E2E 冒烟 item").first()).toBeVisible();
});

test("V10 依赖——blocked 标识", async ({ page }) => {
  await login(page);
  await page.goto(`/p/${KEY}`);
  await page.getByText("E2E 冒烟 item").first().waitFor({ timeout: 15000 });
  // 建第二个 item,加依赖指向冒烟 item
  await page.getByPlaceholder("新建 item").fill("E2E 依赖 item");
  await page.getByRole("button", { name: "新建 ITEM" }).click();
  await page.getByText("E2E 依赖 item").first().waitFor();
  await page.getByText("E2E 依赖 item").first().click();
  // 依赖区块在「依赖+清单」tab(S4 detail 重构)
  await page.getByRole("tab", { name: "依赖+清单" }).click();
  await page.getByRole("button", { name: "加依赖" }).click();
  // 页面里有多个 select(MUI 隐藏原生 select)和多个「添加」按钮,用 aria-label 精确定位
  await page.getByRole("combobox", { name: "被依赖 item" }).selectOption({ index: 1 });
  await page.getByRole("button", { name: "确认添加依赖" }).click();
  await expect(page.getByText("阻塞中").first()).toBeVisible();
});

test("V12-S3 Sprint 全链路——新建/指派/看板/backlog/完成历史", async ({ page }) => {
  await login(page);
  await page.goto(`/p/${KEY}`);
  // 侧边栏新建 Sprint
  await page.getByRole("button", { name: "新建 Sprint" }).click();
  await page.getByLabel("名称").fill("Sprint 42");
  await page.getByRole("button", { name: "创建", exact: true }).click();
  await expect(page.getByRole("link", { name: /Sprint 42/ })).toBeVisible();

  // 详情指派(item 详情常驻区 Sprint 下拉)
  await page.getByText("E2E 冒烟 item").first().waitFor({ timeout: 15000 });
  await page.getByText("E2E 冒烟 item").first().click();
  await page.getByRole("combobox", { name: "Sprint" }).click();
  await page.getByRole("option", { name: "Sprint 42" }).click();
  await expect(page.getByRole("combobox", { name: "Sprint" })).toHaveText("Sprint 42");

  // sprint 看板:冒烟 item 在列
  await page.getByRole("link", { name: /Sprint 42/ }).first().click();
  await expect(page.getByText("E2E 冒烟 item")).toBeVisible();

  // backlog 不含已指派 item
  await page.goto(`/p/${KEY}/backlog`);
  await expect(page.getByText("E2E 冒烟 item")).toHaveCount(0);

  // 详情清除 → 回 backlog
  await page.goto(`/p/${KEY}`);
  await page.getByText("E2E 冒烟 item").first().click();
  await page.getByRole("combobox", { name: "Sprint" }).click();
  await page.getByRole("option", { name: "无" }).click();
  await expect(page.getByRole("combobox", { name: "Sprint" })).toHaveText("");
  await page.goto(`/p/${KEY}/backlog`);
  await expect(page.getByText("E2E 冒烟 item").first()).toBeVisible();

  // 再指派 → 完成 sprint → 历史在 sprint 页回看;详情只显示当前 sprint(裁决 2):已完成 sprint 不回填下拉也不在选项中
  await page.goto(`/p/${KEY}`);
  await page.getByText("E2E 冒烟 item").first().click();
  await page.getByRole("combobox", { name: "Sprint" }).click();
  await page.getByRole("option", { name: "Sprint 42" }).click();
  await page.getByRole("link", { name: /Sprint 42/ }).first().click();
  await page.getByRole("combobox", { name: "sprint 状态" }).click();
  await page.getByRole("option", { name: "已完成" }).click();
  await expect(page.getByText(/此 Sprint 已完成/)).toBeVisible();
  await expect(page.getByText("E2E 冒烟 item")).toBeVisible();
  await page.goto(`/p/${KEY}`);
  await page.getByText("E2E 冒烟 item").first().click();
  await expect(page.getByRole("combobox", { name: "Sprint" })).not.toHaveText("Sprint 42");
  await page.getByRole("combobox", { name: "Sprint" }).click();
  await expect(page.getByRole("option", { name: "Sprint 42" })).toHaveCount(0);
});

test("V12 未知 Sprint id 显示错误态并可返回上一页", async ({ page }) => {
  await login(page);
  await page.goto(`/p/${KEY}`);
  await page.goto(`/p/${KEY}/sprint/00000000-0000-0000-0000-000000000000`);
  await expect(page.getByText("Sprint 不存在或已被删除")).toBeVisible();
  await page.getByRole("button", { name: "← 返回上一页" }).click();
  await expect(page).toHaveURL(new RegExp(`/p/${KEY}$`));
});
