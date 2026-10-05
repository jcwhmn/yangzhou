// V14 Milestone:侧边栏段 + 管理对话框(CRUD/单向状态/两段式删除)+ 0..1 in_progress 拒绝
import { expect, test } from "./support/fixtures";
import { apiCall } from "./support/helpers";

test("Milestone 管理对话框——建/推进/编辑/删除", async ({ page, projectKey }) => {
  // 预置:A 进行中,B 规划中(A 在前)
  const a = (await apiCall<{ milestoneId: string }>("POST", `/api/projects/${projectKey}/milestones`, {
    name: "MVP 里程碑",
    targetDate: "2026-12-01",
  })).milestoneId;
  const b = (await apiCall<{ milestoneId: string }>("POST", `/api/projects/${projectKey}/milestones`, {
    name: "Beta 里程碑",
  })).milestoneId;
  await apiCall("PATCH", `/api/projects/${projectKey}/milestones/${a}`, { status: "in_progress" });

  await page.goto(`/p/${projectKey}`);
  await expect(page.getByText("MVP 里程碑").first()).toBeVisible(); // 侧边栏段展示
  await expect(page.getByText(/进行中 · 2026-12-01/)).toBeVisible();

  // 管理对话框
  await page.getByRole("button", { name: "管理", exact: true }).click();
  const dialog = page.getByRole("dialog");
  await expect(dialog).toBeVisible();
  await expect(dialog.getByText("MVP 里程碑")).toBeVisible();
  await expect(dialog.getByText("Beta 里程碑")).toBeVisible();

  // UI 新建(带日期)
  await dialog.getByLabel("名称").fill("GA 里程碑");
  await dialog.getByLabel("目标日期").fill("2027-06-01");
  await dialog.getByRole("button", { name: "新建", exact: true }).click();
  await expect(dialog.getByText("GA 里程碑")).toBeVisible();

  // 单向推进:B planned → 开始(与 A 并存 → 后端 409 错误展示)。行定位用 testid 锚:
  // 行文本以状态图标开头,锚定 hasText 只能匹配到不含按钮的内层 Box(2026-10-06 排查)
  const betaRow = dialog.getByTestId("milestone-row").filter({ hasText: "Beta 里程碑" });
  await betaRow.getByRole("button", { name: /开始/ }).click();
  await expect(dialog.getByText(/已有进行中的 milestone/)).toBeVisible();

  // 完成 A → 409 解除,B 可开始
  const mvpRow = dialog.getByTestId("milestone-row").filter({ hasText: "MVP 里程碑" });
  await mvpRow.getByRole("button", { name: /完成/ }).click();
  await expect(mvpRow.getByRole("button", { name: /开始|完成|取消/ })).toHaveCount(0); // 终态无状态按钮
  await betaRow.getByRole("button", { name: /开始/ }).click();
  await expect(dialog.getByText(/已有进行中的 milestone/)).toHaveCount(0);
  await expect(dialog.getByText("▶").first()).toBeVisible();

  // 编辑:回填改名
  const gaRow = dialog.getByTestId("milestone-row").filter({ hasText: "GA 里程碑" });
  await gaRow.getByRole("button", { name: "编辑" }).click();
  await expect(dialog.getByLabel("名称")).toHaveValue("GA 里程碑");
  await dialog.getByLabel("名称").fill("GA 里程碑 改");
  await dialog.getByRole("button", { name: "保存" }).click();
  await expect(dialog.getByText("GA 里程碑 改")).toBeVisible();

  // 两段式删除(改名后重新定位,行文本已变)
  const del = dialog.getByTestId("milestone-row").filter({ hasText: "GA 里程碑 改" }).getByRole("button", { name: "删除" });
  await del.click();
  await del.click();
  await expect(dialog.getByText("GA 里程碑 改")).toHaveCount(0);

  // 关闭对话框(MUI 淡出期会吞下层点击,先等隐藏)
  await dialog.getByRole("button", { name: "关闭" }).click();
  await expect(dialog).toBeHidden();
  await expect(page.getByText("GA 里程碑 改")).toHaveCount(0);
});

test("Milestone 空态与侧边栏只读展示", async ({ page, projectKey }) => {
  await page.goto(`/p/${projectKey}`);
  await expect(page.getByText("暂无 Milestone")).toBeVisible();
  await page.getByRole("button", { name: "管理", exact: true }).click();
  const dialog = page.getByRole("dialog");
  await expect(dialog.getByText("暂无 Milestone")).toBeVisible();
  await dialog.getByRole("button", { name: "关闭" }).click();
  await expect(dialog).toBeHidden();
});
