// 0105:看板创建 item 需求(0105-board-item-creation)——场景矩阵逐项;现行实现=v1,增量二(前置决策树)过目后替换
import { expect, test } from "./support/fixtures";
import { apiCreateItem, apiCreateSprint } from "./support/helpers";

test("board 降级态建 item → 弹窗拉起 → 确认后激活并放入 @smoke", async ({
  page,
  projectKey,
}) => {
  await apiCreateSprint(projectKey, "Launch S1"); // planned
  await page.goto(`/p/${projectKey}`);
  await expect(page.getByText(/当前无进行中 sprint/)).toBeVisible();

  await page.getByPlaceholder("新建 item").fill("拉起 item");
  await page.getByRole("button", { name: "新建 item" }).click();

  // 弹窗:单 planned → 标题+正文含 sprint 名;确认 → 激活+放入
  const dialog = page.getByRole("dialog");
  await expect(dialog).toBeVisible();
  await expect(dialog.getByText(/项目还没有进行中的组/)).toBeVisible();
  await expect(dialog.getByText(/Launch S1/)).toBeVisible();
  await dialog.getByRole("button", { name: "激活" }).click();
  await expect(dialog).toBeHidden();

  // 方案B:看板收窄到 active sprint,刚建 item 在看板可见;降级提示消失
  await expect(page.getByText(/当前无进行中 sprint/)).toBeHidden();
  await expect(page.getByText("拉起 item")).toBeVisible();
});

test("board 降级态建 item → 弹窗取消 → sprint 仍 planned,item 留 backlog", async ({
  page,
  projectKey,
}) => {
  await apiCreateSprint(projectKey, "Launch S2");
  await page.goto(`/p/${projectKey}`);
  // 等板数据就绪(sprints state 填充后提交,弹窗判定才成立;goto 返回≠load 完成)
  await expect(page.getByText(/当前无进行中 sprint/)).toBeVisible();
  await page.getByPlaceholder("新建 item").fill("取消 item");
  await page.getByRole("button", { name: "新建 item" }).click();

  const dialog = page.getByRole("dialog");
  await expect(dialog).toBeVisible();
  await dialog.getByRole("button", { name: "暂不" }).click();
  await expect(dialog).toBeHidden();

  // sprint 未激活:降级提示仍在;看板建的 item onBoard=true → 看板可见、Backlog 不见(必进看板+互斥,2026-11-06 拍板)
  await expect(page.getByText(/当前无进行中 sprint/)).toBeVisible();
  await expect(page.getByText("取消 item")).toBeVisible();
  await page.goto(`/p/${projectKey}/backlog`);
  await expect(page.getByText("取消 item")).toHaveCount(0);
});

test("详情页挂 planned sprint → 弹窗激活 → item 随组进看板", async ({
  page,
  projectKey,
}) => {
  await apiCreateSprint(projectKey, "Launch S3");
  const itemId = await apiCreateItem(projectKey, "挂载 item");
  await page.goto(`/p/${projectKey}/i/${itemId}`);

  // getByLabel('Sprint') 会同时命中侧栏「新建 Sprint」按钮,用 role 收窄
  await page.getByRole("combobox", { name: "Sprint" }).click();
  await page.getByRole("option", { name: /Launch S3/ }).click();

  const dialog = page.getByRole("dialog");
  await expect(dialog).toBeVisible();
  await expect(dialog.getByText(/Launch S3/)).toBeVisible();
  await dialog.getByRole("button", { name: "激活" }).click();
  await expect(dialog).toBeHidden();

  // 端到端语义:item membership 未变,组 active → 看板可见
  await page.goto(`/p/${projectKey}`);
  await expect(page.getByText("挂载 item")).toBeVisible();
});

test("详情页挂 planned → 弹窗取消 → sprint 未激活,item 不可见于看板", async ({
  page,
  projectKey,
}) => {
  await apiCreateSprint(projectKey, "Launch S4");
  const itemId = await apiCreateItem(projectKey, "挂载取消 item");
  await page.goto(`/p/${projectKey}/i/${itemId}`);

  await page.getByRole("combobox", { name: "Sprint" }).click();
  await page.getByRole("option", { name: /Launch S4/ }).click();

  const dialog = page.getByRole("dialog");
  await expect(dialog).toBeVisible();
  await dialog.getByRole("button", { name: "暂不" }).click();
  await expect(dialog).toBeHidden();

  // 挂载生效但 sprint 仍 planned → 降级态看板全量显示非终态 item(含 planned 组内),仅确认未激活
  await page.goto(`/p/${projectKey}`);
  await expect(page.getByText(/当前无进行中 sprint/)).toBeVisible();
  await expect(page.getByText("挂载取消 item")).toBeVisible();
});

test("无组项目看板建 item → 不弹窗 + onBoard 看板可见、Backlog 不可见", async ({
  page,
  projectKey,
}) => {
  // fixture 项目有 level-1 类型但无任何组(=T104Z 形态);0098 补集视图 + 0105 增量拍板
  await page.goto(`/p/${projectKey}`);
  await expect(page.getByText(/项目未配置组/)).toBeVisible();
  await page.getByPlaceholder("新建 item").fill("无组板建 item");
  await page.getByRole("button", { name: "新建 item" }).click();

  await expect(page.getByText(/项目未配置组/)).toBeVisible();
  await expect(page.getByText("无组板建 item")).toBeVisible(); // onBoard=true
  await page.goto(`/p/${projectKey}/backlog`);
  await expect(page.getByText("无组板建 item")).toHaveCount(0);
});
