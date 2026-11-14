// YPJ-177(spec 0123):item type 项目级管理——面板种子四种/增/改名/删守卫;item 表单动态下拉(定义名显示)
import { expect, test } from "./support/fixtures";
import { apiCall } from "./support/helpers";

/** 打开 item 类型管理面板(YPJ-187:顶栏 ⚙ → Item 类型节) */
async function openPanel(page: import("@playwright/test").Page) {
  await page.getByRole("button", { name: "项目设置" }).click();
  const hub = page.getByRole("dialog");
  await expect(hub).toBeVisible();
  await hub.getByRole("button", { name: "Item 类型" }).click();
  const panel = hub;
  return panel;
}

test("种子四种占位 + 面板增/改名(修订:无 builtin 概念,皆可删改)", async ({
  page,
  projectKey,
}) => {
  await page.goto(`/p/${projectKey}`);
  const panel = await openPanel(page);

  // 建项目即四种初始化占位(spec 0123 修订:无 builtin 特权,改名/删除钮对种子可用)
  for (const name of ["Task", "Bug", "Story", "Goal"]) {
    const row = panel.getByTestId("item-type-row").filter({ hasText: name });
    await expect(row).toBeVisible();
    await expect(
      row.getByRole("button", { name: "删除" }).first(),
    ).toBeEnabled();
  }

  // 新增自定义 + 改名(两段确认删除不放本例——在用守卫单独例);行定位用 typeKey(两种态下都是稳定文本)
  await panel.getByLabel("key(slug)").fill("feat");
  await panel.getByLabel("显示名").fill("Feature");
  await panel.getByRole("button", { name: "新增" }).click();
  const feat = panel.getByTestId("item-type-row").filter({ hasText: "feat" });
  await expect(feat.getByText("Feature")).toBeVisible();

  await feat.getByRole("button", { name: "改名 feat" }).click();
  await feat.getByRole("textbox").fill("特性");
  await feat.getByRole("button", { name: "保存 feat" }).click();
  await expect(feat.getByText("特性")).toBeVisible();
});

test("item 表单动态下拉 + 在用删除守卫 409", async ({ page, projectKey }) => {
  await apiCall("POST", `/api/projects/${projectKey}/item-types`, {
    typeKey: "feat",
    name: "Feature",
  });
  const item = await apiCall<{ itemId: string }>(
    "POST",
    `/api/projects/${projectKey}/items`,
    { title: "类型件", type: "feat" },
  );

  // 编辑表单:下拉显示定义名 Feature(value=feat)——Dropdown 触发器是 readOnly TextField,值断言用 toHaveValue
  await page.goto(`/p/${projectKey}/i/${item.itemId}`);
  await expect(page.getByRole("combobox", { name: "类型" })).toHaveValue(
    "Feature",
  );

  // 面板删在用类型 → 409 错误条带计数(两段确认)
  const panel = await openPanel(page);
  const feat = panel.getByTestId("item-type-row").filter({ hasText: "feat" });
  await feat.getByRole("button", { name: "删除 feat" }).click();
  await feat.getByRole("button", { name: "删除 feat" }).click();
  await expect(panel.getByText(/1 个 item/)).toBeVisible();
});
