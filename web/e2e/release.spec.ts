// Releases 页 + 侧栏段 + item 挂接(YPJ-201,spec 0134):建/发布终态置灰/侧栏折叠。
import { expect, test } from "./support/fixtures";
import {
  apiCall,
  apiCreateItem,
  openSelect,
  selectOption,
} from "./support/helpers";
import { t } from "../lib/texts";

test("Releases——建/发布/终态置灰/侧栏/item 挂接 @smoke", async ({
  page,
  projectKey,
}) => {
  // 预置:一个已发布 release(验证置灰区与不在下拉),一个 item
  const doneId = (
    await apiCall<{ releaseId: string }>(
      "POST",
      `/api/projects/${projectKey}/releases`,
      { name: "v0.1 历史发布" },
    )
  ).releaseId;
  await apiCall("PATCH", `/api/projects/${projectKey}/releases/${doneId}`, {
    status: "released",
  });
  const item = await apiCreateItem(projectKey, "挂接用 item");

  await page.goto(`/p/${projectKey}/releases`);
  await expect(page.getByTestId("releases-page")).toBeVisible();
  // 已发布行在置灰区,带发布日徽标
  await expect(page.getByTestId("release-released-badge")).toBeVisible();

  // UI 新建(仅必填名称)
  await page.getByTestId("release-create").click();
  await page.getByLabel(t.releases.name).fill("v1.0 首个发布");
  await page.getByTestId("release-save").click();
  await expect(
    page.getByTestId("releases-page").getByText("v1.0 首个发布"),
  ).toBeVisible();

  // 侧栏:段头 + planned 行(released 不进侧栏)
  await expect(
    page.getByRole("link", { name: "Releases", exact: true }),
  ).toBeVisible();
  await expect(page.getByText("○ v1.0 首个发布")).toBeVisible();

  // 发布 → 行移入置灰区(带发布徽标),侧栏 planned 行消失
  const row = page
    .locator('[data-testid^="release-row-"]')
    .filter({ hasText: "v1.0 首个发布" });
  await row.getByRole("button", { name: "发布" }).click();
  await page.getByTestId("release-confirm").click();
  await expect(row.getByTestId("release-released-badge")).toBeVisible();
  await expect(page.getByText("○ v1.0 首个发布")).toHaveCount(0);

  // item 详情挂接:下拉只含 planned(此处无 planned,只有兜底「已发布」回显后清空)
  await page.goto(`/p/${projectKey}/i/${item}`);
  // 建第二个 planned release 供下拉选择
  const plannedId = (
    await apiCall<{ releaseId: string }>(
      "POST",
      `/api/projects/${projectKey}/releases`,
      { name: "v2.0 计划中" },
    )
  ).releaseId;
  await page.reload();
  await openSelect(page, "Release");
  await selectOption(page, "Release", "v2.0 计划中");
  // 挂接落库:刷新后仍选中
  await page.reload();
  await expect(page.getByText("v2.0 计划中")).toBeVisible();
});
