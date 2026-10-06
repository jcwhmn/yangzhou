// YPJ-1/6 候选分配体验:头部入口/Popover 分色(delta=1 浅橙)/未足确认/取消指派
import { randomUUID } from "node:crypto";
import { expect, test } from "./support/fixtures";
import { apiAssignMe, apiCall, apiCreateItem, apiCreateMember, apiMeId } from "./support/helpers";

/** 属性名带随机后缀:并行用例/重跑同毫秒也不撞(409) */
const uniqueAttr = (prefix: string) => `${prefix} ${randomUUID().slice(0, 8)}`;

/** 建属性(同名重复调会 409,一属性只建一次) */
async function createAttr(name: string) {
  await apiCall("POST", "/api/attributes", { name, kind: "skill", leveled: true });
}

/** 给指定成员设能力等级(memberName 为空 = me) */
async function setCap(attr: string, level: number, memberName?: string) {
  const memberId = memberName
    ? (await apiCall<{ memberId: string; displayName: string }[]>("GET", "/api/members")).find(
        (m) => m.displayName === memberName,
      )!.memberId
    : await apiMeId();
  await apiCall("PUT", `/api/members/${memberId}/capabilities`, { attribute: attr, level });
}

test("详情页头部——未指派显示分配入口;已指派显示 chip;谁来做区块已移除", async ({ page, projectKey }) => {
  const empty = await apiCreateItem(projectKey, "AS 无主 item");
  await page.goto(`/p/${projectKey}/i/${empty}`);
  await expect(page.getByText("分配", { exact: true })).toBeVisible(); // 头部入口(状态下拉前)
  await expect(page.getByText("缺门0·差0级")).toHaveCount(0); // 候选 Popover 收起
  await expect(page.getByText("谁来做")).toHaveCount(0); // 区块已从详情移除

  const taken = await apiCreateItem(projectKey, "AS 有主 item");
  await apiAssignMe(taken);
  await page.goto(`/p/${projectKey}/i/${taken}`);
  await expect(page.getByText(/^👤 /).first()).toBeVisible();
  await expect(page.getByText("分配", { exact: true })).toHaveCount(0);
});

test("展开面板——按 rank 排序,分色:绿/浅橙(delta=1)/红", async ({ page, projectKey }) => {
  test.slow(); // 2 次能力 PUT 各触发 recomputeWorkspace(全 workspace 逐 item 重算,V9-Q2),胖测试库下远超 30s
  const attr = uniqueAttr("AS 属性");
  await apiCreateMember("小张");
  await createAttr(attr);
  await setCap(attr, 3); // me 满足 → GREEN
  await setCap(attr, 1, "小张"); // 差 1 级 → 勉强浅橙
  await apiCreateMember("小王"); // 无能力 → 缺门 RED

  const itemId = await apiCreateItem(projectKey, "AS 分色 item");
  await apiCall("PUT", `/api/items/${itemId}/requirements`, { requirements: [{ attribute: attr, minLevel: 2 }] });
  await page.goto(`/p/${projectKey}/i/${itemId}`);
  await page.getByText("分配", { exact: true }).click(); // 头部入口

  const pop = page.locator(".MuiPopover-paper");
  const rows = pop.locator("[data-testid=cand-row]");
  // 排序 = 缺门少优先 → 总差距小优先:me(0,0) → 小张(0,1) → 小王(1,0)
  await expect(rows.nth(0)).toContainText("缺门0·差0级");
  await expect(rows.nth(1)).toContainText("缺门0·差1级");
  await expect(rows.nth(2)).toContainText("缺门1·差0级");
  // 分色:绿 / 浅橙 #ff9800 / 红
  await expect(rows.nth(0)).toHaveCSS("border-left-color", "rgb(46, 125, 50)");
  await expect(rows.nth(1)).toHaveCSS("border-left-color", "rgb(255, 152, 0)");
  await expect(rows.nth(2)).toHaveCSS("border-left-color", "rgb(198, 40, 40)");
});

test("详情页指派——无未足项直接执行不弹确认", async ({ page, projectKey }) => {
  const itemId = await apiCreateItem(projectKey, "AS 干净直达");
  await page.goto(`/p/${projectKey}/i/${itemId}`);
  await page.getByText("分配", { exact: true }).click();
  const pop = page.locator(".MuiPopover-paper");
  await expect(pop).toBeVisible();
  await pop.locator("[data-testid=cand-row]", { hasText: "me" }).getByRole("button", { name: "指派", exact: true }).click();
  await expect(page.getByText("存在未满足项")).toHaveCount(0); // 不弹确认
  await expect(page.getByText(/^👤 me/).first()).toBeVisible();
});

test("详情页指派——有未足项先确认:取消维持现状,确认后指派成功", async ({ page, projectKey }) => {
  const attr = uniqueAttr("AS 属性"); // me 无该能力 → 候选为缺门(RED,未足)
  await createAttr(attr);
  const itemId = await apiCreateItem(projectKey, "AS 未足确认");
  await apiCall("PUT", `/api/items/${itemId}/requirements`, { requirements: [{ attribute: attr, minLevel: 2 }] });
  await page.goto(`/p/${projectKey}/i/${itemId}`);

  const pop = page.locator(".MuiPopover-paper");
  const dialog = page.getByRole("dialog");
  const meRow = pop.locator("[data-testid=cand-row]", { hasText: "me" });
  // 第一次:指派 me → 弹确认 → 取消 → 未指派(Popover 保持开着可继续选)
  await page.getByText("分配", { exact: true }).click();
  await meRow.getByRole("button", { name: "指派", exact: true }).click();
  await expect(dialog).toBeVisible();
  await dialog.getByRole("button", { name: "取消" }).click();
  await expect(dialog).toBeHidden(); // MUI 淡出期会吞下层交互,先等消失
  await expect(page.getByText(/^👤 me/)).toHaveCount(0);
  // 第二次:仍然指派 → 成功
  await meRow.getByRole("button", { name: "指派", exact: true }).click();
  await expect(dialog).toBeVisible();
  await dialog.getByRole("button", { name: "仍然指派" }).click();
  await expect(dialog).toBeHidden();
  await expect(page.getByText(/^👤 me/).first()).toBeVisible();
});

test("Popover 指派缺门候选——淡出警告确认后指派成功", async ({ page, projectKey }) => {
  const attr = uniqueAttr("AS 属性");
  await createAttr(attr);
  await apiCreateMember("小王");
  const itemId = await apiCreateItem(projectKey, "AS 缺门候选");
  await apiCall("PUT", `/api/items/${itemId}/requirements`, { requirements: [{ attribute: attr, minLevel: 2 }] });
  await page.goto(`/p/${projectKey}/i/${itemId}`);
  await page.getByText("分配", { exact: true }).click();

  const pop = page.locator(".MuiPopover-paper");
  const wang = pop.locator("[data-testid=cand-row]", { hasText: "小王" });
  await wang.getByRole("button", { name: "指派", exact: true }).click();
  const dialog = page.getByRole("dialog");
  await expect(dialog).toBeVisible();
  await expect(dialog).toContainText("小王");
  await dialog.getByRole("button", { name: "仍然指派" }).click();
  await expect(dialog).toBeHidden();
  await expect(page.getByText(/^👤 小王/).first()).toBeVisible(); // 指派后 Popover 收起,chip 直显
});

test("看板卡片指派入口——Popover 弹出/Esc 收起/指派成功且不跳转", async ({ page, projectKey }) => {
  await apiCreateItem(projectKey, "AP 卡片指派");
  await page.goto(`/p/${projectKey}`);
  const card = page.locator(".MuiCard-root", { hasText: "AP 卡片指派" });
  await card.getByText("未指派").click(); // 占位入口
  const pop = page.locator(".MuiPopover-paper");
  await expect(pop).toBeVisible();
  await expect(page).not.toHaveURL(/\/i\//); // 点入口不跳详情
  await page.keyboard.press("Escape");
  await expect(pop).toBeHidden(); // 淡出期后再交互
  // 重开,指派第一个候选 → 卡片 chip 直显
  await card.getByText("未指派").click();
  await expect(pop).toBeVisible();
  await pop.locator("[data-testid=cand-row]").first().getByRole("button", { name: "指派", exact: true }).click();
  await expect(pop).toBeHidden();
  await expect(card.getByText(/^👤 /)).toBeVisible();
});

test("重分配——候选列表标注当前负责人", async ({ page, projectKey }) => {
  const itemId = await apiCreateItem(projectKey, "AP 当前标注");
  await apiAssignMe(itemId);
  await page.goto(`/p/${projectKey}`);
  const card = page.locator(".MuiCard-root", { hasText: "AP 当前标注" });
  await card.getByText(/^👤 /).click();
  const pop = page.locator(".MuiPopover-paper");
  await expect(pop).toBeVisible();
  await expect(pop.locator("[data-testid=cand-row]", { hasText: "me" })).toContainText("当前");
});

test("取消指派——chip × 与 Popover 内取消指派", async ({ page, projectKey }) => {
  const itemId = await apiCreateItem(projectKey, "AS 取消指派");
  await apiAssignMe(itemId);
  await page.goto(`/p/${projectKey}/i/${itemId}`);
  // chip × → 未指派,占位入口回归
  await page.locator(".MuiChip-deleteIcon").first().click();
  await expect(page.getByText(/^👤 me/)).toHaveCount(0);
  await expect(page.getByText("分配", { exact: true })).toBeVisible();
  // Popover 重新指派 me,再从 Popover 内取消
  await page.getByText("分配", { exact: true }).click();
  const pop = page.locator(".MuiPopover-paper");
  await expect(pop).toBeVisible();
  await pop.locator("[data-testid=cand-row]", { hasText: "me" }).getByRole("button", { name: "指派", exact: true }).click();
  await expect(page.getByText(/^👤 me/).first()).toBeVisible();
  await page.getByText(/^👤 me/).first().click();
  await expect(pop).toBeVisible();
  await pop.getByRole("button", { name: "取消指派" }).click();
  await expect(pop).toBeHidden();
  await expect(page.getByText(/^👤 me/)).toHaveCount(0);
});
