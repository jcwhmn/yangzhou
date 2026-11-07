// spec 0112(YPJ-112):侧栏两级树——active 父层默认展开、其余折叠(+前缀)、空父层无前缀、新建叶层组默认落当前 epic
// 后端联动语义见 0111(父层组状态由子组动作推导)
import { expect, test } from "./support/fixtures";
import { apiCall, apiCreateSprint } from "./support/helpers";

/** 建 epic 类型(第二个类型自底向上拿 level-2);409=已有,容忍 */
async function ensureEpicType(key: string): Promise<void> {
  await apiCall("POST", `/api/projects/${key}/group-types`, {
    typeKey: "epic",
    name: "Epic",
  }).catch(() => {}); // 409=已有
}

/** 建叶层组并可挂父层组(parentGroupId) */
async function createLeaf(
  key: string,
  name: string,
  parentGroupId?: string,
): Promise<string> {
  const r = await apiCall<{ groupId: string }>(
    "POST",
    `/api/projects/${key}/groups`,
    { typeKey: "sprint", name, parentGroupId: parentGroupId ?? null },
  );
  return r.groupId;
}

test("侧栏两级树:active 展开/其余折叠/空父层无前缀/开合互不影响", async ({
  page,
  projectKey,
}) => {
  await ensureEpicType(projectKey);
  const r = await apiCall<{ groupId: string }>(
    "POST",
    `/api/projects/${projectKey}/groups`,
    { typeKey: "epic", name: "树E1" },
  );
  const e1 = r.groupId;
  await apiCall("POST", `/api/projects/${projectKey}/groups`, {
    typeKey: "epic",
    name: "树E2空",
  });
  const e3 = (
    await apiCall<{ groupId: string }>(
      "POST",
      `/api/projects/${projectKey}/groups`,
      { typeKey: "epic", name: "树E3" },
    )
  ).groupId;
  const s1 = await createLeaf(projectKey, "树S1", e1);
  await createLeaf(projectKey, "树S3", e3);
  // S1 start → E1 联动 active(0111)
  await apiCall(
    "POST",
    `/api/projects/${projectKey}/groups/${s1}/actions/start`,
    {},
  );
  await page.goto(`/p/${projectKey}/overview`);

  // E1 active → 展开(−),子组在列;E3 planned 有子 → 折叠(+);E2 空父层 → 无前缀
  await expect(page.getByText("− 树E1")).toBeVisible();
  await expect(page.getByRole("link", { name: "树S1" }).first()).toBeVisible();
  await expect(page.getByText("+ 树E3")).toBeVisible();
  await expect(page.getByText("树E2空", { exact: true })).toBeVisible();
  await expect(page.getByText("+ 树E2空")).toHaveCount(0);
  await expect(page.getByText("− 树E2空")).toHaveCount(0);

  // 点 E3 开合,互不影响 E1
  await page.getByText("+ 树E3").click();
  await expect(page.getByText("− 树E3")).toBeVisible();
  await expect(page.getByRole("link", { name: "树S3" }).first()).toBeVisible();
  await page.getByText("− 树E3").click();
  await expect(page.getByText("+ 树E3")).toBeVisible();
  await expect(page.getByText("− 树E1")).toBeVisible();
});

test("新建叶层组默认落当前 epic(最后 active 关闭后仍记忆)", async ({
  page,
  projectKey,
}) => {
  await ensureEpicType(projectKey);
  const e1 = (
    await apiCall<{ groupId: string }>(
      "POST",
      `/api/projects/${projectKey}/groups`,
      { typeKey: "epic", name: "默认E1" },
    )
  ).groupId;
  const s1 = await createLeaf(projectKey, "默认S1", e1);
  // 先让 E1 当过 active(写 localStorage),再关闭唯一 active 子组 → E1 回 planned
  await apiCall(
    "POST",
    `/api/projects/${projectKey}/groups/${s1}/actions/start`,
    {},
  );
  await page.goto(`/p/${projectKey}/overview`);
  await expect(page.getByText("− 默认E1")).toBeVisible();
  await apiCall(
    "POST",
    `/api/projects/${projectKey}/groups/${s1}/actions/complete`,
    {},
  );
  await page.goto(`/p/${projectKey}/overview`);
  await expect(page.getByText("+ 默认E1")).toBeVisible();

  // 新建:父层选择器默认 默认E1(localStorage 记忆),创建后 S2 落 E1 下
  await page.getByRole("button", { name: "新建 Sprint" }).click();
  // MUI Select 显示文本 = 选中项名称(value 是 groupId)
  await expect(page.getByRole("combobox", { name: "所属Epic" })).toContainText(
    "默认E1",
  );
  await page.getByLabel("名称").fill("默认S2");
  await page.getByRole("button", { name: "创建", exact: true }).click();
  await expect(page.getByRole("link", { name: "默认S2" }).first()).toHaveCount(
    0,
  ); // E1 已收起,行隐藏
  await page.getByText("+ 默认E1").click();
  await expect(
    page.getByRole("link", { name: "默认S2" }).first(),
  ).toBeVisible();
});
