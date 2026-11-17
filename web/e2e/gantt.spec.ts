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

test("v2 数据锚定轴 + 连续缩放(YPJ-219)", async ({ page, projectKey }) => {
  const item = await apiCreateItem(projectKey, "E2E 缩放条");
  await apiCall("PATCH", `/api/items/${item}`, {
    startDate: "2020-01-01",
    dueDate: "2020-01-03",
  });

  await page.goto(`/p/${projectKey}/gantt`);
  await expect(page.getByText("E2E 缩放条")).toBeVisible();

  // v2:无语义档位按钮;缩放控件 + 今日 + 日窗预设 + 视点日导航在
  await expect(page.getByTestId("gantt-gran-day")).toHaveCount(0);
  for (const tid of [
    "gantt-zoom-in",
    "gantt-zoom-out",
    "gantt-fit",
    "gantt-today",
    "gantt-win-1",
    "gantt-win-2",
    "gantt-win-3",
    "gantt-win-5",
    "gantt-center-nav",
  ]) {
    await expect(page.getByTestId(tid)).toBeVisible();
  }

  // 自适应 header:小时行 + 日行在(3 天区间撑满一屏 → 3 行封顶)
  await expect(page.getByTestId("gantt-row-hour")).toBeVisible();
  await expect(page.getByTestId("gantt-row-day")).toBeVisible();

  // 默认全区间压一屏:内容撑满可视区
  await expect
    .poll(() =>
      page
        .getByTestId("gantt-scroll")
        .evaluate(
          (el) =>
            (el.firstElementChild as HTMLElement).scrollWidth >=
            el.clientWidth - 280,
        ),
    )
    .toBe(true);

  // 连续缩放:+ 一档 → 内容变宽
  const before = await page
    .getByTestId("gantt-scroll")
    .evaluate((el) => (el.firstElementChild as HTMLElement).scrollWidth);
  await page.getByTestId("gantt-zoom-in").click();
  await expect
    .poll(async () =>
      page
        .getByTestId("gantt-scroll")
        .evaluate((el) => (el.firstElementChild as HTMLElement).scrollWidth),
    )
    .toBeGreaterThan(before);

  // 1 日预设:0–24 墙钟全程小时刻度(首尾在)
  await page.getByTestId("gantt-win-1").click();
  await expect(page.getByText("00:00", { exact: true }).first()).toBeVisible();
  await expect(page.getByText("23:00", { exact: true })).toBeVisible();

  // 工况保持:切页再回来,日窗预设仍在(小时刻度还在)
  await page.goto(`/p/${projectKey}/backlog`);
  await page.goto(`/p/${projectKey}/gantt`);
  await expect(page.getByText("00:00", { exact: true }).first()).toBeVisible();
});

test("视点日导航(YPJ-219 v2)", async ({ page, projectKey }) => {
  const item = await apiCreateItem(projectKey, "E2E 时轴条");
  // 排明天 10:00–12:00;数据锚定轴默认包含它
  const tomorrow = new Date();
  tomorrow.setDate(tomorrow.getDate() + 1);
  const y = tomorrow.getFullYear();
  const md = `${String(tomorrow.getMonth() + 1).padStart(2, "0")}-${String(tomorrow.getDate()).padStart(2, "0")}`;
  await apiCall("PATCH", `/api/items/${item}`, {
    startDate: `${y}-${md}T10:00`,
    dueDate: `${y}-${md}T12:00`,
  });

  await page.goto(`/p/${projectKey}/gantt`);
  await expect(page.getByText("E2E 时轴条")).toBeVisible();

  // 视点日设为明天(fillDate 三段式)
  const group = page.getByRole("group", { name: "视点日", exact: true });
  await group.getByLabel("月", { exact: true }).fill(md.slice(0, 2));
  await group.getByLabel("日", { exact: true }).fill(md.slice(3, 5));
  await group.getByLabel("年", { exact: true }).fill(String(y));
  await group.getByLabel("年", { exact: true }).blur();
  const v = await group.getAttribute("data-value");
  expect(v).toBe(`${y}-${md}`);

  // ◀ 后退一天 → 视点日回到今日
  await page.getByTestId("gantt-center-prev").click();
  const v2 = await group.getAttribute("data-value");
  expect(v2).not.toBe(`${y}-${md}`);
});
