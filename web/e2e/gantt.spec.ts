// 甘特 S1(YPJ-218/spec 0139):父汇总条、FINAL 置灰、父时间线禁用与加子迁移拦截
import { expect } from "./support/fixtures";
import { test } from "./support/fixtures";
import { apiAssignMe, apiCall, apiCreateItem } from "./support/helpers";

/** 给 item 挂父并设日期(API;POST 不带日期,子项日期走 PATCH)。 */
async function makeChild(
  key: string,
  title: string,
  parentId: string,
  start?: string,
  due?: string,
): Promise<string> {
  const { itemId } = await apiCall<{ itemId: string }>(
    "POST",
    `/api/projects/${key}/items`,
    { title, parentItemId: parentId },
  );
  if (start || due) {
    await apiCall("PATCH", `/api/items/${itemId}`, {
      startDate: start ?? "",
      dueDate: due ?? "",
    });
  }
  return itemId;
}

test("甘特父汇总条 + FINAL 置灰 + 条宽含当天", async ({ page, projectKey }) => {
  const statuses = (
    await apiCall<{ statuses: { statusId: string; name: string }[] }>(
      "GET",
      `/api/projects/${projectKey}`,
    )
  ).statuses;
  const finalId = statuses[statuses.length - 1].statusId;

  const parent = await apiCreateItem(projectKey, "E2E 汇总父");
  const c1 = await makeChild(
    projectKey,
    "E2E 子一",
    parent,
    "2020-01-01",
    "2020-01-03",
  );
  await makeChild(projectKey, "E2E 子二", parent, "2020-01-20", "2020-01-25");
  // FINAL 行置灰:子二走到终态(终态迁移需有主)
  await apiAssignMe(c1);
  await apiCall("PATCH", `/api/items/${c1}`, { statusItemId: finalId });

  await page.goto(`/p/${projectKey}/gantt`);
  await expect(page.getByText("E2E 汇总父")).toBeVisible();

  const barArea = (n: number) =>
    page.locator(`a[aria-label="${projectKey}-${n}"]`).locator("xpath=..");

  // 行号:父=1,子一=2,子二=3
  // 父行:汇总条在(11-01~11-25 并集);父无自有条
  await expect(barArea(1).locator("[data-summary]")).toBeVisible();
  // FINAL 子行:置灰条标记在
  await expect(barArea(2).locator("[data-final]")).toBeVisible();
  // 非 FINAL 子行:无置灰标记
  await expect(barArea(3).locator("[data-final]")).toHaveCount(0);
  // B3 条宽含当天:条几何宽度非零(sx 宽度走 emotion class,inline style 拿不到;当天覆盖由手测用例⑨覆盖)
  const w = await barArea(2)
    .locator("[data-final]")
    .evaluate((el) => el.getBoundingClientRect().width);
  expect(w).toBeGreaterThan(5);
});

test("父时间线禁用 + 加子迁移拦截(默认迁移)", async ({ page, projectKey }) => {
  const parent = await apiCreateItem(projectKey, "E2E 时间线父");
  await apiCall("PATCH", `/api/items/${parent}`, {
    startDate: "2020-02-01",
    dueDate: "2020-02-10",
  });

  // 无子时时间线可编辑(此处只验父路径主链,不逐字段)
  await page.goto(`/p/${projectKey}/i/${parent}`);
  await expect(page.getByRole("group", { name: "开始日期" })).toHaveAttribute(
    "data-value",
    "2020-02-01",
  );

  // 加子 → 弹策略选择 → 默认迁移:建「·原排期」子承接,父清空,新子建成
  await page.getByPlaceholder("加一个子条目…").fill("E2E 新子");
  await page.getByRole("button", { name: "添加", exact: true }).click();
  const dialog = page.getByRole("dialog");
  await expect(dialog).toBeVisible();
  await dialog.getByTestId("sub-policy-migrate").click();
  await expect(dialog).toBeHidden();

  // 父时间线清空 → 有子 → 四框禁用
  await expect(page.getByRole("group", { name: "开始日期" })).toHaveAttribute(
    "data-value",
    "",
  );
  await expect(page.getByRole("group", { name: "开始日期" })).toHaveAttribute(
    "aria-disabled",
    "true",
  );
  await expect(page.getByText("已有子项,时间线由子项汇总")).toBeVisible();
  // 迁移子承接原时间线
  await expect(page.getByText("E2E 时间线父·原排期")).toBeVisible();
  await expect(page.getByText("E2E 新子")).toBeVisible();
});

test("无子的父加子不弹窗", async ({ page, projectKey }) => {
  const parent = await apiCreateItem(projectKey, "E2E 无时间线父");
  await page.goto(`/p/${projectKey}/i/${parent}`);
  await page.getByPlaceholder("加一个子条目…").fill("E2E 直接子");
  await page.getByRole("button", { name: "添加", exact: true }).click();
  await expect(page.getByText("E2E 直接子")).toBeVisible();
  await expect(page.getByRole("dialog")).toHaveCount(0);
  // 定案:加了子即禁用(哪怕父从没填过时间线)
  await expect(page.getByRole("group", { name: "开始日期" })).toHaveAttribute(
    "aria-disabled",
    "true",
  );
});
