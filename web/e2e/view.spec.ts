// YPJ-47 视图配置(spec 0045):保存视图 → 切换默认/视图(筛选生效)→ 删除
import { expect, test } from "./support/fixtures";
import { apiAssignMe, apiCall, apiCreateItem } from "./support/helpers";

test("表格视图——筛选保存/切换/删除主链", async ({ page, projectKey }) => {
  const statuses = (
    await apiCall<{
      statuses: { statusId: string; name: string; position: number }[];
    }>("GET", `/api/projects/${projectKey}`)
  ).statuses.sort((a, b) => a.position - b.position);
  // 一条 In Progress(指派我)+ 一条停在 To Do
  const wip = await apiCreateItem(projectKey, "VW 进行中 item");
  await apiAssignMe(wip);
  await apiCall("PATCH", `/api/items/${wip}`, {
    statusItemId: statuses[1].statusId,
  });
  await apiCreateItem(projectKey, "VW 待办 item");

  await page.goto(`/p/${projectKey}/table`);
  // 筛选:状态 = 第二列
  await page.getByRole("combobox", { name: "状态" }).click();
  await page
    .getByRole("option", { name: statuses[1].name, exact: true })
    .click();
  await expect(page.getByRole("listbox")).toBeHidden(); // 菜单退场动画会吞下层点击
  await expect(page.getByText("VW 进行中 item")).toBeVisible();
  await expect(page.getByText("VW 待办 item")).toBeHidden();

  // YPJ-127:排标题升 + 搜索词,一并入视图
  await page
    .getByRole("columnheader", { name: /标题/ })
    .getByRole("button")
    .click();
  await page.getByPlaceholder("搜索编号、标题、负责人…").fill("进行中");

  // 保存视图(命名)
  await page.getByRole("button", { name: "保存 ·" }).click();
  await page.getByLabel("视图名").fill("进行中视图");
  await page
    .getByRole("dialog")
    .getByRole("button", { name: "保存", exact: true })
    .click();
  // 保存后自动选中:视图下拉显示该名称(input value,非文本节点)
  await expect(page.getByRole("combobox", { name: "视图" })).toHaveValue(
    "进行中视图",
  );

  // 重进(刷新)记住并自动应用上次视图(YPJ-131 手测反馈)
  await page.reload();
  await expect(page.getByRole("combobox", { name: "视图" })).toHaveValue(
    "进行中视图",
  );
  await expect(page.getByPlaceholder("搜索编号、标题、负责人…")).toHaveValue(
    "进行中",
  );

  // 切回默认视图 → 筛选清空;搜索/排序是默认视图自己的持久态(YPJ-131 用户拍板)
  await page.getByRole("combobox", { name: "视图" }).click();
  await page.getByRole("option", { name: "默认视图" }).click();
  await expect(page.getByRole("listbox")).toBeHidden();
  await expect(page.getByPlaceholder("搜索编号、标题、负责人…")).toHaveValue(
    "进行中",
  );
  await expect(page.getByText("VW 待办 item")).toBeHidden();

  // 清搜索 → 两条都见
  await page.getByPlaceholder("搜索编号、标题、负责人…").fill("");
  await expect(page.getByText("VW 待办 item")).toBeVisible();

  // 切换到视图 → 筛选恢复
  await page.getByRole("combobox", { name: "视图" }).click();
  await page.getByRole("option", { name: "进行中视图" }).click();
  await expect(page.getByText("VW 待办 item")).toBeHidden();

  // YPJ-127:排序/搜索词随视图恢复
  await expect(page.getByPlaceholder("搜索编号、标题、负责人…")).toHaveValue(
    "进行中",
  );
  await expect(
    page.getByRole("columnheader", { name: /标题/ }),
  ).toHaveAttribute("aria-sort", "ascending");

  // 另存为同名:预检禁用保存按钮(YPJ-131 手测反馈:删除后重建遇 409 且错误不消失)
  await page.getByRole("button", { name: "另存为" }).click();
  await page.getByLabel("视图名").fill("进行中视图");
  await expect(
    page.getByRole("dialog").getByRole("button", { name: "保存", exact: true }),
  ).toBeDisabled();
  await page.getByRole("dialog").getByRole("button", { name: "取消" }).click();
  await expect(page.getByRole("dialog")).toBeHidden();

  // 删除视图 → 回默认,数据全见
  await page.getByRole("button", { name: "删除视图" }).click();
  await expect(page.getByText("VW 待办 item")).toBeVisible();
  await expect(page.getByRole("combobox", { name: "视图" })).toHaveValue(
    "默认视图",
  );
});

// YPJ-131 列顺序(spec 0091 §6):面板上下移 → 表头实时变;随视图保存/恢复;恢复默认回代码默认序
test("表格视图——列顺序:面板调序、随视图保存/恢复、恢复默认", async ({
  page,
  projectKey,
}) => {
  await apiCreateItem(projectKey, "CO item");
  await page.goto(`/p/${projectKey}/table`);
  await expect(page.getByText("CO item")).toBeVisible(); // 数据到位,表头才渲染

  const headers = () =>
    page.evaluate(() =>
      Array.from(document.querySelectorAll("thead th")).map(
        (th) => th.textContent ?? "",
      ),
    );
  const openPanel = async () => {
    await page.getByRole("button", { name: /列配置/ }).click();
  };
  const closePanel = async () => {
    await page.keyboard.press("Escape");
    await expect(page.locator(".MuiPopover-paper")).toBeHidden();
  };
  const row = (label: string) =>
    page
      .locator("div")
      .filter({ hasText: new RegExp(`^${label}$`) })
      .filter({ has: page.getByRole("button", { name: "下移" }) })
      .last();
  const switchView = async (name: string) => {
    await page.getByRole("combobox", { name: "视图" }).click();
    await page.getByRole("option", { name, exact: true }).click();
    await expect(page.getByRole("listbox")).toBeHidden();
  };

  // 默认顺序:状态 在 负责人 前
  let h = await headers();
  expect(h.indexOf("状态")).toBeLessThan(h.indexOf("负责人"));

  // 面板:状态下移一次(=A)→ 负责人 反超,表头实时变
  await openPanel();
  await row("状态").getByRole("button", { name: "下移" }).click();
  h = await headers();
  expect(h.indexOf("负责人")).toBeLessThan(h.indexOf("状态"));
  await closePanel();

  // 存视图:顺序入库
  await page.getByRole("button", { name: "保存 ·" }).click();
  await page.getByLabel("视图名").fill("列序视图");
  await page
    .getByRole("dialog")
    .getByRole("button", { name: "保存", exact: true })
    .click();
  await expect(page.getByRole("combobox", { name: "视图" })).toHaveValue(
    "列序视图",
  );

  // 切回默认视图,再下移一次(=B:状态 落到 开始 后)→ 改的是默认视图(localStorage)
  await switchView("默认视图");
  await openPanel();
  await row("状态").getByRole("button", { name: "下移" }).click();
  h = await headers();
  expect(h.indexOf("开始")).toBeLessThan(h.indexOf("状态"));
  await closePanel();

  // 刷新:默认视图顺序走 localStorage(B)
  await page.reload();
  await expect(page.getByText("CO item")).toBeVisible();
  h = await headers();
  expect(h.indexOf("开始")).toBeLessThan(h.indexOf("状态"));

  // 切回列序视图 → 存档顺序(A)恢复:负责人<状态<开始
  await switchView("列序视图");
  h = await headers();
  expect(h.indexOf("负责人")).toBeLessThan(h.indexOf("状态"));
  expect(h.indexOf("状态")).toBeLessThan(h.indexOf("开始"));

  // 切默认 → 回 localStorage(B)
  await switchView("默认视图");
  h = await headers();
  expect(h.indexOf("开始")).toBeLessThan(h.indexOf("状态"));

  // 恢复默认 → 可见性+顺序回代码默认(D)
  await openPanel();
  await page.getByRole("button", { name: "恢复默认" }).click();
  h = await headers();
  expect(h.indexOf("状态")).toBeLessThan(h.indexOf("负责人"));
  expect(h.indexOf("状态")).toBeLessThan(h.indexOf("开始"));
});

// YPJ-131 复验:load 失败(token 过期 401 的等价场景)= 死页面
// → 保存/另存/列配置锁定,错误常驻 + 重试;重试成功后一切恢复
test("加载失败锁定操作,重试恢复", async ({ page, projectKey }) => {
  await apiCreateItem(projectKey, "VW 死页面 item");
  await page.route("**/api/projects/*/items*", (r) => r.abort());
  await page.goto(`/p/${projectKey}/table`);
  const save = page.getByRole("button", { name: "保存", exact: true });
  await expect(save).toBeDisabled();
  await expect(page.getByRole("button", { name: "另存为" })).toBeDisabled();
  await expect(page.getByRole("button", { name: /列配置/ })).toBeDisabled();
  await expect(page.getByRole("button", { name: "重试" })).toBeVisible();

  await page.unroute("**/api/projects/*/items*");
  await page.getByRole("button", { name: "重试" }).click();
  await expect(save).toBeEnabled();
  await expect(page.getByText("VW 死页面 item")).toBeVisible();
});
// YPJ-131 复验回归:选中视图后工具栏直存必须走 PATCH 落库
// (旧条件 nameOpen === "save" 永假,直存误走 POST 撞同名 409,顺序/排序从未落库)
test("列顺序——选中视图直存(PATCH)落库,刷新后恢复", async ({
  page,
  projectKey,
}) => {
  await apiCreateItem(projectKey, "CO direct-save item");
  await page.goto(`/p/${projectKey}/table`);
  await expect(page.getByText("CO direct-save item")).toBeVisible(); // 数据到位,表头才渲染

  const headers = () =>
    page.evaluate(() =>
      Array.from(document.querySelectorAll("thead th")).map(
        (th) => th.textContent ?? "",
      ),
    );
  const moveStatusDown = async () => {
    await page.getByRole("button", { name: /列配置/ }).click();
    await page
      .locator("div")
      .filter({ hasText: /^状态$/ })
      .filter({ has: page.getByRole("button", { name: "下移" }) })
      .last()
      .getByRole("button", { name: "下移" })
      .click();
    await page.keyboard.press("Escape");
    await expect(page.locator(".MuiPopover-paper")).toBeHidden();
  };

  // 首存为视图(移列置 dirty → 命名对话框 = POST)
  await moveStatusDown();
  await page.getByRole("button", { name: "保存 ·" }).click();
  await page.getByLabel("视图名").fill("列序直存");
  await page
    .getByRole("dialog")
    .getByRole("button", { name: "保存", exact: true })
    .click();
  await expect(page.getByRole("combobox", { name: "视图" })).toHaveValue(
    "列序直存",
  );

  // 视图内再移一次列 → 工具栏直存(不弹框;旧 bug 此处误走 POST 撞同名 409)
  await moveStatusDown();
  await page.getByRole("button", { name: "保存 ·" }).click();
  // 直存成功 = dirty 清除(按钮「 ·」消失),全程不弹命名框
  await expect(
    page.getByRole("button", { name: "保存", exact: true }),
  ).toBeVisible();
  await expect(page.getByRole("dialog")).toBeHidden();

  // 刷新 → 记忆视图自动恢复,顺序从库恢复(状态 已在 负责人 之后)
  await page.reload();
  await expect(page.getByText("CO direct-save item")).toBeVisible();
  await expect(page.getByRole("combobox", { name: "视图" })).toHaveValue(
    "列序直存",
  );
  const h = await headers();
  expect(h.indexOf("负责人")).toBeLessThan(h.indexOf("状态"));
});
