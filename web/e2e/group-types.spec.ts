// spec 0115(YPJ-164):分组类型管理页——workflow 恒常展示 + 行内编辑(无弹窗)
import { expect, test } from "./support/fixtures";
import { apiCall } from "./support/helpers";

test("GroupTypesPage——类型 workflow 恒常展示(自定义类型默认三态循环)", async ({
  page,
  projectKey,
}) => {
  // 自定义类型 phase:后端种默认三态循环(switch 单出边 planned→active→completed→planned)
  await apiCall("POST", `/api/projects/${projectKey}/group-types`, {
    typeKey: "phase",
    name: "阶段",
  });

  await page.goto(`/p/${projectKey}/group-types`);
  await expect(page.getByText("阶段")).toBeVisible();

  // 恒常只读 workflow:名称 · statusType · 出边(函数标签 → 目标状态),无需进编辑态
  await expect(page.getByText(/planned · 起点 · 切换 → active/)).toBeVisible();
  await expect(
    page.getByText(/active · 进行中 · 切换 → completed/),
  ).toBeVisible();
  await expect(
    page.getByText(/completed · 终态 · 切换 → planned/),
  ).toBeVisible();
  // 内建 sprint:active 双出边全量展示(backward 不进 sprint 页按钮,但只读视图如实呈现)
  await expect(
    page.getByText(/完成 → completed、后退 → planned/),
  ).toBeVisible();
  // release 类型不进本页(非用户管理的分组类型;断言用小写 typeKey,避开 sidebar Release 段标题)
  await expect(page.getByText("release", { exact: true })).toHaveCount(0);
});

test("level 动线——UI 建 timed level-1 类型 → 建 Sprint 组(数据源=level-1)", async ({
  page,
  projectKey,
}) => {
  // 删 fixture 补种的 sprint 类型,回到「无 level-1」起点
  await apiCall("DELETE", `/api/projects/${projectKey}/group-types/sprint`);

  await page.goto(`/p/${projectKey}`);
  // spec 0101:无 level-1 类型 → 无 Sprint 段(Backlog 入口保留)
  await expect(page.getByRole("button", { name: "新建 Sprint" })).toHaveCount(
    0,
  );
  await expect(page.getByText("Backlog")).toBeVisible();

  // 进分组类型页 UI 建类型:timed 勾选 → 时间盒模板 + level-1
  await page.goto(`/p/${projectKey}/group-types`);
  await page.getByLabel("typeKey(小写字母开头)").fill("iteration");
  await page.getByLabel("名称", { exact: true }).fill("Iteration");
  await page.getByText("时间盒", { exact: true }).click();
  await page.getByRole("button", { name: "新增" }).click();
  await expect(page.getByText("Iteration", { exact: true })).toBeVisible();
  await expect(page.getByText("L1", { exact: true })).toBeVisible();
  await expect(page.getByText("时间盒", { exact: true })).toHaveCount(2); // 开关 + 徽标
  await expect(page.getByText(/planned · 起点 · 开始 → active/)).toBeVisible();

  // 回项目页 → 侧栏 Sprint 段出现(离开类型页自动刷新)
  await page.goto(`/p/${projectKey}`);
  await expect(
    page.getByRole("button", { name: "新建 Iteration" }),
  ).toBeVisible();
  // 段头 = 类型名,不是 "Sprint"
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

test("GroupTypesPage——workflow 编辑:label/icon 覆写保存并持久(spec 0115)", async ({
  page,
  projectKey,
}) => {
  await apiCall("POST", `/api/projects/${projectKey}/group-types`, {
    typeKey: "phase",
    name: "阶段",
  });

  await page.goto(`/p/${projectKey}/group-types`);

  // 进入「阶段」类型的编辑态(行内;头部变保存/取消)
  const li = page.locator("li").filter({ hasText: "阶段" });
  await li.getByRole("button", { name: "编辑工作流" }).click();
  await expect(li.getByRole("button", { name: "保存" })).toBeVisible();

  // 首条出边(planned→active,fn=switch)label 改「启动」;icon 已按字典预填 ↔
  await li.getByPlaceholder("显示名(可选)").first().fill("启动");
  await li.getByRole("button", { name: "保存" }).click();

  // 保存后回只读态,立即显示覆写
  await expect(
    page.getByText(/planned · 起点 · ↔ 启动 → active/),
  ).toBeVisible();

  // 持久化:刷新仍在
  await page.reload();
  await expect(
    page.getByText(/planned · 起点 · ↔ 启动 → active/),
  ).toBeVisible();
});
