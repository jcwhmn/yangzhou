// YPJ-4 Release(spec 0012):侧边栏段 + 管理对话框(建/补建/发布)+ 详情下拉拉入 + 非空删除 409
import { expect, test } from "./support/fixtures";
import { apiCall, apiCreateItem, selectOption } from "./support/helpers";

test("Release 全链路——段显示/对话框建与补建/发布/拉入/非空删除 409", async ({ page, projectKey }) => {
  // 预置:planned R2.0 + 补建 released R1.9(§8.2)
  await apiCall("POST", `/api/projects/${projectKey}/releases`, { name: "R2.0", targetDate: "2026-12-01" });
  await apiCall("POST", `/api/projects/${projectKey}/releases`, { name: "R1.9", status: "released", releasedDate: "2026-09-15" });

  await page.goto(`/p/${projectKey}`);
  // 侧边栏段:planned 在前,released 在后
  await expect(page.getByText("R2.0 (0)")).toBeVisible();
  await expect(page.getByText("R1.9 (0)")).toBeVisible();

  // 管理对话框:新建 R3.0
  await page.getByRole("button", { name: "管理 Release" }).click();
  const dialog = page.getByRole("dialog");
  await expect(dialog).toBeVisible();
  await dialog.getByLabel("名称").fill("R3.0");
  await dialog.getByLabel("目标发布日").fill("2027-03-01");
  await dialog.getByRole("button", { name: "新建", exact: true }).click();
  const r3row = dialog.getByTestId("release-row").filter({ hasText: "R3.0" });
  await expect(r3row).toBeVisible();

  // 补建入口存在(勾选后出发布日)
  await dialog.getByLabel("补建为已发布").check();
  await expect(dialog.getByLabel("发布日", { exact: true })).toBeVisible();
  await dialog.getByLabel("补建为已发布").uncheck();

  // 发布 R2.0:行内确认(默认今天)
  const r2row = dialog.getByTestId("release-row").filter({ hasText: "R2.0" });
  await r2row.getByRole("button", { name: /发布/ }).click();
  await r2row.getByRole("button", { name: "确认发布" }).click();
  await expect(r2row.getByText("已发布")).toBeVisible();
  // released 只读:无编辑/发布按钮
  await expect(r2row.getByRole("button", { name: /发布|编辑/ })).toHaveCount(0);

  // 关闭(淡出期吞下层点击,先等隐藏)
  await dialog.getByRole("button", { name: "关闭" }).click();
  await expect(dialog).toBeHidden();

  // 详情页下拉拉入 R3.0
  const itemId = await apiCreateItem(projectKey, "交付特性 A");
  await page.goto(`/p/${projectKey}/i/${itemId}`);
  await selectOption(page, "Release", "R3.0");

  // 侧边栏计数 (1);对话框非空删除 409;移出后删 204
  await page.goto(`/p/${projectKey}`);
  await expect(page.getByText("R3.0 (1)")).toBeVisible();
  await page.getByRole("button", { name: "管理 Release" }).click();
  await expect(page.getByRole("dialog")).toBeVisible();
  const r3 = page.getByRole("dialog").getByTestId("release-row").filter({ hasText: "R3.0" });
  await r3.getByRole("button", { name: "删除" }).click();
  await r3.getByRole("button", { name: "删除" }).click();
  await expect(page.getByRole("dialog").getByText(/release 非空/)).toBeVisible();

  await page.goto(`/p/${projectKey}/i/${itemId}`);
  await selectOption(page, "Release", "无", "");
  await page.goto(`/p/${projectKey}`);
  await page.getByRole("button", { name: "管理 Release" }).click();
  await expect(page.getByRole("dialog")).toBeVisible();
  const r3again = page.getByRole("dialog").getByTestId("release-row").filter({ hasText: "R3.0" });
  await r3again.getByRole("button", { name: "删除" }).click();
  await r3again.getByRole("button", { name: "删除" }).click();
  await expect(r3again).toHaveCount(0);
  await page.getByRole("dialog").getByRole("button", { name: "关闭" }).click();
  await expect(page.getByRole("dialog")).toBeHidden();
});
