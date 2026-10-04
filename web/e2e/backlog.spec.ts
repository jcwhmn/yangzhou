// Backlog 派生桶:就地建项(零成员关系,天然入池)
import { expect } from "./support/fixtures";
import { test } from "./support/fixtures";

test("V13-S4 Backlog 页就地建项", async ({ page, projectKey }) => {
  await page.goto(`/p/${projectKey}/backlog`);
  await page.getByPlaceholder("新建 item").fill("E2E backlog item");
  await page.getByRole("button", { name: "新建 ITEM" }).click();
  await expect(page.getByText("E2E backlog item").first()).toBeVisible();
});
