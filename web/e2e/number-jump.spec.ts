// item 编号直达:顶栏跳转框 + 详情页编号 URL(YPJ-63,spec 0048)
import { expect } from "./support/fixtures";
import { test } from "./support/fixtures";
import { apiCreateItem } from "./support/helpers";

// 新建项目首个 item 编号恒为 {KEY}-1(原子取号),确定性

test("顶栏编号跳转框——回车直达详情,地址栏保持编号", async ({ page, projectKey }) => {
  await apiCreateItem(projectKey, "E2E 直达 item");
  await page.goto("/");
  const box = page.getByPlaceholder("编号跳转,如 YPJ-59");
  await box.fill(`${projectKey}-1`);
  await box.press("Enter");
  await expect(page.getByText(`${projectKey}-1`)).toBeVisible();
  await expect(page.getByLabel("标题", { exact: true })).toHaveValue("E2E 直达 item");
  expect(page.url()).toContain(`/p/${projectKey}/i/${projectKey}-1`);
  // 刷新不坏
  await page.reload();
  await expect(page.getByText(`${projectKey}-1`)).toBeVisible();
});

test("编号 URL 直达——直接访问 /p/{key}/i/{NUMBER}", async ({ page, projectKey }) => {
  await apiCreateItem(projectKey, "E2E 编号 URL item");
  await page.goto(`/p/${projectKey}/i/${projectKey}-1`);
  await expect(page.getByText(`${projectKey}-1`)).toBeVisible();
  await expect(page.getByLabel("标题", { exact: true })).toHaveValue("E2E 编号 URL item");
});

test("负例——非法格式就地提示;未知编号未找到", async ({ page, projectKey }) => {
  await page.goto("/");
  const box = page.getByPlaceholder("编号跳转,如 YPJ-59");
  await box.fill("abc");
  await box.press("Enter");
  await expect(page.getByText("格式:项目KEY-编号")).toBeVisible();
  await page.goto(`/p/${projectKey}/i/${projectKey}-999`);
  await expect(page.getByText("未找到该编号的 item")).toBeVisible();
});
