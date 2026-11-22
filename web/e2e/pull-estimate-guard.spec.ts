// YPJ-216(spec 0164,2026-11-24 SRS 修订):拉入守卫三途径弹窗行为回归钉板——
// 必预估:①backlog 行拉入弹补录 ②看板对话框完成→补录框(预填) ③组页同②;
// 非必预估:直通不弹框。此前多轮交互返工全靠手工验收,此 spec 把主路径交给自动化。
import { expect, test } from "./support/fixtures";
import { apiCall, apiCreateItem, apiCreateSprint } from "./support/helpers";

test.describe("estimateRequired=true——三途径缺预估弹补录", () => {
  test("途径② backlog 行拉入:缺预估弹补录,提交自动拉入", async ({
    page,
    projectKey,
  }) => {
    await apiCall("PATCH", `/api/projects/${projectKey}/settings`, {
      estimateRequired: true,
    });
    await apiCreateItem(projectKey, "EG 补录甲");
    await apiCreateSprint(projectKey, "EG Sprint 甲");
    await page.goto(`/p/${projectKey}/backlog`);
    await page.getByRole("button", { name: "拉入看板" }).first().click();
    const dlg = page.getByRole("dialog", { name: "补录预估" });
    await expect(dlg).toBeVisible();
    // 预填项目缺省(未设 → 内置中档 5day);不输入直接确定也可用
    await expect(dlg.getByLabel("预估", { exact: true })).toHaveValue("5");
    await expect(dlg.getByLabel("单位", { exact: true })).toHaveValue("day");
    await dlg.getByLabel("预估", { exact: true }).fill("2");
    await dlg.getByRole("button", { name: "确定并拉入" }).click();
    await expect(dlg).toBeHidden();
    await expect(page.getByText("EG 补录甲")).toHaveCount(0); // 离开 backlog
  });

  test("途径③ 看板拉入:勾选完成弹补录(预填),提交入板", async ({
    page,
    projectKey,
  }) => {
    await apiCall("PATCH", `/api/projects/${projectKey}/settings`, {
      estimateRequired: true,
    });
    await apiCreateItem(projectKey, "EG 补录乙");
    const sprintId = await apiCreateSprint(projectKey, "EG Sprint 乙");
    await apiCall(
      "POST",
      `/api/projects/${projectKey}/groups/${sprintId}/actions/start`,
      {},
    );
    await page.goto(`/p/${projectKey}/board`);
    await page.getByRole("button", { name: "拉入看板" }).click();
    const pullDlg = page.getByRole("dialog", { name: "从 Backlog 拉入看板" });
    await pullDlg
      .locator("li", { hasText: "EG 补录乙" })
      .getByRole("checkbox")
      .click();
    await pullDlg.getByRole("button", { name: "完成", exact: true }).click();
    // 完成后勾选票缺预估 → 弹补录框;未设项目缺省 → 预填内置中档 5
    const fillDlg = page.getByRole("dialog", { name: "补录预估" });
    await expect(fillDlg).toBeVisible();
    await expect(fillDlg.getByLabel("预估", { exact: true })).toHaveValue("5");
    await fillDlg.getByRole("button", { name: "提交", exact: true }).click();
    await expect(page.getByRole("dialog")).toHaveCount(0);
    await page.goto(`/p/${projectKey}/board`);
    await expect(page.getByText("EG 补录乙")).toBeVisible();
  });

  test("途径④ 组页拉入:勾选完成弹补录,提交入组", async ({
    page,
    projectKey,
  }) => {
    await apiCall("PATCH", `/api/projects/${projectKey}/settings`, {
      estimateRequired: true,
    });
    await apiCreateItem(projectKey, "EG 补录丙");
    const sprintId = await apiCreateSprint(projectKey, "EG Sprint 丙");
    await page.goto(`/p/${projectKey}/sprint/${sprintId}`);
    await page.getByRole("button", { name: "拉入 item" }).click();
    const pullDlg = page.getByRole("dialog", { name: "拉入 item" });
    await pullDlg
      .locator("li", { hasText: "EG 补录丙" })
      .getByRole("checkbox")
      .check();
    await pullDlg.getByRole("button", { name: "完成", exact: true }).click();
    const fillDlg = page.getByRole("dialog", { name: "补录预估" });
    await expect(fillDlg).toBeVisible();
    await fillDlg.getByRole("button", { name: "提交", exact: true }).click();
    await expect(page.getByRole("dialog")).toHaveCount(0);
    await expect(page.getByText("EG 补录丙")).toBeVisible();
  });
});

test.describe("estimateRequired=false——拉入直通不弹补录", () => {
  test("backlog 行拉入:不弹框直接入板", async ({ page, projectKey }) => {
    await apiCreateItem(projectKey, "EG 直通甲");
    await apiCreateSprint(projectKey, "EG Sprint 丁");
    await page.goto(`/p/${projectKey}/backlog`);
    await page.getByRole("button", { name: "拉入看板" }).first().click();
    await expect(page.getByText("EG 直通甲")).toHaveCount(0);
  });

  test("看板对话框:完成不弹补录,直接入板", async ({ page, projectKey }) => {
    await apiCreateItem(projectKey, "EG 直通乙");
    const sprintId = await apiCreateSprint(projectKey, "EG Sprint 戊");
    await apiCall(
      "POST",
      `/api/projects/${projectKey}/groups/${sprintId}/actions/start`,
      {},
    );
    await page.goto(`/p/${projectKey}/board`);
    await page.getByRole("button", { name: "拉入看板" }).click();
    const pullDlg = page.getByRole("dialog", { name: "从 Backlog 拉入看板" });
    await pullDlg
      .locator("li", { hasText: "EG 直通乙" })
      .getByRole("checkbox")
      .click();
    await pullDlg.getByRole("button", { name: "完成", exact: true }).click();
    await expect(page.getByRole("dialog", { name: "补录预估" })).toHaveCount(0);
    await expect(page.getByRole("dialog")).toHaveCount(0);
    await page.goto(`/p/${projectKey}/board`);
    await expect(page.getByText("EG 直通乙")).toBeVisible();
  });
});
