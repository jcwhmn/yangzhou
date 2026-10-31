// YPJ-123(spec 0091 §1):表格显示修复——编号自然排序 / 状态列非空 / sprint 列 backlog 标识
import { expect, test } from "./fixtures";
import { apiCall, apiCreateItem } from "./support/helpers";

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

  // 11 条:字符串序会把 10/11 排在 2 前,数字序应为 1..11
  for (let i = 1; i <= 11; i++) await apiCreateItem(projectKey, `TB Item ${i}`);

  await page.goto(`/p/${projectKey}/table`);
  await expect(page.getByRole("row").nth(1)).toBeVisible();

  // 编号自然排序:首列尾数字严格 1..11
  const nums = await page.locator("tbody tr td:first-child").allTextContents();
  const trail = nums.map((s) => Number(s.trim().split("-").pop()));
  expect(trail).toEqual([...Array(11)].map((_, i) => i + 1));

  // 状态列非空(修复前该列恒为空串)
  await expect(
    page.getByRole("cell", { name: todo, exact: true }).first(),
  ).toBeVisible();

  // 未挂 sprint 的行显示弱化 backlog 标识(11 行全显)
  await expect(page.getByText("backlog", { exact: true })).toHaveCount(11);
});
