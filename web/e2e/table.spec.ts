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
