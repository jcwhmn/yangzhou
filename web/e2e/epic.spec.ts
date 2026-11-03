// spec 0097 S3(case 2):Epics 侧栏段移除——goal item 不再进导航;原 V14 rollup 色点逻辑随段删除
import { expect, test } from "./support/fixtures";
import { apiCall, apiCreateItem } from "./support/helpers";

test("侧栏无 Epic 段——goal item 不进导航,空态文案也不再出现", async ({
  page,
  projectKey,
}) => {
  const epic = await apiCreateItem(projectKey, "EP Epic 大目标");
  await apiCall("PATCH", `/api/items/${epic}`, { type: "goal" });

  await page.goto(`/p/${projectKey}`);
  await page.getByRole("link", { name: "Overview" }).click(); // 触发完整导航,观察侧边栏

  const nav = page.getByRole("navigation");
  await expect(nav.getByRole("link", { name: /EP Epic 大目标/ })).toHaveCount(
    0,
  );
  await expect(nav.getByText("Epic", { exact: true })).toHaveCount(0);
  await expect(nav.getByText("暂无 Epic")).toHaveCount(0);
});
