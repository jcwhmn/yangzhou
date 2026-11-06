// 0106:组完成时未完成 item 处置——Done 留组;非终态按 0105 决策树进目标组,可覆写回 Backlog
// 先完成组(组先行),处置弹窗列出非终态成员;取消处置=全部落 Backlog 兜底
import { expect, test } from "./support/fixtures";
import {
  apiAssignMe,
  apiCall,
  apiCreateItem,
  apiCreateSprint,
} from "./support/helpers";

/** 建组并置定状态 */
async function group(
  key: string,
  name: string,
  status: string,
): Promise<string> {
  const gid = await apiCreateSprint(key, name);
  if (status === "active" || status === "completed") {
    await apiCall(
      "POST",
      `/api/projects/${key}/groups/${gid}/actions/start`,
      {},
    );
    if (status === "completed") {
      await apiCall(
        "POST",
        `/api/projects/${key}/groups/${gid}/actions/complete`,
        {},
      );
    }
  }
  return gid;
}

/** 建 item 并挂组 */
async function member(key: string, title: string, gid: string): Promise<void> {
  await apiCall("POST", `/api/projects/${key}/items`, { title, sprintId: gid });
}

/** 完成组并回到组详情页(处置弹窗所在) */
async function completeAndOpen(
  page: import("@playwright/test").Page,
  key: string,
  gid: string,
) {
  // 处置弹窗只在 UI 完成流(doComplete)出现——API 完成不触发;
  // planned 态无「完成」出边按钮(0097 按钮=当前状态出边),先 API 激活
  await apiCall("POST", `/api/projects/${key}/groups/${gid}/actions/start`, {});
  await page.goto(`/p/${key}/sprint/${gid}`);
  await page.getByRole("button", { name: /完成 Sprint/ }).click();
  await page.getByRole("button", { name: "确认完成" }).click();
}

test("完成组→处置弹窗列出非终态成员(Done 不列),默认全转入单进行中目标组", async ({
  page,
  projectKey,
}) => {
  const doneGid = await group(projectKey, "冲刺A", "planned");
  await member(projectKey, "待办成员", doneGid);
  await member(projectKey, "进行中成员", doneGid);
  const todoId = await apiCall<{ itemId: string }>(
    "POST",
    `/api/projects/${projectKey}/items`,
    { title: "终态成员", sprintId: doneGid },
  ).then((r) => r.itemId);
  // 终态化:迁移到 FINAL 状态(取项目 statuses 的 FINAL 项)
  const proj = await apiCall<{
    statuses: { statusId: string; statusType: string }[];
  }>("GET", `/api/projects/${projectKey}`);
  const finalId = proj.statuses.find((s) => s.statusType === "FINAL")!.statusId;
  await apiAssignMe(todoId);
  await apiCall("PATCH", `/api/items/${todoId}`, { statusItemId: finalId });
  const targetGid = await group(projectKey, "接盘B", "active");

  await completeAndOpen(page, projectKey, doneGid);
  const dialog = page.getByRole("dialog");
  await expect(dialog).toBeVisible();
  // Done 不列;两个非终态列出行
  await expect(dialog.getByText("完成成员")).toHaveCount(0);
  await expect(dialog.getByText("待办成员")).toBeVisible();
  await expect(dialog.getByText("进行中成员")).toBeVisible();
  await expect(dialog.getByText(/转入进行中的「接盘B」/)).toBeVisible();

  // 等处置的组 items 刷新响应(处置是逐个异步 PUT,导航过早会中断)
  const refreshed = page.waitForResponse((r) =>
    r.url().includes(`/api/projects/${projectKey}/groups/${doneGid}/items`),
  );
  await dialog.getByRole("button", { name: "确认处置" }).click();
  await refreshed;
  await expect(dialog).toBeHidden();
  // 目标组看板可见转入的 item;Done 留在冲刺A 历史行
  await page.goto(`/p/${projectKey}`);
  await expect(page.getByText("待办成员")).toBeVisible();
  await expect(page.getByText("进行中成员")).toBeVisible();
  await page.goto(`/p/${projectKey}/sprint/${doneGid}`);
  await expect(page.getByText("终态成员")).toBeVisible();
});

test("处置覆写回 Backlog→该 item 入池,其余转入目标组", async ({
  page,
  projectKey,
}) => {
  const gid = await group(projectKey, "冲刺A2", "planned");
  await member(projectKey, "转入A2", gid);
  await member(projectKey, "回池A2", gid);
  await group(projectKey, "接盘B2", "active");

  await completeAndOpen(page, projectKey, gid);
  const dialog = page.getByRole("dialog");
  await expect(dialog).toBeVisible();
  // 0107:行内显式按钮替换 checkbox;摘要行常驻(默认全转入,无警示)
  await expect(
    dialog.getByText(/将转入「接盘B2」 2 个;回 Backlog 0 个/),
  ).toBeVisible();
  // 覆写「回池A2」:点行内「回 Backlog」按钮 → 行进警示态 + 摘要变警示色计数
  await dialog.getByRole("button", { name: `回 Backlog 回池A2` }).click();
  await expect(dialog.getByText("将回 Backlog")).toBeVisible();
  await expect(
    dialog.getByText(/将转入「接盘B2」 1 个;回 Backlog 1 个/),
  ).toBeVisible();
  await dialog.getByRole("button", { name: "确认处置" }).click();
  await expect(dialog).toBeHidden();

  // 转入项在目标组看板;回池项在 Backlog
  await page.goto(`/p/${projectKey}`);
  await expect(page.getByText("转入A2")).toBeVisible();
  await page.goto(`/p/${projectKey}/backlog`);
  await expect(page.getByText("回池A2")).toBeVisible();
});

test("S3 型目标:完成组时无进行中/计划中→弹窗默认新建名→创建并激活", async ({
  page,
  projectKey,
}) => {
  const gid = await group(projectKey, "冲刺A3", "planned");
  await member(projectKey, "遗孤 item", gid);
  // 完成后项目内无其它非完成组(A3 自己已完成)
  await completeAndOpen(page, projectKey, gid);
  const dialog = page.getByRole("dialog");
  await expect(dialog).toBeVisible();
  await expect(dialog.getByText(/将新建组「Sprint 2」并激活/)).toBeVisible();
  await dialog.getByRole("button", { name: "确认处置" }).click();
  await expect(dialog).toBeHidden();
  await page.goto(`/p/${projectKey}`);
  await expect(page.getByText("遗孤 item")).toBeVisible();
});

test("取消处置→非终态成员全部落 Backlog(兜底)", async ({
  page,
  projectKey,
}) => {
  const gid = await group(projectKey, "冲刺A4", "planned");
  await member(projectKey, "兜底 item", gid);
  await completeAndOpen(page, projectKey, gid);
  const dialog = page.getByRole("dialog");
  await expect(dialog).toBeVisible();
  await dialog.getByRole("button", { name: "取消处置" }).click();
  await expect(dialog).toBeHidden();

  await page.goto(`/p/${projectKey}/backlog`);
  await expect(page.getByText("兜底 item")).toBeVisible();
});

test("无非终态成员→现行确认框,无处置步", async ({ page, projectKey }) => {
  const gid = await group(projectKey, "冲刺A5", "planned");
  await member(projectKey, "终态独苗", gid);
  const proj = await apiCall<{
    statuses: { statusId: string; statusType: string }[];
  }>("GET", `/api/projects/${projectKey}`);
  const finalId = proj.statuses.find((s) => s.statusType === "FINAL")!.statusId;
  const items = await apiCall<{ itemId: string; title: string }[]>(
    "GET",
    `/api/projects/${projectKey}/groups/${gid}/items`,
  );
  await apiAssignMe(items[0].itemId);
  await apiCall("PATCH", `/api/items/${items[0].itemId}`, {
    statusItemId: finalId,
  });

  await completeAndOpen(page, projectKey, gid);
  // 组已完成直接落历史(无处置弹窗)
  await expect(page.getByRole("dialog")).toHaveCount(0);
  await expect(page.getByText(/此 Sprint 已完成/)).toBeVisible();
});
