// 成员池:项目内加成员,按非终态 item 分色
import { expect } from "./support/fixtures";
import { test } from "./support/fixtures";
import { apiAssignMe, apiCreateItem, apiCreateMember } from "./support/helpers";

test("V11 成员池色点——有非终态 item 🟢,无 ⚪ @smoke", async ({
  page,
  projectKey,
}) => {
  // 小王是虚拟成员,干净库不存在,先 API 预置
  await apiCreateMember("小王");
  // me 名下放一个非终态 item → 🟢
  const itemId = await apiCreateItem(projectKey, "E2E 池 item");
  await apiAssignMe(itemId);
  await page.goto(`/p/${projectKey}`);
  // YPJ-187:成员池入口 = 顶栏 ⚙ → 成员节
  await page.getByRole("button", { name: "项目设置" }).click();
  const hub = page.getByRole("dialog");
  await hub.getByRole("button", { name: "项目成员池" }).click();
  await hub.getByText("+ 小王", { exact: true }).click(); // me 已随建项目自动入池(0047),不再手加
  await expect(page.getByLabel("进行中")).toHaveCount(1);
  await expect(page.getByLabel("空闲")).toHaveCount(1);
});
