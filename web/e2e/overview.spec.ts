// V13-S1 Overview 聚合页:侧边栏入口 + 五区块与预置数据一致 + 空项目空态
import { expect, test } from "./support/fixtures";
import { apiAssignMe, apiCall, apiCreateItem, apiCreateSprint, apiMeId } from "./support/helpers";

test("Overview 五区块——状态分布/信号/我的/风险/Sprint", async ({ page, projectKey }) => {
  // 预置:item 覆盖各区块;信号链 = 属性 → 我的能力 → item 需求(requirements PUT 触发重算)
  const statuses = (
    await apiCall<{ statuses: { statusId: string; name: string; position: number }[] }>("GET", `/api/projects/${projectKey}`)
  ).statuses.sort((a, b) => a.position - b.position);

  const mine = await apiCreateItem(projectKey, "OV 我的 item");
  await apiAssignMe(mine);
  const overdue = await apiCreateItem(projectKey, "OV 超期 item");
  await apiCall("PATCH", `/api/items/${overdue}`, { dueDate: new Date(Date.now() - 86_400_000).toISOString().slice(0, 10) });
  const blocker = await apiCreateItem(projectKey, "OV 依赖源 item");
  const blocked = await apiCreateItem(projectKey, "OV 被阻塞 item");
  await apiCall("POST", `/api/items/${blocked}/dependencies`, { dependsOnItemId: blocker });
  // 挪一列,让状态分布出现两列非零(开工须有主:挪列前先指派)
  await apiAssignMe(blocker);
  await apiCall("PATCH", `/api/items/${blocker}`, { statusItemId: statuses[1].statusId });

  const attr = `OV 属性 ${Date.now()}`;
  await apiCall("POST", "/api/attributes", { name: attr, kind: "skill", leveled: true });
  await apiCall("PUT", `/api/members/${await apiMeId()}/capabilities`, { attribute: attr, level: 3 });
  await apiCall("PUT", `/api/items/${mine}/requirements`, { requirements: [{ attribute: attr, minLevel: 2 }] });

  const sprintId = await apiCreateSprint(projectKey, "OV Sprint");
  await apiCall("PATCH", `/api/projects/${projectKey}/sprints/${sprintId}`, { status: "active" });
  await apiCall("PUT", `/api/items/${mine}/sprint`, { sprintId });

  // 侧边栏入口 + 选中态
  await page.goto(`/p/${projectKey}`);
  await page.getByRole("link", { name: "Overview" }).click();
  await expect(page).toHaveURL(new RegExp(`/p/${projectKey}/overview`));
  await expect(page.getByRole("link", { name: "Overview" })).toHaveClass(/Mui-selected/);

  // 状态分布:起始列 3(我的/超期/被阻塞),第二列 1(依赖源)
  await expect(page.getByText(`${statuses[0].name} · 3`)).toBeVisible();
  await expect(page.getByText(`${statuses[1].name} · 1`)).toBeVisible();

  // 信号:无需求 = 全满足(领域语义),4 个 item 全 GREEN
  await expect(page.getByText("✔ 全满足 · 4")).toBeVisible();
  await expect(page.getByText("✗ 有缺门 · 0")).toBeVisible();

  // 我的:含指派给我的,不含他人/超期
  const mineCard = page.locator(".MuiCard-root", { hasText: "我的" }).first();
  await expect(mineCard.getByText(/OV 我的 item/)).toBeVisible();
  await expect(mineCard.getByText(/OV 超期 item/)).toHaveCount(0);

  // 风险:超期 + 被阻塞,带标签
  const riskCard = page.locator(".MuiCard-root", { hasText: "风险" }).first();
  await expect(riskCard.getByText(/OV 超期 item/)).toBeVisible();
  await expect(riskCard.getByText("超期", { exact: true })).toBeVisible();
  await expect(riskCard.getByText(/OV 被阻塞 item/)).toBeVisible();
  await expect(riskCard.getByText("被阻塞", { exact: true })).toBeVisible();

  // Sprint 概况:active sprint + 完成度 0/1,点名可进 sprint 页
  await expect(page.getByText("0/1 完成")).toBeVisible();
  await page.getByRole("link", { name: "OV Sprint", exact: true }).click();
  await expect(page).toHaveURL(new RegExp(`/p/${projectKey}/sprint/`));
});

test("Overview 空项目——空态文案,不炸", async ({ page, projectKey }) => {
  const startName = (
    await apiCall<{ statuses: { name: string; position: number }[] }>("GET", `/api/projects/${projectKey}`)
  ).statuses.sort((a, b) => a.position - b.position)[0].name;

  await page.goto(`/p/${projectKey}/overview`);
  await expect(page.getByText("状态分布")).toBeVisible();
  await expect(page.getByText(`${startName} · 0`)).toBeVisible();
  await expect(page.getByText("没有分配给我的未完成 item")).toBeVisible();
  await expect(page.getByText("没有风险 item")).toBeVisible();
  await expect(page.getByText("暂无进行中的 Sprint")).toBeVisible();
});
