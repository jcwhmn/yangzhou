// YPJ-123(spec 0091 §1):表格显示修复——编号自然排序 / 状态列非空 / sprint 列 backlog 标识
import { expect, test } from "./support/fixtures";
import { apiCall, apiCreateItem } from "./support/helpers";

/** 建后端并发死锁(40P01→500,存量缺陷)瞬态失败,重试兜底。shortcut: 治本待后端死锁排查票 */
async function createItemResilient(projectKey: string, title: string) {
  for (let i = 0; ; i++) {
    try {
      return await apiCreateItem(projectKey, title);
    } catch (e) {
      if (i >= 2 || !String(e).includes("500")) throw e;
      await new Promise((r) => setTimeout(r, 300));
    }
  }
}

test("表格显示:编号数字序、状态列非空、sprint 列 backlog 标识 @smoke", async ({
  page,
  projectKey,
}) => {
  const statuses = (
    await apiCall<{ statuses: { name: string }[] }>(
      "GET",
      `/api/projects/${projectKey}`,
    )
  ).statuses;
  const todo = statuses[0].name; // 默认 workflow 首态

  // 建 10 条(number=1..10):数字序 KEY-10 在末位;字符串序会把 KEY-10 排到第 2 位(修复前此断言红)
  for (let i = 1; i <= 10; i++)
    await createItemResilient(projectKey, `TB Item ${i}`);

  await page.goto(`/p/${projectKey}/table`);
  await expect(page.getByRole("row").nth(1)).toBeVisible();

  // 编号自然排序:首列尾数字严格 1..10
  const nums = await page.locator("tbody tr td:first-child").allTextContents();
  const trail = nums.map((s) => Number(s.trim().split("-").pop()));
  expect(trail).toEqual([...Array(10)].map((_, i) => i + 1));

  // 状态列非空(修复前该列恒为空串)
  await expect(
    page.getByRole("cell", { name: todo, exact: true }).first(),
  ).toBeVisible();

  // 未挂 sprint 的行显示弱化 backlog 标识(10 行全显)
  await expect(page.getByText("backlog", { exact: true })).toHaveCount(10);
});

test("列头排序:单击追加多列(循环 升/降/移除),Shift 仅此列", async ({
  page,
  projectKey,
}) => {
  // 建序 C/A/D/B(number 1..4):标题升序时行序应为 number [2,4,1,3]
  for (const t of ["TB C", "TB A", "TB D", "TB B"])
    await createItemResilient(projectKey, t);

  await page.goto(`/p/${projectKey}/table`);
  await expect(page.getByRole("row").nth(1)).toBeVisible();

  const trailOf = async () => {
    const nums = await page
      .locator("tbody tr td:first-child")
      .allTextContents();
    return nums.map((s) => Number(s.trim().split("-").pop()));
  };

  // Shift+标题:仅按此列升序(替换)
  await page
    .getByRole("columnheader", { name: /标题/ })
    .click({ modifiers: ["Shift"] });
  await expect.poll(trailOf).toEqual([2, 4, 1, 3]);

  // 单击截止:追加次级键(全 null 恒最后,不破坏标题序);列头显示多栏序号 1/2
  await page.getByRole("columnheader", { name: /截止/ }).click();
  await expect.poll(trailOf).toEqual([2, 4, 1, 3]);
  await expect(page.getByRole("columnheader", { name: /标题/ })).toContainText(
    "1",
  );
  await expect(page.getByRole("columnheader", { name: /截止/ })).toContainText(
    "2",
  );

  // 再点截止:降序(标题仍居首)
  await page.getByRole("columnheader", { name: /截止/ }).click();
  await expect.poll(trailOf).toEqual([2, 4, 1, 3]);

  // 三点截止:移除 → 仅剩标题升序
  await page.getByRole("columnheader", { name: /截止/ }).click();
  await expect.poll(trailOf).toEqual([2, 4, 1, 3]);

  // 点标题:翻降序
  await page.getByRole("columnheader", { name: /标题/ }).click();
  await expect.poll(trailOf).toEqual([3, 1, 4, 2]);

  // 再点标题:移除 → 回默认编号序
  await page.getByRole("columnheader", { name: /标题/ }).click();
  await expect.poll(trailOf).toEqual([1, 2, 3, 4]);
});

test("分页页 20 两页,翻页/改页大小生效", async ({ page, projectKey }) => {
  // 默认页大小 20:21 条 → 两页(20+1)
  for (let i = 1; i <= 21; i++)
    await createItemResilient(projectKey, `TB ${i}`);

  await page.goto(`/p/${projectKey}/table`);
  await expect(page.getByRole("row").nth(1)).toBeVisible();
  await expect(page.locator("tbody tr")).toHaveCount(20);
  await expect(page.getByText("1-20 / 21")).toBeVisible();

  await page.getByRole("button", { name: /next page/i }).click();
  await expect(page.locator("tbody tr")).toHaveCount(1);
  await expect(page.getByText("21-21 / 21")).toBeVisible();

  // 改每页 50 → 单页 21 行
  await page.getByRole("combobox", { name: "每页" }).click();
  await page.getByRole("option", { name: "50", exact: true }).click();
  await expect(page.locator("tbody tr")).toHaveCount(21);
});

test("表内搜索:标题/编号不区分大小写子串,清空恢复全量", async ({
  page,
  projectKey,
}) => {
  await createItemResilient(projectKey, "TB ALPHA");
  await createItemResilient(projectKey, "TB beta");
  await createItemResilient(projectKey, "TB gamma");

  await page.goto(`/p/${projectKey}/table`);
  await expect(page.getByRole("row").nth(1)).toBeVisible();

  const trailOf = async () => {
    const nums = await page
      .locator("tbody tr td:first-child")
      .allTextContents();
    return nums.map((s) => Number(s.trim().split("-").pop()));
  };
  const search = page.getByPlaceholder("搜索编号、标题、负责人…");

  // 标题:小写查询命中大写数据
  await search.fill("alpha");
  await expect(page.locator("tbody tr")).toHaveCount(1);
  await expect.poll(trailOf).toEqual([1]);

  // 编号:小写 key 前缀命中大写 KEY-<n>(全部 3 行)
  await search.fill(projectKey.toLowerCase());
  await expect(page.locator("tbody tr")).toHaveCount(3);

  // 编号:"-2" 仅命中 KEY-2(key 无连字符,且总数 <20 无 -2x 干扰)
  await search.fill("-2");
  await expect(page.locator("tbody tr")).toHaveCount(1);
  await expect.poll(trailOf).toEqual([2]);

  // 清空 → 全量恢复
  await search.fill("");
  await expect(page.locator("tbody tr")).toHaveCount(3);
});
