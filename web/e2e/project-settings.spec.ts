// YPJ-151(spec 0102):项目设置——release/milestone 启停,侧栏段门控
import { expect, test } from "./support/fixtures";

test("项目设置——关 milestone 段消失,release 段不受影响;重开恢复", async ({
  page,
  projectKey,
}) => {
  await page.goto(`/p/${projectKey}`);
  // 默认开:两段都在
  await expect(page.getByText("Milestone", { exact: true })).toBeVisible();
  await expect(page.getByText("Release", { exact: true })).toBeVisible();

  // 关 milestone
  await page.getByRole("button", { name: "项目设置" }).click();
  const dialog = page.getByRole("dialog");
  await expect(dialog).toBeVisible();
  await dialog.getByRole("switch", { name: "Milestone 段" }).uncheck();
  await dialog.getByRole("button", { name: "保存" }).click();
  await expect(dialog).toBeHidden(); // MUI 淡出期吞点击,先等关

  // 侧栏:Milestone 段消失,Release 段仍在
  await expect(page.getByText("Milestone", { exact: true })).toHaveCount(0);
  await expect(page.getByText("Release", { exact: true })).toBeVisible();

  // 刷新后仍关(持久化)
  await page.reload();
  await expect(page.getByText("Milestone", { exact: true })).toHaveCount(0);

  // 重开恢复
  await page.getByRole("button", { name: "项目设置" }).click();
  const dialog2 = page.getByRole("dialog");
  await expect(dialog2).toBeVisible();
  await dialog2.getByRole("switch", { name: "Milestone 段" }).check();
  await dialog2.getByRole("button", { name: "保存" }).click();
  await expect(dialog2).toBeHidden();
  await expect(page.getByText("Milestone", { exact: true })).toBeVisible();
});
