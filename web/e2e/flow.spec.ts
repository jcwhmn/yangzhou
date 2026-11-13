// YPJ-46 Flow 页(spec 0044):七卡容器可见 + 空态/数据态切换;sprint 详情 burndown 上线
import { expect, test } from "./support/fixtures";
import { apiAssignMe, apiCall, apiCreateItem } from "./support/helpers";

test("Flow 页五图一表——数据态卡片与口径 caption", async ({
  page,
  projectKey,
}) => {
  const statuses = (
    await apiCall<{
      statuses: { statusId: string; name: string; position: number }[];
    }>("GET", `/api/projects/${projectKey}`)
  ).statuses.sort((a, b) => a.position - b.position);
  // 1 wip(指派 + 挪列)+ 1 done(挪到 final 列,造 throughput/散点数据)
  const wip = await apiCreateItem(projectKey, "FL WIP item");
  await apiAssignMe(wip);
  await apiCall("PATCH", `/api/items/${wip}`, {
    statusItemId: statuses[1].statusId,
  });
  const done = await apiCreateItem(projectKey, "FL Done item");
  await apiAssignMe(done);
  await apiCall("PATCH", `/api/items/${done}`, {
    statusItemId: statuses[statuses.length - 1].statusId,
  });

  await page.goto(`/p/${projectKey}/flow`);
  await expect(page.getByText("WIP(按负责人、状态堆叠)").first()).toBeVisible();
  await expect(page.getByText("吞吐(每日完成)").first()).toBeVisible();
  await expect(page.getByText("Cycle / Lead 散点").first()).toBeVisible();
  await expect(page.getByText("累积流(CFD)").first()).toBeVisible();
  await expect(page.getByText("Aging / 瓶颈").first()).toBeVisible();
  await expect(
    page.getByText("Velocity(近 8 个 Sprint)").first(),
  ).toBeVisible();
  // 无 active sprint:burnup 卡显示占位
  // spec 0101 案B:无组 → 中性文案
  await expect(page.getByText("暂无进行中的组")).toBeVisible();
  // 数据真的进了图:WIP 图例出现状态名、页面有图表 svg(空态也显示标题,标题断言拦不住 400/空数据)
  await expect(page.getByText("In Progress").first()).toBeVisible();
  await expect(page.locator("svg").nth(1)).toBeVisible();
});

test("Sprint 详情 burndown——active sprint 出现剩余/理想线图例", async ({
  page,
  projectKey,
}) => {
  // burndown 端点要求起止日期:直建带日期 sprint(helper 建的是无日期)
  const sprintId = (
    await apiCall<{ groupId: string }>(
      "POST",
      `/api/projects/${projectKey}/groups`,
      {
        typeKey: "sprint",
        name: "FL Sprint",
        startDate: new Date(Date.now() - 7 * 864e5).toISOString().slice(0, 10),
        endDate: new Date(Date.now() + 7 * 864e5).toISOString().slice(0, 10),
      },
    )
  ).groupId;
  const item = await apiCreateItem(projectKey, "FL Sprint item");
  await apiCall("PUT", `/api/items/${item}/group-membership`, {
    groupId: sprintId,
    groupType: "sprint",
  });
  await apiCall(
    "POST",
    `/api/projects/${projectKey}/groups/${sprintId}/actions/start`,
    {},
  );

  await page.goto(`/p/${projectKey}/sprint/${sprintId}`);
  await expect(page.getByText("剩余")).toBeVisible();
  await expect(page.getByText("理想线")).toBeVisible();
});

test("byType 图:双 type 计数入图(YPJ-178/spec 0122)", async ({
  page,
  projectKey,
}) => {
  // 预置 bug+task 各一(type 入参建 item;workload byType 已在后端,本例拦前端渲染)
  await apiCall("POST", `/api/projects/${projectKey}/items`, {
    title: "TY bug item",
    type: "bug",
  });
  await apiCall("POST", `/api/projects/${projectKey}/items`, {
    title: "TY task item",
    type: "task",
  });

  await page.goto(`/p/${projectKey}/flow`);
  const card = page
    .locator(".MuiCard-root")
    .filter({ hasText: "按 Type(数量)" });
  await expect(card).toBeVisible();
  // 数据真入图:卡内 x 轴出现 type 名 + 图 svg(标题断言拦不住 400/空数据,与首例同口径)
  await expect(card.getByText("bug")).toBeVisible();
  await expect(card.getByText("task")).toBeVisible();
  // 卡内有两个 svg(图例标记+图层);取首个=BarChart 已挂载(空态走 Empty,无 svg)
  await expect(card.locator("svg").first()).toBeVisible();
});
