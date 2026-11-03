// YPJ-120(spec 0089):开始动作自动入当前 sprint——服务端挂载 + 详情页 toast 轻提示
import { expect } from "./support/fixtures";
import { test } from "./support/fixtures";
import {
  apiAssignMe,
  apiCall,
  apiCreateItem,
  apiCreateSprint,
} from "./support/helpers";

test("开始 backlog item 自动挂最早 active sprint 并 toast @smoke", async ({
  page,
  projectKey,
}) => {
  const a = await apiCreateSprint(projectKey, "A");
  await apiCreateSprint(projectKey, "B");
  // POST 建出的 sprint 默认 planned;激活是用户动作(两个都激活,A 最早创建应胜出)
  await apiCall(
    "POST",
    `/api/projects/${projectKey}/groups/${a}/actions/start`,
    {},
  );
  const bId = await apiCall<{ groupId: string; name: string }[]>(
    "GET",
    `/api/projects/${projectKey}/groups?type=sprint`,
  ).then(
    (list: { groupId: string; name: string }[]) =>
      list.find((s) => s.name === "B")!.groupId,
  );
  await apiCall(
    "POST",
    `/api/projects/${projectKey}/groups/${bId}/actions/start`,
    {},
  );

  const itemId = await apiCreateItem(projectKey, "自动入 sprint item");
  await apiAssignMe(itemId);

  await page.goto(`/p/${projectKey}/i/${itemId}`);
  await page.getByRole("combobox", { name: "item 状态" }).click();
  await page
    .getByRole("listbox")
    .getByRole("option", { name: "In Progress" })
    .click();

  await expect(page.getByText(/已自动加入 sprint「A」/)).toBeVisible();
});

test("已有 sprint 的 item 开始不覆盖、无 toast", async ({
  page,
  projectKey,
}) => {
  const keep = await apiCreateSprint(projectKey, "keep");
  await apiCall(
    "POST",
    `/api/projects/${projectKey}/groups/${keep}/actions/start`,
    {},
  );
  const itemId = await apiCreateItem(projectKey, "已在 sprint 的 item");
  await apiCall("PUT", `/api/items/${itemId}/group-membership`, {
    groupId: keep,
    groupType: "sprint",
  });
  await apiAssignMe(itemId);

  await page.goto(`/p/${projectKey}/i/${itemId}`);
  await page.getByRole("combobox", { name: "item 状态" }).click();
  await page
    .getByRole("listbox")
    .getByRole("option", { name: "In Progress" })
    .click();

  await expect(page.getByRole("combobox", { name: "item 状态" })).toHaveValue(
    "In Progress",
  );
  await expect(page.getByText(/已自动加入 sprint/)).toHaveCount(0);
});
