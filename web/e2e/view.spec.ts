// YPJ-47 视图配置(spec 0045):保存视图 → 切换默认/视图(筛选生效)→ 删除
import { expect, test } from "./support/fixtures";
import { apiAssignMe, apiCall, apiCreateItem } from "./support/helpers";

test("表格视图——筛选保存/切换/删除主链", async ({ page, projectKey }) => {
  const statuses = (
    await apiCall<{
      statuses: { statusId: string; name: string; position: number }[];
    }>("GET", `/api/projects/${projectKey}`)
  ).statuses.sort((a, b) => a.position - b.position);
  // 一条 In Progress(指派我)+ 一条停在 To Do
  const wip = await apiCreateItem(projectKey, "VW 进行中 item");
  await apiAssignMe(wip);
  await apiCall("PATCH", `/api/items/${wip}`, {
    statusItemId: statuses[1].statusId,
  });
  await apiCreateItem(projectKey, "VW 待办 item");

  await page.goto(`/p/${projectKey}/table`);
  // 筛选:状态 = 第二列
  await page.getByRole("combobox", { name: "状态" }).click();
  await page
    .getByRole("option", { name: statuses[1].name, exact: true })
    .click();
  await expect(page.getByText("VW 进行中 item")).toBeVisible();
  await expect(page.getByText("VW 待办 item")).toBeHidden();

  // 保存视图(命名)
  await page.getByRole("button", { name: "保存 ·" }).click();
  await page.getByLabel("视图名").fill("进行中视图");
  await page
    .getByRole("dialog")
    .getByRole("button", { name: "保存", exact: true })
    .click();
  // 保存后自动选中:视图下拉显示该名称(input value,非文本节点)
  await expect(page.getByRole("combobox", { name: "视图" })).toHaveValue(
    "进行中视图",
  );

  // 切回默认视图 → 筛选清空,两条都见
  await page.getByRole("combobox", { name: "视图" }).click();
  await page.getByRole("option", { name: "默认视图" }).click();
  await expect(page.getByText("VW 待办 item")).toBeVisible();

  // 切回视图 → 筛选恢复
  await page.getByRole("combobox", { name: "视图" }).click();
  await page.getByRole("option", { name: "进行中视图" }).click();
  await expect(page.getByText("VW 待办 item")).toBeHidden();

  // 删除视图 → 回默认,数据全见
  await page.getByRole("button", { name: "删除视图" }).click();
  await expect(page.getByText("VW 待办 item")).toBeVisible();
  await expect(page.getByRole("combobox", { name: "视图" })).toHaveValue(
    "默认视图",
  );
});
