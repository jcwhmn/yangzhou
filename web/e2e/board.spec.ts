// 看板与 item 详情:建项/评论/状态/日期/优先级/依赖/回收站/过滤/错误浮出
import { expect } from "./support/fixtures";
import { test } from "./support/fixtures";
import { apiAssignMe, apiCreateItem } from "./support/helpers";

test("建 item → 详情 → 评论 → 指派 → 状态 QA → 活动日志", async ({ page, projectKey }) => {
  await page.goto(`/p/${projectKey}`);
  await page.getByPlaceholder("新建 item").fill("E2E 冒烟 item");
  await page.getByRole("button", { name: "新建 ITEM" }).click();
  await page.getByText("E2E 冒烟 item").first().click();

  await expect(page.getByRole("heading", { name: "谁来做" })).toBeVisible();
  await page.getByPlaceholder("写评论…").fill("E2E 冒烟评论");
  await page.getByRole("button", { name: "发送" }).click();
  await expect(page.getByText("E2E 冒烟评论")).toBeVisible();

  await page.getByRole("button", { name: "指派给我" }).click();
  await expect(page.getByText("👤 me").first()).toBeVisible();

  await page.getByRole("combobox", { name: "item 状态" }).click();
  const listbox = page.getByRole("listbox");
  await expect(listbox).toBeVisible();
  await listbox.getByRole("option", { name: "QA" }).click();
  await expect(page.getByRole("combobox", { name: "item 状态" })).toHaveText("QA");
  await expect(page.getByText("活动日志")).toBeVisible();
  await expect(page.getByText(/状态变更: To Do → QA/)).toBeVisible();
});

test("日期与超期标识——详情设置日期,看板红标,甘特可见", async ({ page, projectKey }) => {
  // 另造一个无日期 item:甘特「未排期」桶 length>0 才渲染,只有带日期 item 时桶不出现
  await apiCreateItem(projectKey, "E2E 未排期 item");
  await page.goto(`/p/${projectKey}`);
  await page.getByPlaceholder("新建 item").fill("E2E 超期 item");
  await page.getByRole("button", { name: "新建 ITEM" }).click();
  await page.getByText("E2E 超期 item").first().click();
  await page.getByLabel("开始日期").fill("2020-01-01");
  await page.getByLabel("截止日期").fill("2020-01-15");
  const saveResp = page.waitForResponse((r) => r.url().includes("/api/items/") && r.request().method() === "PATCH");
  await page.getByRole("button", { name: "保存" }).click();
  await saveResp; // 等 PATCH 落库再导航,否则请求被打断
  await expect(page.getByLabel("开始日期")).toHaveValue("2020-01-01");
  // 看板红标
  await page.goto(`/p/${projectKey}`);
  await expect(page.getByText("⚠ 超期 2020-01-15")).toBeVisible();
  // 甘特:行可见 + 未排期组存在
  await page.goto(`/p/${projectKey}/gantt`);
  await expect(page.getByText("E2E 超期 item")).toBeVisible();
  await expect(page.getByText(/未排期\(/)).toBeVisible();
});

test("V11-S4 detail 优先级 Select 接通(P1 落库/清除)", async ({ page, projectKey }) => {
  const itemId = await apiCreateItem(projectKey, "E2E 优先级 item");
  await page.goto(`/p/${projectKey}/i/${itemId}`);
  await page.getByRole("combobox", { name: "优先级" }).click();
  const plist = page.getByRole("listbox");
  await plist.getByRole("option", { name: "P1" }).click();
  let saveResp = page.waitForResponse((r) => r.url().includes("/api/items/") && r.request().method() === "PATCH");
  await page.getByRole("button", { name: "保存" }).click();
  await saveResp;
  await page.reload();
  await expect(page.getByRole("combobox", { name: "优先级" })).toHaveText("P1");
  // 清除
  await page.getByRole("combobox", { name: "优先级" }).click();
  await plist.getByRole("option", { name: "无" }).click();
  saveResp = page.waitForResponse((r) => r.url().includes("/api/items/") && r.request().method() === "PATCH");
  await page.getByRole("button", { name: "保存" }).click();
  await saveResp;
  await page.reload();
  await expect(page.getByRole("combobox", { name: "优先级" })).not.toHaveText("P1");
});

test("V10 priority 设置与表格视图", async ({ page, projectKey }) => {
  const itemId = await apiCreateItem(projectKey, "E2E 表格 item");
  await page.goto(`/p/${projectKey}/i/${itemId}`);
  await page.getByRole("combobox", { name: "优先级" }).click();
  await page.getByRole("listbox").getByRole("option", { name: "P1" }).click();
  const saveResp = page.waitForResponse((r) => r.url().includes("/api/items/") && r.request().method() === "PATCH");
  await page.getByRole("button", { name: "保存" }).click();
  await saveResp;
  // 表格视图页可达且 P1 可见(优先级列默认隐藏,先开列)
  await page.goto(`/p/${projectKey}/table`);
  await page.getByText("优先级", { exact: true }).click();
  await expect(page.getByText("E2E 表格 item").first()).toBeVisible();
  await expect(page.getByText("P1").first()).toBeVisible();
});

test("V10 依赖——blocked 标识", async ({ page, projectKey }) => {
  await apiCreateItem(projectKey, "E2E 依赖 item");
  await apiCreateItem(projectKey, "E2E 被卡 item");
  await page.goto(`/p/${projectKey}`);
  // 打开被卡 item 详情,把依赖 item 设为它的前置
  await page.getByText("E2E 被卡 item").first().click();
  // 依赖区块在「依赖+清单」tab
  await page.getByRole("tab", { name: "依赖+清单" }).click();
  await page.getByRole("button", { name: "加依赖" }).click();
  await page.getByRole("combobox", { name: "被依赖 item" }).selectOption({ index: 1 });
  await page.getByRole("button", { name: "确认添加依赖" }).click();
  await expect(page.getByText("阻塞中").first()).toBeVisible();
});

test("回收站——删除后可恢复", async ({ page, projectKey }) => {
  await page.goto(`/p/${projectKey}`);
  await page.getByPlaceholder("新建 item").fill("E2E 回收站 item");
  await page.getByRole("button", { name: "新建 ITEM" }).click();
  await page.getByText("E2E 回收站 item").first().click();
  // 详情删除(原生 confirm 自动接受)
  page.once("dialog", (d) => d.accept());
  await page.getByRole("button", { name: "删除", exact: true }).click();
  await expect(page).toHaveURL(new RegExp(`/p/${projectKey}$`));
  // 回收站列出 → 恢复 → 看板重现
  await page.goto("/recycle-bin");
  await page.getByText("E2E 回收站 item").waitFor({ timeout: 10000 });
  await page.getByRole("button", { name: "恢复" }).first().click();
  await page.goto(`/p/${projectKey}`);
  await page.getByText("E2E 回收站 item").first().waitFor({ timeout: 10000 });
});

test("看板刷新按钮可用", async ({ page, projectKey }) => {
  await page.goto(`/p/${projectKey}`);
  await page.getByRole("button", { name: "刷新" }).click();
  await expect(page.getByRole("heading", { name: "To Do", exact: false })).toBeVisible();
});

test("V13-S4 详情切状态 409——错误浮出且状态不变", async ({ page, projectKey }) => {
  const itemId = await apiCreateItem(projectKey, "E2E 无主 item");
  await page.goto(`/p/${projectKey}/i/${itemId}`);
  await page.getByRole("combobox", { name: "item 状态" }).click();
  await page.getByRole("option", { name: "Done" }).click();
  // V4-S3 规则:开工须有主;JCW-160-E:错误浮出而非静默
  await expect(page.getByText("开工前请先指派负责人")).toBeVisible();
  await expect(page.getByRole("combobox", { name: "item 状态" })).toHaveText("To Do");
});

test("V13-S4 看板 Sprint 过滤——未规划视图", async ({ page, projectKey }) => {
  await apiCreateItem(projectKey, "E2E 过滤 item");
  await page.goto(`/p/${projectKey}`);
  await page.getByLabel("sprint 过滤").click();
  await page.getByRole("option", { name: "未规划" }).click();
  await expect(page.getByText("E2E 过滤 item").first()).toBeVisible();
});
