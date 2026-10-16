// YPJ-46 Flow 页(spec 0044):七卡容器可见 + 空态/数据态切换;sprint 详情 burndown 上线
import { expect, test } from "./support/fixtures";
import { apiAssignMe, apiCall, apiCreateItem } from "./support/helpers";

test("Flow 页五图一表——数据态卡片与口径 caption", async ({ page, projectKey }) => {
  const statuses = (
    await apiCall<{ statuses: { statusId: string; name: string; position: number }[] }>("GET", `/api/projects/${projectKey}`)
  ).statuses.sort((a, b) => a.position - b.position);
  // 1 wip(指派 + 挪列)+ 1 done(挪到 final 列,造 throughput/散点数据)
  const wip = await apiCreateItem(projectKey, "FL WIP item");
  await apiAssignMe(wip);
  await apiCall("PATCH", `/api/items/${wip}`, { statusItemId: statuses[1].statusId });
  const done = await apiCreateItem(projectKey, "FL Done item");
  await apiAssignMe(done);
  await apiCall("PATCH", `/api/items/${done}`, { statusItemId: statuses[statuses.length - 1].statusId });

  await page.goto(`/p/${projectKey}/flow`);
  await expect(page.getByText("WIP(按负责人、状态堆叠)")).toBeVisible();
  await expect(page.getByText("吞吐(每日完成)")).toBeVisible();
  await expect(page.getByText("Cycle / Lead 散点")).toBeVisible();
  await expect(page.getByText("累积流(CFD)")).toBeVisible();
  await expect(page.getByText("Aging / 瓶颈")).toBeVisible();
  await expect(page.getByText("Velocity(近 8 个 Sprint)")).toBeVisible();
  // 无 active sprint:burnup 卡显示占位
  await expect(page.getByText("无进行中的 Sprint")).toBeVisible();
});

test("Sprint 详情 burndown——active sprint 出现剩余/理想线图例", async ({ page, projectKey }) => {
  // burndown 端点要求起止日期:直建带日期 sprint(helper 建的是无日期)
  const sprintId = (
    await apiCall<{ sprintId: string }>("POST", `/api/projects/${projectKey}/sprints`, {
      name: "FL Sprint",
      startDate: new Date(Date.now() - 7 * 864e5).toISOString().slice(0, 10),
      endDate: new Date(Date.now() + 7 * 864e5).toISOString().slice(0, 10),
    })
  ).sprintId;
  const item = await apiCreateItem(projectKey, "FL Sprint item");
  await apiCall("PUT", `/api/items/${item}/sprint`, { sprintId });
  await apiCall("PATCH", `/api/projects/${projectKey}/sprints/${sprintId}`, { status: "active" });

  await page.goto(`/p/${projectKey}/sprint/${sprintId}`);
  await expect(page.getByText("剩余")).toBeVisible();
  await expect(page.getByText("理想线")).toBeVisible();
});
