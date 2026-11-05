// 0105:看板创建 item 需求(0105-board-item-creation)——场景矩阵逐项
// S0 无类型 / S1 单进行中 / S2+S2m 激活并放入 / S3 新建并激活 / D10 详情页挂计划中
// 前置决策树在提交时执行,须等 board/groups 数据就绪(goto 返回≠load 完成)再提交
import { expect, test } from "./support/fixtures";
import {
  apiCall,
  apiCreateItem,
  apiCreateSprint,
  injectToken,
  uniqueKey,
} from "./support/helpers";

/** 等看板首屏两个请求落定(sprints state 填充后,提交的场景判定才成立) */
async function waitBoardData(page: import("@playwright/test").Page) {
  await Promise.all([
    page.waitForResponse((r) => r.url().includes("/board")),
    page.waitForResponse((r) => r.url().includes("/groups")),
  ]);
}

test("S2 建item→激活并放入 @smoke", async ({ page, projectKey }) => {
  await apiCreateSprint(projectKey, "计划中B"); // planned
  await page.goto(`/p/${projectKey}`);
  await expect(page.getByText(/当前无进行中 sprint/)).toBeVisible();

  await page.getByPlaceholder("新建 item").fill("拉起 item");
  await page.getByRole("button", { name: "新建 item" }).click();

  const dialog = page.getByRole("dialog");
  await expect(dialog).toBeVisible();
  await expect(dialog.getByText(/项目还没有进行中的组/)).toBeVisible();
  await expect(dialog.getByText(/计划中B/)).toBeVisible();
  await dialog.getByRole("button", { name: "激活并放入" }).click();
  await expect(dialog).toBeHidden();

  // B 变进行中(提示条消失),item 挂入即看板可见
  await expect(page.getByText(/当前无进行中 sprint/)).toBeHidden();
  await expect(page.getByText("拉起 item")).toBeVisible();
});

test("S2 取消=放弃创建:item 不落库,标题保留输入框", async ({
  page,
  projectKey,
}) => {
  await apiCreateSprint(projectKey, "计划中B");
  await page.goto(`/p/${projectKey}`);
  await expect(page.getByText(/当前无进行中 sprint/)).toBeVisible();

  await page.getByPlaceholder("新建 item").fill("取消 item");
  await page.getByRole("button", { name: "新建 item" }).click();
  const dialog = page.getByRole("dialog");
  await expect(dialog).toBeVisible();
  await dialog.getByRole("button", { name: "取消" }).click();
  await expect(dialog).toBeHidden();

  // D6:游离非法 → 无「暂不」;取消=整个创建不发生
  await expect(page.getByText(/当前无进行中 sprint/)).toBeVisible();
  await expect(page.getByPlaceholder("新建 item")).toHaveValue("取消 item");
  await expect(page.getByText("取消 item", { exact: true })).toHaveCount(0);
  await page.goto(`/p/${projectKey}/backlog`);
  await expect(page.getByText("取消 item")).toHaveCount(0);
});

test("S2m 多计划中组→列表选择→激活并放入", async ({ page, projectKey }) => {
  await apiCreateSprint(projectKey, "计划中B1");
  await apiCreateSprint(projectKey, "计划中B2");
  await page.goto(`/p/${projectKey}`);
  await expect(page.getByText(/当前无进行中 sprint/)).toBeVisible();

  await page.getByPlaceholder("新建 item").fill("选择 item");
  await page.getByRole("button", { name: "新建 item" }).click();
  const dialog = page.getByRole("dialog");
  await expect(dialog).toBeVisible();
  await expect(dialog.getByText(/要激活哪个组/)).toBeVisible();
  await dialog.getByRole("button", { name: "计划中B2" }).click();
  await dialog.getByRole("button", { name: "激活并放入" }).click();
  await expect(dialog).toBeHidden();

  await expect(page.getByText(/当前无进行中 sprint/)).toBeHidden();
  await expect(page.getByText("选择 item")).toBeVisible();
});

test("S1 单进行中组→直挂,无弹窗", async ({ page, projectKey }) => {
  const gid = await apiCreateSprint(projectKey, "进行中A");
  await apiCall(
    "POST",
    `/api/projects/${projectKey}/groups/${gid}/actions/start`,
  );
  const board = page.waitForResponse((r) => r.url().includes("/board"));
  const groups = page.waitForResponse((r) => r.url().includes("/groups"));
  await page.goto(`/p/${projectKey}`);
  await board;
  await groups;

  await page.getByPlaceholder("新建 item").fill("直挂 item");
  await page.getByRole("button", { name: "新建 item" }).click();

  // 无弹窗(有 active 时建 item 不触发拉起);item 直挂可见
  await expect(page.getByRole("dialog")).toHaveCount(0);
  await expect(page.getByText(/当前无进行中 sprint/)).toBeHidden();
  await expect(page.getByText("直挂 item")).toBeVisible();
});

test("S3 有类型无非完成态实例→默认名可改→创建并激活", async ({
  page,
  projectKey,
}) => {
  const gid = await apiCreateSprint(projectKey, "已完成C");
  await apiCall(
    "POST",
    `/api/projects/${projectKey}/groups/${gid}/actions/start`,
  );
  await apiCall(
    "POST",
    `/api/projects/${projectKey}/groups/${gid}/actions/complete`,
  );
  await page.goto(`/p/${projectKey}`);
  await expect(page.getByText(/当前无进行中 sprint/)).toBeVisible();

  await page.getByPlaceholder("新建 item").fill("新建组 item");
  await page.getByRole("button", { name: "新建 item" }).click();
  const dialog = page.getByRole("dialog");
  await expect(dialog).toBeVisible();
  // 默认名 = 类型名 + 实例数+1(已完成C 是 1 号)
  const nameInput = dialog.getByLabel("组名");
  await expect(nameInput).toHaveValue("Sprint 2");
  await nameInput.fill("冲刺X");
  await dialog.getByRole("button", { name: "创建并激活" }).click();
  await expect(dialog).toBeHidden();

  await expect(page.getByText(/当前无进行中 sprint/)).toBeHidden();
  await expect(page.getByText("新建组 item")).toBeVisible();
});

test("S3-Z 有类型 0 实例→默认名 序号1", async ({ page, projectKey }) => {
  // fixture 项目有 sprint 类型、无任何组实例
  await page.goto(`/p/${projectKey}`);
  await expect(page.getByText(/项目未配置组/)).toBeVisible();

  await page.getByPlaceholder("新建 item").fill("首个 item");
  await page.getByRole("button", { name: "新建 item" }).click();
  const dialog = page.getByRole("dialog");
  await expect(dialog).toBeVisible();
  await expect(dialog.getByLabel("组名")).toHaveValue("Sprint 1");
  await dialog.getByRole("button", { name: "创建并激活" }).click();
  await expect(dialog).toBeHidden();

  await expect(page.getByText(/项目未配置组/)).toBeHidden();
  await expect(page.getByText("首个 item")).toBeVisible();
});

test("S0 无组类型项目建 item→onBoard 看板可见,Backlog 不可见,无组字样", async ({
  page,
}) => {
  await injectToken(page);
  const key = uniqueKey();
  // 裸建项目(不经 fixture——fixture 会补种组类型),S0 = 无 level-1 组类型
  await apiCall("POST", "/api/projects", { key, name: `E2E ${key}` });
  await page.goto(`/p/${key}`);
  await expect(page.getByText(/项目未配置组/)).toBeVisible();

  await page.getByPlaceholder("新建 item").fill("无类型 item");
  await page.getByRole("button", { name: "新建 item" }).click();

  // 无弹窗(0101 铁律:无组概念);onBoard → 看板可见、Backlog 无
  await expect(page.getByRole("dialog")).toHaveCount(0);
  await expect(page.getByText("无类型 item")).toBeVisible();
  await page.goto(`/p/${key}/backlog`);
  await expect(page.getByText("无类型 item")).toHaveCount(0);
});

test("D10 详情页挂计划中组→弹窗激活→item 随组进看板", async ({
  page,
  projectKey,
}) => {
  await apiCreateSprint(projectKey, "计划中B3");
  const itemId = await apiCreateItem(projectKey, "挂载 item");
  await page.goto(`/p/${projectKey}/i/${itemId}`);

  // getByLabel('Sprint') 会同时命中侧栏「新建 Sprint」按钮,用 role 收窄
  await page.getByRole("combobox", { name: "Sprint" }).click();
  await page.getByRole("option", { name: /计划中B3/ }).click();

  const dialog = page.getByRole("dialog");
  await expect(dialog).toBeVisible();
  await expect(dialog.getByText(/计划中B3/)).toBeVisible();
  await dialog.getByRole("button", { name: "激活" }).click();
  await expect(dialog).toBeHidden();

  // 端到端语义:item membership 未变,组进行中 → 看板可见
  await page.goto(`/p/${projectKey}`);
  await expect(page.getByText("挂载 item")).toBeVisible();
});

test("D10 详情页挂计划中组→暂不→挂载生效但未激活", async ({
  page,
  projectKey,
}) => {
  await apiCreateSprint(projectKey, "计划中B4");
  const itemId = await apiCreateItem(projectKey, "挂载取消 item");
  await page.goto(`/p/${projectKey}/i/${itemId}`);

  await page.getByRole("combobox", { name: "Sprint" }).click();
  await page.getByRole("option", { name: /计划中B4/ }).click();

  const dialog = page.getByRole("dialog");
  await expect(dialog).toBeVisible();
  await dialog.getByRole("button", { name: "暂不" }).click();
  await expect(dialog).toBeHidden();

  // 挂载生效但组仍计划中 → item 在补集看板可见(不在 Backlog;planned 成员被谓词排除)
  await page.goto(`/p/${projectKey}`);
  await expect(page.getByText(/当前无进行中 sprint/)).toBeVisible();
  await expect(page.getByText("挂载取消 item")).toBeVisible();
});
