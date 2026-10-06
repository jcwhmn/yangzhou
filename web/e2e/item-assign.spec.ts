// YPJ-1 候选分配体验:收起态/候选面板分色(delta=1 浅橙)/指派给我未足确认 Dialog
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

test("收起态——无 assignee 显示指派按钮不显成员列表;有 assignee 显示 chip", async ({ page, projectKey }) => {
  const empty = await apiCreateItem(projectKey, "AS 无主 item");
  await page.goto(`/p/${projectKey}/i/${empty}`);
  await expect(page.getByRole("button", { name: "指派给我" })).toBeVisible();
  await expect(page.getByRole("button", { name: "分配成员…" })).toBeVisible();
  await expect(page.getByText("缺门0·差0级")).toHaveCount(0); // 成员列表收起

  const taken = await apiCreateItem(projectKey, "AS 有主 item");
  await apiAssignMe(taken);
  await page.goto(`/p/${projectKey}/i/${taken}`);
  await expect(page.getByText(/^👤 /).first()).toBeVisible();
  await expect(page.getByRole("button", { name: "重新分配" })).toBeVisible();
  await expect(page.getByRole("button", { name: "分配成员…" })).toHaveCount(0);
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
  await page.getByRole("button", { name: "分配成员…" }).click();

  const cards = page.locator(".MuiCard-root");
  // 排序 = 缺门少优先 → 总差距小优先:me(0,0) → 小张(0,1) → 小王(1,0)
  await expect(cards.nth(0)).toContainText("缺门0·差0级");
  await expect(cards.nth(1)).toContainText("缺门0·差1级");
  await expect(cards.nth(2)).toContainText("缺门1·差0级");
  // 分色:绿 / 浅橙 #ff9800 / 红
  await expect(cards.nth(0)).toHaveCSS("border-color", "rgb(46, 125, 50)");
  await expect(cards.nth(1)).toHaveCSS("border-color", "rgb(255, 152, 0)");
  await expect(cards.nth(2)).toHaveCSS("border-color", "rgb(211, 47, 47)");
});

test("指派给我——无未足项直接执行", async ({ page, projectKey }) => {
  const itemId = await apiCreateItem(projectKey, "AS 干净直达");
  await page.goto(`/p/${projectKey}/i/${itemId}`);
  await page.getByRole("button", { name: "指派给我" }).click();
  await expect(page.getByText("存在未满足项")).toHaveCount(0); // 不弹确认
  await expect(page.getByText(/^👤 /).first()).toBeVisible();
});

test("指派给我——有未足项先确认:取消维持现状,确认后指派成功", async ({ page, projectKey }) => {
  const attr = uniqueAttr("AS 属性"); // me 无该能力 → 候选为缺门(RED,未足)
  await createAttr(attr);
  const itemId = await apiCreateItem(projectKey, "AS 未足确认");
  await apiCall("PUT", `/api/items/${itemId}/requirements`, { requirements: [{ attribute: attr, minLevel: 2 }] });
  await page.goto(`/p/${projectKey}/i/${itemId}`);

  const dialog = page.getByRole("dialog");
  // 第一次:弹确认 → 取消 → 未指派
  await page.getByRole("button", { name: "指派给我" }).click();
  await expect(dialog).toBeVisible();
  await dialog.getByRole("button", { name: "取消" }).click();
  await expect(dialog).toBeHidden(); // MUI 淡出期会吞下层交互,先等消失
  await expect(page.getByText(/^👤 /)).toHaveCount(0);
  // 第二次:仍然指派 → 成功
  await page.getByRole("button", { name: "指派给我" }).click();
  await expect(dialog).toBeVisible();
  await dialog.getByRole("button", { name: "仍然指派" }).click();
  await expect(dialog).toBeHidden();
  await expect(page.getByText(/^👤 /).first()).toBeVisible();
});

test("面板指派缺门候选——淡出警告确认后指派成功", async ({ page, projectKey }) => {
  const attr = uniqueAttr("AS 属性");
  await createAttr(attr);
  await apiCreateMember("小王");
  const itemId = await apiCreateItem(projectKey, "AS 缺门候选");
  await apiCall("PUT", `/api/items/${itemId}/requirements`, { requirements: [{ attribute: attr, minLevel: 2 }] });
  await page.goto(`/p/${projectKey}/i/${itemId}`);
  await page.getByRole("button", { name: "分配成员…" }).click();

  const wang = page.locator(".MuiCard-root", { hasText: "小王" });
  await wang.getByRole("button", { name: "指派", exact: true }).click();
  const dialog = page.getByRole("dialog");
  await expect(dialog).toBeVisible();
  await expect(dialog).toContainText("小王");
  await dialog.getByRole("button", { name: "仍然指派" }).click();
  await expect(dialog).toBeHidden();
  await expect(page.getByText(/^👤 小王/).first()).toBeVisible(); // 指派后面板收起,chip 直显
});
