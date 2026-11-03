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
