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

// ---------- spec 0113(YPJ-160):组改父 ----------

test("组改父:详情页改挂另一 epic,侧栏树更新且旧 epic 联动回规划中", async ({
  page,
  projectKey,
}) => {
  await ensureEpicType(projectKey);
  const e1 = (
    await apiCall<{ groupId: string }>(
      "POST",
      `/api/projects/${projectKey}/groups`,
      { typeKey: "epic", name: "移E1" },
    )
  ).groupId;
  const e2 = (
    await apiCall<{ groupId: string }>(
      "POST",
      `/api/projects/${projectKey}/groups`,
      { typeKey: "epic", name: "移E2" },
    )
  ).groupId;
  const s1 = await createLeaf(projectKey, "移S1", e1);
  await createLeaf(projectKey, "移S2", e1);
  // S1 start → E1 active(0111);E2 保持 planned
  await apiCall(
    "POST",
    `/api/projects/${projectKey}/groups/${s1}/actions/start`,
    {},
  );
  await page.goto(`/p/${projectKey}/sprint/${s1}`);
  const sel = page.getByRole("combobox", { name: "所属Epic" });
  await expect(sel).toContainText("移E1");
  await sel.click();
  await page.getByRole("option", { name: "移E2" }).click();
  // 改挂后:E2 收 active 子 → active 展开;E1 剩 planned 子 → 状态回规划中(0111 规则经改父联动;展开态残留是 0112 设计——只加不移除)
  await expect(page.getByText("− 移E2")).toBeVisible();
  await expect(page.getByRole("link", { name: "移S1" }).first()).toBeVisible();
  await expect(page.getByRole("button", { name: /− 移E1/ })).toContainText(
    "规划中",
  );
});

test("组改父:悬空组归挂(平面区消失、进 epic 树;占位不可选回)", async ({
  page,
  projectKey,
}) => {
  // 造存量悬空 grandfather:项目尚无 epic 类型时建无父叶层组(合法),后配 epic 类型
  const s1 = await createLeaf(projectKey, "悬S1");
  await apiCall(
    "POST",
    `/api/projects/${projectKey}/groups/${s1}/actions/start`,
    {},
  );
  await ensureEpicType(projectKey);
  await apiCall("POST", `/api/projects/${projectKey}/groups`, {
    typeKey: "epic",
    name: "悬E1",
  });
  await page.goto(`/p/${projectKey}/sprint/${s1}`);
  const sel = page.getByRole("combobox", { name: "所属Epic" });
  await expect(sel).toContainText("未分组（悬空）");
  await sel.click();
  await page.getByRole("option", { name: "悬E1" }).click();
  // E1 收 active 子 → active 展开,S1 归位;选择器回显 E1(占位项 disabled 不可选回)
  await expect(page.getByText("− 悬E1")).toBeVisible();
  await expect(page.getByRole("link", { name: "悬S1" }).first()).toBeVisible();
  await expect(sel).toContainText("悬E1");
});
