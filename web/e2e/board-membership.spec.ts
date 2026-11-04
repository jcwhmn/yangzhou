// YPJ-144(spec 0099):看板拉入/退回——backlog 行拉入 / 看板对话框拉入 / 卡片退回 / 有 active sprint 同时挂
import { expect, test } from "./support/fixtures";
import { apiCall, apiCreateItem, apiCreateSprint } from "./support/helpers";

test("无组——backlog 行拉入入板_看板路由可见_卡片退回复原", async ({
  page,
  projectKey,
}) => {
  await apiCreateItem(projectKey, "BM 拉入 item");
  await page.goto(`/p/${projectKey}/backlog`);
  await page.getByRole("button", { name: "拉入看板" }).first().click();
  // 离开 backlog(工作集)
  await expect(page.getByText("BM 拉入 item")).toHaveCount(0);
  // 看板独立路由可见(方案A 下无组项目根页落 backlog,执行面在 /board)
  await page.goto(`/p/${projectKey}/board`);
  await expect(page.getByText("BM 拉入 item")).toBeVisible();
  // 卡片退回 → 回 backlog
  await page.getByRole("button", { name: "退回 Backlog" }).click();
  await page.goto(`/p/${projectKey}/backlog`);
  await expect(page.getByText("BM 拉入 item")).toBeVisible();
});

test("看板页拉入对话框——多选拉入", async ({ page, projectKey }) => {
  await apiCreateItem(projectKey, "BM 对话框 item");
  await page.goto(`/p/${projectKey}/board`);
  await page.getByRole("button", { name: "拉入看板" }).click();
  await page.getByRole("button", { name: /BM 对话框 item/ }).click();
  await page.getByRole("button", { name: "拉入", exact: true }).click();
  const dialog = page.getByRole("dialog");
  await expect(dialog).toBeHidden(); // MUI 淡出期吞点击,先等关
  await expect(page.getByText("BM 对话框 item")).toBeVisible();
});

test("有 active sprint——拉入同时挂 sprint_退回清成员", async ({
  page,
  projectKey,
}) => {
  const item = await apiCreateItem(projectKey, "BM 冲刺 item");
  const sprintId = await apiCreateSprint(projectKey, "BM Sprint");
  await apiCall(
    "POST",
    `/api/projects/${projectKey}/groups/${sprintId}/actions/start`,
    {},
  );
  await page.goto(`/p/${projectKey}/backlog`);
  await page.getByRole("button", { name: "拉入看板" }).first().click();
  // 拉入 = 同时挂最早 active sprint:sprint 页成员可见
  await page.goto(`/p/${projectKey}/sprint/${sprintId}`);
  await expect(page.getByText("BM 冲刺 item")).toBeVisible();
  // 看板退回 → 成员行清(重回 backlog)
  await page.goto(`/p/${projectKey}/board`);
  await page.getByRole("button", { name: "退回 Backlog" }).click();
  await page.goto(`/p/${projectKey}/sprint/${sprintId}`);
  await expect(page.getByText("BM 冲刺 item")).toHaveCount(0);
  await page.goto(`/p/${projectKey}/backlog`);
  await expect(page.getByText("BM 冲刺 item")).toBeVisible();
});
