// V14 Epics 侧边栏段:顶层 goal item + rollup 色点(红>黄>绿);非 goal 不进段
import { expect, test } from "./support/fixtures";
import { apiAssignMe, apiCall, apiCreateItem, apiMeId } from "./support/helpers";

test("Epic 段——goal 显示 + rollup 色点 + 点击进详情,非 goal 不显示", async ({ page, projectKey }) => {
  // 预置:goal item + 挂其下的 task 子项 + 独立 task;信号链(属性→能力→需求)→ goal 子树 GREEN
  const attr = `EP 属性 ${Date.now()}`;
  await apiCall("POST", "/api/attributes", { name: attr, kind: "skill", leveled: true });
  await apiCall("PUT", `/api/members/${await apiMeId()}/capabilities`, { attribute: attr, level: 3 });

  const epic = await apiCreateItem(projectKey, "EP Epic 大目标");
  await apiCall("PATCH", `/api/items/${epic}`, { type: "goal" });
  const child = await apiCreateItem(projectKey, "EP 子任务");
  await apiCall("PATCH", `/api/items/${child}`, { type: "task", parentItemId: epic });
  const plain = await apiCreateItem(projectKey, "EP 普通任务");
  await apiAssignMe(plain); // 占用避免空态干扰;task 不进 Epic 段

  await apiCall("PUT", `/api/items/${child}/requirements`, { requirements: [{ attribute: attr, minLevel: 2 }] });

  await page.goto(`/p/${projectKey}`);
  await page.getByRole("link", { name: "Overview" }).click(); // 触发一次完整导航,观察侧边栏

  // Epic 段:goal 在(作用域收窄到侧边栏 nav,避开看板 main 里的同名卡片链接),普通 task 不在
  const nav = page.getByRole("navigation");
  const epicItem = nav.getByRole("link", { name: /EP Epic 大目标/ });
  await expect(epicItem).toBeVisible();
  // rollup 色点:子任务 GREEN(能力 3 ≥ 需求 2)→ 祖先点绿(色点 aria-label 即信号文案)
  await expect(epicItem.getByLabel("✔ 全满足")).toBeVisible();
  await expect(nav.getByRole("link", { name: /EP 普通任务/ })).toHaveCount(0);

  // 点击进详情
  await epicItem.click();
  await expect(page).toHaveURL(new RegExp(`/p/${projectKey}/i/`));
});

test("Epic 段空态文案", async ({ page, projectKey }) => {
  await page.goto(`/p/${projectKey}`);
  await expect(page.getByText("暂无 Epic")).toBeVisible();
});
