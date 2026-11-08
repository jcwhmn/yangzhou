// spec 0097 S3:类型管理对话框——workflow 只读展示(状态 · statusType · 出边动作)
import { expect, test } from "./support/fixtures";
import { apiCall } from "./support/helpers";

test("GroupTypesPanel——类型 workflow 只读展示(自定义类型默认三态循环)", async ({
  page,
  projectKey,
}) => {
  // 自定义类型 phase:后端种默认三态循环(switch 单出边 planned→active→completed→planned)
  await apiCall("POST", `/api/projects/${projectKey}/group-types`, {
    typeKey: "phase",
    name: "阶段",
  });

  await page.goto(`/p/${projectKey}`);
  await page.getByRole("button", { name: "分组类型管理" }).click(); // 侧栏头部图标按钮(aria-label = t.groupTypes.title)
  const dialog = page.getByRole("dialog");
  await expect(dialog).toBeVisible();
  await expect(dialog.getByText("阶段")).toBeVisible();

  // 只读 workflow:名称 · statusType · 出边(函数标签 → 目标状态)
  await expect(
    dialog.getByText(/planned · 起点 · 切换 → active/),
  ).toBeVisible();
  await expect(
    dialog.getByText(/active · 进行中 · 切换 → completed/),
  ).toBeVisible();
  await expect(
    dialog.getByText(/completed · 终态 · 切换 → planned/),
  ).toBeVisible();
  // 内建 sprint:active 双出边全量展示(backward 不进 sprint 页按钮,但只读视图如实呈现)
  await expect(
    dialog.getByText(/完成 → completed、后退 → planned/),
  ).toBeVisible();
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

  // UI 建类型:timed 勾选 → 时间盒模板 + level-1
  await page.getByRole("button", { name: "分组类型管理" }).click();
  const dialog = page.getByRole("dialog");
  await expect(dialog).toBeVisible();
  await dialog.getByLabel("typeKey(小写字母开头)").fill("iteration");
  await dialog.getByLabel("名称", { exact: true }).fill("Iteration");
  await dialog.getByText("时间盒", { exact: true }).click();
  await dialog.getByRole("button", { name: "新增" }).click();
  await expect(dialog.getByText("Iteration", { exact: true })).toBeVisible();
  await expect(dialog.getByText("L1", { exact: true })).toBeVisible();
  await expect(dialog.getByText("时间盒", { exact: true })).toHaveCount(2); // 开关 + 徽标
  await expect(
    dialog.getByText(/planned · 起点 · 开始 → active/),
  ).toBeVisible();
  await page.getByRole("button", { name: "关闭" }).click();
  await expect(dialog).toBeHidden(); // MUI 淡出期吞点击,先等关
  // 类型建好 → 侧栏 Sprint 段出现
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
  await expect(page.getByText("暂无Iteration")).toHaveCount(0);
});

test("GroupTypesPanel——workflow 编辑:label/icon 覆写保存并持久(spec 0115)", async ({
  page,
  projectKey,
}) => {
  await apiCall("POST", `/api/projects/${projectKey}/group-types`, {
    typeKey: "phase",
    name: "阶段",
  });

  await page.goto(`/p/${projectKey}`);
  await page.getByRole("button", { name: "分组类型管理" }).click();
  const dialog = page.getByRole("dialog");
  await expect(dialog).toBeVisible();

  // 进入「阶段」类型的编辑态(W-EDIT;头部变保存/取消)
  const li = dialog.locator("li").filter({ hasText: "阶段" });
  await li.getByRole("button", { name: "编辑工作流" }).click();
  await expect(li.getByRole("button", { name: "保存" })).toBeVisible();

  // 首条出边(planned→active)配 label/icon
  await li.getByPlaceholder("显示名(可选)").first().fill("启动");
  await li.getByRole("button", { name: "保存" }).click();

  // 保存后回只读态,立即显示覆写
  await expect(
    dialog.getByText(/planned · 起点 · ↔ 启动 → active/),
  ).toBeVisible();

  // 持久化:刷新重开面板仍在
  await page.reload();
  await page.getByRole("button", { name: "分组类型管理" }).click();
  await expect(page.getByRole("dialog")).toBeVisible();
  await expect(
    page.getByRole("dialog").getByText(/planned · 起点 · ↔ 启动 → active/),
  ).toBeVisible();
});
