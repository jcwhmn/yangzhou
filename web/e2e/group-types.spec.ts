// spec 0115 §四(YPJ-164 修订):分组类型管理 = 图标行入口弹面板(无侧栏项/无路由);
// W-RO = 出边 4 槽展示行(与编辑态同构,零点击可见);W-EDIT = 点笔同槽位变控件
import { expect, test } from "./support/fixtures";
import { apiCall } from "./support/helpers";

/** 打开分组类型管理面板(图标行分类图标) */
async function openPanel(page: import("@playwright/test").Page) {
  await page.getByRole("button", { name: "分组类型管理" }).click();
  const panel = page.getByRole("dialog");
  await expect(panel).toBeVisible();
  return panel;
}

test("GroupTypesPanel——打开面板零点击见出边 4 槽展示行(W-RO,同编辑态骨架)", async ({
  page,
  projectKey,
}) => {
  // 自定义类型 phase:后端种默认三态循环(switch 单出边 planned→active→completed→planned)
  await apiCall("POST", `/api/projects/${projectKey}/group-types`, {
    typeKey: "phase",
    name: "阶段",
  });

  await page.goto(`/p/${projectKey}`);
  const panel = await openPanel(page);
  await expect(panel.getByText("阶段", { exact: true })).toBeVisible();

  // W-RO:每条出边四槽齐全(fn/target/label/icon,缺省按字典回退),无需任何点击;
  // 三态循环三条出边 fn 同为 switch,label/icon 同为字典回退值 → 用 first()
  const phase = panel.locator("li").filter({ hasText: "阶段" });
  await expect(
    phase.getByText("switch", { exact: true }).first(),
  ).toBeVisible();
  await expect(phase.getByText("active", { exact: true })).toBeVisible();
  await expect(phase.getByText("切换", { exact: true }).first()).toBeVisible();
  await expect(phase.getByText("↔", { exact: true }).first()).toBeVisible();

  // 内建 sprint(timed 模板):active 双出边如实全展示(开始/完成/后退)
  const sprint = panel.locator("li").filter({ hasText: "sprint" });
  await expect(sprint.getByText("start", { exact: true })).toBeVisible();
  await expect(sprint.getByText("开始", { exact: true })).toBeVisible();
  await expect(sprint.getByText("backward", { exact: true })).toBeVisible();
  await expect(sprint.getByText("后退", { exact: true })).toBeVisible();

  // 展示态不可编辑:面板内无任何控件(下拉/输入框)
  await expect(panel.getByRole("combobox")).toHaveCount(0);
  await expect(panel.getByPlaceholder("显示名(可选)")).toHaveCount(0);

  // release 类型不进本面板(非用户管理的分组类型)
  await expect(panel.getByText("release", { exact: true })).toHaveCount(0);
});

test("level 动线——面板建 timed level-1 类型 → 关面板刷新侧栏 → 建 Sprint 组", async ({
  page,
  projectKey,
}) => {
  // 删 fixture 补种的 sprint 类型,回到「无 level-1」起点
  await apiCall("DELETE", `/api/projects/${projectKey}/group-types/sprint`);

  await page.goto(`/p/${projectKey}`);
  await expect(page.getByRole("button", { name: "新建 Sprint" })).toHaveCount(
    0,
  );
  await expect(page.getByText("Backlog")).toBeVisible();

  // 图标行入口开面板,UI 建 timed 类型:时间盒勾选 → 时间盒模板 + level-1
  const panel = await openPanel(page);
  await panel.getByLabel("typeKey(小写字母开头)").fill("iteration");
  await panel.getByLabel("名称", { exact: true }).fill("Iteration");
  await panel.getByText("时间盒", { exact: true }).click();
  await panel.getByRole("button", { name: "新增" }).click();
  const iteration = panel.locator("li").filter({ hasText: "Iteration" });
  await expect(iteration).toBeVisible();
  await expect(iteration.getByText("L1", { exact: true })).toBeVisible();
  // W-RO 展示行:timed 模板首边 fn=start,label=开始
  await expect(iteration.getByText("start", { exact: true })).toBeVisible();
  await expect(iteration.getByText("开始", { exact: true })).toBeVisible();

  // 关面板(Esc)→ 侧栏 Sprint 段出现(关面板刷新,YPJ-150 语义)
  await page.keyboard.press("Escape");
  await expect(panel).toBeHidden();
  await expect(
    page.getByRole("button", { name: "新建 Iteration" }),
  ).toBeVisible();
  await expect(page.getByText("Iteration", { exact: true })).toBeVisible();

  // 侧栏新建 Sprint:走 level-1 解析(不再按 typeKey=sprint 寻型)
  await page.getByRole("button", { name: "新建 Iteration" }).click();
  const createDialog = page.getByRole("dialog");
  await expect(createDialog).toBeVisible();
  await createDialog.getByLabel("名称").fill(" iteration-1");
  await createDialog.getByRole("button", { name: "创建" }).click();
  await expect(createDialog).toBeHidden();
  await expect(page.getByText("iteration-1")).toBeVisible();
});

test("GroupTypesPanel——点笔进入编辑模式:label 覆写保存并持久(spec 0115)", async ({
  page,
  projectKey,
}) => {
  await apiCall("POST", `/api/projects/${projectKey}/group-types`, {
    typeKey: "phase",
    name: "阶段",
  });

  await page.goto(`/p/${projectKey}`);
  const panel = await openPanel(page);

  // 点「阶段」的笔 → 同面板进入编辑模式(槽位变控件),无二级弹出
  const phase = panel.locator("li").filter({ hasText: "阶段" });
  await phase.getByRole("button", { name: "编辑工作流" }).click();
  await expect(phase.getByRole("button", { name: "保存" })).toBeVisible();

  // 首条出边(planned→active,fn=switch)label 改「启动」;icon 已按字典预填 ↔
  await phase.getByPlaceholder("显示名(可选)").first().fill("启动");
  await phase.getByRole("button", { name: "保存" }).click();

  // 保存后回 W-RO,展示行立即显示覆写
  await expect(phase.getByText("启动", { exact: true })).toBeVisible();

  // 持久化:刷新重开面板仍在
  await page.reload();
  const panel2 = await openPanel(page);
  const phase2 = panel2.locator("li").filter({ hasText: "阶段" });
  await expect(phase2.getByText("启动", { exact: true })).toBeVisible();
});
