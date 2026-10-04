// 成员池:项目内加成员,按非终态 item 分色
import { expect } from "./support/fixtures";
import { test } from "./support/fixtures";
import { apiAssignMe, apiCreateItem } from "./support/helpers";

test("V11 成员池色点——有非终态 item 🟢,无 ⚪", async ({ page, projectKey }) => {
  // me 名下放一个非终态 item → 🟢
  const itemId = await apiCreateItem(projectKey, "E2E 池 item");
  await apiAssignMe(itemId);
  await page.goto(`/p/${projectKey}`);
  await page.getByRole("button", { name: "成员池" }).click();
  await page.getByText("+ me", { exact: true }).click();
  await page.getByText("+ 小王", { exact: true }).click();
  await expect(page.getByLabel("进行中")).toHaveCount(1);
  await expect(page.getByLabel("空闲")).toHaveCount(1);
});
