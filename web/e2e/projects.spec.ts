// 项目列表/表单/收藏
import { expect } from "./support/fixtures";
import { test } from "./support/fixtures";

test("建项目表单——创建并打开看板(默认五列) @smoke", async ({ page, projectKey }) => {
  await page.goto("/");
  await page.getByLabel("KEY(如 CHE)").fill(`${projectKey}P`);
  await page.getByLabel("名称").fill("E2E UI 建的项目");
  await page.getByRole("button", { name: "创建项目" }).click();
  await expect(page.getByText(`${projectKey}P`, { exact: true })).toBeVisible();
  await page.goto(`/p/${projectKey}P`);
  await expect(page.getByRole("heading", { name: "To Do(0)" })).toBeVisible();
  for (const col of ["In Progress(0)", "In Review(0)", "QA(0)", "Done(0)"]) {
    await expect(page.getByRole("heading", { name: col })).toBeVisible();
  }
});

test("V11-S3 项目列表搜索/排序/显示已归档", async ({ page, projectKey }) => {
  await page.goto("/");
  await page.getByText(projectKey, { exact: true }).waitFor({ timeout: 15000 });
  // 搜索命中自己的项目
  await page.getByPlaceholder("搜索项目…").fill(`E2E ${projectKey}`);
  await expect(page.getByText(`E2E ${projectKey}`).first()).toBeVisible();
  await page.getByPlaceholder("搜索项目…").fill("");
  // 排序切换
  await page.getByRole("combobox").click();
  await page.getByRole("option", { name: "按名称" }).click();
  await expect(page.getByText(`E2E ${projectKey}`).first()).toBeVisible();
  // 显示已归档开关(无已归档项目,列表不消失)
  await page.getByText("显示已归档").click();
  await expect(page.getByRole("checkbox").first()).toBeChecked();
  await expect(page.getByText(`E2E ${projectKey}`).first()).toBeVisible();
});

test("收藏——卡片☆切换与导航下拉直达", async ({ page, projectKey }) => {
  await page.goto("/");
  const card = page.locator(".MuiCard-root", { hasText: projectKey }).first();
  await card.getByLabel("favorite").click();
  await expect(card.getByText("★")).toBeVisible();
  // 导航 ⭐ 下拉列出本项目
  await page.getByRole("button", { name: "⭐ 收藏" }).click();
  await expect(page.getByRole("menu").getByRole("link", { name: `E2E ${projectKey}` })).toBeVisible();
  // 收起(下拉内移除)→ 菜单里消失
  await page.getByRole("menu").getByRole("button", { name: "移除" }).first().click();
  await expect(page.getByRole("menu").getByRole("link", { name: `E2E ${projectKey}` })).toHaveCount(0);
});
