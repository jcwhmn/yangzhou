import { expect, test } from "@playwright/test";

/**
 * 核心链路(serial):登录 → 建项目 → 建 item → 评论 → 切状态 → 活动日志 → 看板 → 通知页。
 * 断言走用户可见文案(中文)。数据隔离:随机项目 key,不清库。
 * 前置:本机 Postgres(共享 compose)+ 后端 `gradle :api:bootRun`(8080);Next dev 由 webServer 拉起(已跑则复用)。
 */

const KEY = `E2E${Date.now() % 100000}`;

test.describe.configure({ mode: "serial" });

test.beforeAll(async ({ request }) => {
  // 后端在场检查:不起就给出明确指引
  let ok = false;
  try {
    const res = await request.post("http://localhost:8080/api/auth/login", {
      data: { username: "me", password: "secret" },
    });
    ok = res.ok();
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
  await expect(page).toHaveURL("/");
}

test("登录进首页", async ({ page }) => {
  await login(page);
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
