// Sprint 全链路(状态单向按钮/拉入对话框/回链)+ Backlog 派生桶
import { expect } from "./support/fixtures";
import { test } from "./support/fixtures";
import { apiCreateItem, selectOption } from "./support/helpers";

test("V12-S3 Sprint 全链路——建/指派/开始/完成/backlog/历史 @smoke", async ({ page, projectKey }) => {
  test.slow();
  await apiCreateItem(projectKey, "E2E 冒烟 item");
  await page.goto(`/p/${projectKey}`);
  // 侧边栏新建 Sprint
  await page.getByRole("button", { name: "新建 Sprint" }).click();
  await page.getByLabel("名称").fill("Sprint 42");
  await page.getByRole("button", { name: "创建", exact: true }).click();
  await expect(page.getByRole("link", { name: /Sprint 42/ })).toBeVisible();

  // 详情指派(item 详情常驻区 Sprint 下拉)
  await page.getByText("E2E 冒烟 item").first().click();
  await selectOption(page, "Sprint", "Sprint 42");
  await expect(page.getByRole("combobox", { name: "Sprint" })).toHaveText("Sprint 42");

  // sprint 看板:冒烟 item 在列
  await page.getByRole("link", { name: /Sprint 42/ }).first().click();
  await expect(page.getByText("E2E 冒烟 item")).toBeVisible();

  // backlog 不含已指派 item
  await page.goto(`/p/${projectKey}/backlog`);
  await expect(page.getByText("E2E 冒烟 item")).toHaveCount(0);

  // 详情清除 → 回 backlog
  await page.goto(`/p/${projectKey}`);
  await page.getByText("E2E 冒烟 item").first().click();
  await selectOption(page, "Sprint", "无", "");
  await expect(page.getByRole("combobox", { name: "Sprint" })).toHaveText("");
  await page.goto(`/p/${projectKey}/backlog`);
  await expect(page.getByText("E2E 冒烟 item").first()).toBeVisible();

  // 再指派 → 状态单向按钮:开始 → 完成(确认框明示未完成 item 回 Backlog)
  await page.goto(`/p/${projectKey}`);
  await page.getByText("E2E 冒烟 item").first().click();
  const reassign = page.waitForResponse((r) => r.url().includes("/api/items/") && r.request().method() === "PUT");
  await selectOption(page, "Sprint", "Sprint 42");
  await reassign; // 等 PUT 落库再导航,否则指派可能被打断丢失
  await page.getByRole("link", { name: /Sprint 42/ }).first().click();
  await expect(page.getByRole("combobox", { name: "sprint 状态" })).toHaveCount(0); // V13-S4:下拉已移除
  await page.getByRole("button", { name: "▶ 开始 Sprint" }).click();
  await expect(page.getByRole("button", { name: "✓ 完成 Sprint" })).toBeVisible();
  await page.getByRole("button", { name: "✓ 完成 Sprint" }).click();
  await expect(page.getByText(/回到 Backlog/)).toBeVisible();
  await page.getByRole("button", { name: "确认完成" }).click();
  await expect(page.getByText(/此 Sprint 已完成/)).toBeVisible();
  await expect(page.getByText("E2E 冒烟 item")).toBeVisible();

  // 详情只显示当前 sprint(裁决 2):已完成 sprint 不回填下拉也不在选项中
  await page.goto(`/p/${projectKey}`);
  await page.getByText("E2E 冒烟 item").first().click();
  await expect(page.getByRole("combobox", { name: "Sprint" })).not.toHaveText("Sprint 42");
});

test("V13-S3 拉入对话框——多选勾入/移出 + 详情回链", async ({ page, projectKey }) => {
  await apiCreateItem(projectKey, "E2E 冒烟 item");
  await page.goto(`/p/${projectKey}`);
  await page.getByRole("button", { name: "新建 Sprint" }).click();
  await page.getByLabel("名称").fill("Sprint 43");
  await page.getByRole("button", { name: "创建", exact: true }).click();
  await expect(page.getByRole("link", { name: /Sprint 43/ })).toBeVisible();
  await page.getByRole("link", { name: /Sprint 43/ }).first().click();
  await expect(page.getByText("此 Sprint 还没有 item")).toBeVisible();

  // 拉入:对话框多选勾入(候选取自 Backlog)
  await page.getByRole("button", { name: "拉入 item" }).click();
  await page.getByRole("dialog").locator("li", { hasText: "E2E 冒烟 item" }).getByRole("checkbox").check();
  await page.getByRole("button", { name: "完成", exact: true }).click();
  await expect(page.getByRole("dialog")).toBeHidden();
  await expect(page.getByText("E2E 冒烟 item")).toBeVisible();

  // 详情回链:点 sprint 名跳回 sprint 页
  await page.getByText("E2E 冒烟 item").first().click();
  await page.getByRole("link", { name: "Sprint 43 ↗" }).click();
  await expect(page).toHaveURL(/\/sprint\/[0-9a-f-]{36}/);

  // 移出:同一对话框去勾
  await page.getByRole("button", { name: "拉入 item" }).click();
  await page.getByRole("dialog").locator("li", { hasText: "E2E 冒烟 item" }).getByRole("checkbox").uncheck();
  await page.getByRole("button", { name: "完成", exact: true }).click();
  await expect(page.getByRole("dialog")).toBeHidden();
  await expect(page.getByText("此 Sprint 还没有 item")).toBeVisible();
});

test("V13-S4 状态单向按钮——开始时多进行中提示 + 完成", async ({ page, projectKey }) => {
  await page.goto(`/p/${projectKey}`);
  // 建 A、B 两个 sprint(均 planned)
  await page.getByRole("button", { name: "新建 Sprint" }).click();
  await page.getByLabel("名称").fill("Sprint A");
  await page.getByRole("button", { name: "创建", exact: true }).click();
  await expect(page.getByRole("link", { name: /Sprint A/ })).toBeVisible();
  await page.getByRole("button", { name: "新建 Sprint" }).click();
  await page.getByLabel("名称").fill("Sprint B");
  await page.getByRole("button", { name: "创建", exact: true }).click();
  await expect(page.getByRole("link", { name: /Sprint B/ })).toBeVisible();
  // 开始 A(无其它进行中,直接生效)
  await page.getByRole("link", { name: /Sprint A/ }).first().click();
  await page.getByRole("button", { name: "▶ 开始 Sprint" }).click();
  await expect(page.getByRole("button", { name: "✓ 完成 Sprint" })).toBeVisible();
  // 开始 B:已有进行中 A → 确认框提示并存
  await page.getByRole("link", { name: /Sprint B/ }).first().click();
  await page.getByRole("button", { name: "▶ 开始 Sprint" }).click();
  await expect(page.getByText(/仍要开始本 Sprint/)).toBeVisible();
  await page.getByRole("button", { name: "仍要开始" }).click();
  await expect(page.getByRole("button", { name: "✓ 完成 Sprint" })).toBeVisible();
  // 完成 B:确认框明示未完成 item 回 Backlog
  await page.getByRole("button", { name: "✓ 完成 Sprint" }).click();
  await expect(page.getByText(/回到 Backlog/)).toBeVisible();
  await page.getByRole("button", { name: "确认完成" }).click();
  await expect(page.getByText(/此 Sprint 已完成/)).toBeVisible();
});

test("V12 未知 Sprint id 显示错误态并可返回上一页", async ({ page, projectKey }) => {
  await page.goto(`/p/${projectKey}`);
  await page.goto(`/p/${projectKey}/sprint/00000000-0000-0000-0000-000000000000`);
  await expect(page.getByText("Sprint 不存在或已被删除")).toBeVisible();
  await page.getByRole("button", { name: "← 返回上一页" }).click();
  await expect(page).toHaveURL(new RegExp(`/p/${projectKey}$`));
});
