// YPJ-2 驾驶舱:首页跨项目聚合(今日名下/阻塞/昨日完成 + 活跃 sprint/进行中 milestone)
import { expect, test } from "./support/fixtures";
import { apiAssignMe, apiCall, apiCreateItem } from "./support/helpers";

test("驾驶舱聚合——我的 items、活跃 sprint、进行中 milestone", async ({ page, projectKey }) => {
  const itemId = await apiCreateItem(projectKey, "驾驶舱聚合任务");
  await apiAssignMe(itemId);
  const sprint = await apiCall<{ sprintId: string }>("POST", `/api/projects/${projectKey}/sprints`, {
    name: `驾驶舱冲刺 ${projectKey}`,
    startDate: null,
    endDate: null,
  });
  await apiCall("PATCH", `/api/projects/${projectKey}/sprints/${sprint.sprintId}`, { status: "active" });
  const ms = await apiCall<{ milestoneId: string }>("POST", `/api/projects/${projectKey}/milestones`, {
    name: `驾驶舱里程碑 ${projectKey}`,
  });
  await apiCall("PATCH", `/api/projects/${projectKey}/milestones/${ms.milestoneId}`, { status: "in_progress" });

  await page.goto("/");
  await expect(page.getByText("今日名下")).toBeVisible();
  await expect(page.getByText(`${projectKey}-1驾驶舱聚合任务`)).toBeVisible();
  await expect(page.getByText(`驾驶舱冲刺 ${projectKey}`)).toBeVisible();
  await expect(page.getByText(`驾驶舱里程碑 ${projectKey}`)).toBeVisible();
});
