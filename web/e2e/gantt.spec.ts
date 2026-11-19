// 甘特 S1(YPJ-218/spec 0139):父汇总条、FINAL 置灰、父时间线禁用与加子迁移拦截
import { expect } from "./support/fixtures";
import { test } from "./support/fixtures";
import {
  apiAssignMe,
  apiCall,
  apiCreateItem,
  apiCreateSprint,
} from "./support/helpers";

/** UI 切范围(YPJ-220)。依赖缺省 board 后,旧用例需显式切「全部」复现全量语义。 */
async function pickScope(page: import("@playwright/test").Page, label: string) {
  await page.getByTestId("gantt-scope").click();
  // MUI Menu 淡入期(~200ms)会吞下层点击(仓库已知陷阱),先等动画落定再点
  await page.waitForTimeout(300);
  await page.getByRole("option", { name: label }).click();
  await expect(page.getByRole("listbox")).toBeHidden();
}

/** 给 item 挂父并设日期(API;POST 不带日期,子项日期走 PATCH)。 */
async function makeChild(
  key: string,
  title: string,
  parentId: string,
  start?: string,
  due?: string,
): Promise<string> {
  const { itemId } = await apiCall<{ itemId: string }>(
    "POST",
    `/api/projects/${key}/items`,
    { title, parentItemId: parentId },
  );
  if (start || due) {
    await apiCall("PATCH", `/api/items/${itemId}`, {
      startDate: start ?? "",
      dueDate: due ?? "",
    });
  }
  return itemId;
}

test("甘特父汇总条 + FINAL 置灰 + 条宽含当天", async ({ page, projectKey }) => {
  const statuses = (
    await apiCall<{ statuses: { statusId: string; name: string }[] }>(
      "GET",
      `/api/projects/${projectKey}`,
    )
  ).statuses;
  const finalId = statuses[statuses.length - 1].statusId;

  const parent = await apiCreateItem(projectKey, "E2E 汇总父");
  // 日期用近期动态值(2026-11-19 定):FINAL 子的 actualEnd=now 会把 fit 轴拉到 planned→now,
  // 固定 2020 老日期会把 span 拉爆到 ~2500 天、fit 下所有条压成 3px(spec 0147 轴纳入实际日期的合法结果)
  const d = (offset: number) => {
    const t = new Date();
    t.setDate(t.getDate() + offset);
    return `${t.getFullYear()}-${String(t.getMonth() + 1).padStart(2, "0")}-${String(t.getDate()).padStart(2, "0")}`;
  };
  const c1 = await makeChild(projectKey, "E2E 子一", parent, d(-10), d(-8));
  await makeChild(projectKey, "E2E 子二", parent, d(-4), d(-1));
  // FINAL 行置灰:子二走到终态(终态迁移需有主)
  await apiAssignMe(c1);
  await apiCall("PATCH", `/api/items/${c1}`, { statusItemId: finalId });

  await page.goto(`/p/${projectKey}/gantt`);
  await pickScope(page, "全部");
  await expect(page.getByText("E2E 汇总父")).toBeVisible();

  const barArea = (n: number) =>
    page.locator(`a[aria-label="${projectKey}-${n}"]`).locator("xpath=..");

  // 行号:父=1,子一=2,子二=3
  // 父行:汇总条在(11-01~11-25 并集);父无自有条
  await expect(barArea(1).locator("[data-summary]")).toBeVisible();
  // FINAL 子行:置灰条标记在
  await expect(barArea(2).locator("[data-final]")).toBeVisible();
  // 非 FINAL 子行:无置灰标记
  await expect(barArea(3).locator("[data-final]")).toHaveCount(0);
  // B3 条宽含当天:条几何宽度非零(sx 宽度走 emotion class,inline style 拿不到;当天覆盖由手测用例⑨覆盖)
  const w = await barArea(2)
    .locator("[data-final]")
    .evaluate((el) => el.getBoundingClientRect().width);
  expect(w).toBeGreaterThan(5);
});

test("父时间线禁用 + 加子迁移拦截(默认迁移)", async ({ page, projectKey }) => {
  const parent = await apiCreateItem(projectKey, "E2E 时间线父");
  await apiCall("PATCH", `/api/items/${parent}`, {
    startDate: "2020-02-01",
    dueDate: "2020-02-10",
  });

  // 无子时时间线可编辑(此处只验父路径主链,不逐字段)
  await page.goto(`/p/${projectKey}/i/${parent}`);
  await expect(page.getByRole("group", { name: "开始日期" })).toHaveAttribute(
    "data-value",
    "2020-02-01",
  );

  // 加子 → 弹策略选择 → 默认迁移:建「·原排期」子承接,父清空,新子建成
  await page.getByPlaceholder("加一个子条目…").fill("E2E 新子");
  await page.getByRole("button", { name: "添加", exact: true }).click();
  const dialog = page.getByRole("dialog");
  await expect(dialog).toBeVisible();
  await dialog.getByTestId("sub-policy-migrate").click();
  await expect(dialog).toBeHidden();

  // 父时间线清空 → 有子 → 四框禁用
  await expect(page.getByRole("group", { name: "开始日期" })).toHaveAttribute(
    "data-value",
    "",
  );
  await expect(page.getByRole("group", { name: "开始日期" })).toHaveAttribute(
    "aria-disabled",
    "true",
  );
  await expect(page.getByText("已有子项,时间线由子项汇总")).toBeVisible();
  // 迁移子承接原时间线
  await expect(page.getByText("E2E 时间线父·原排期")).toBeVisible();
  await expect(page.getByText("E2E 新子")).toBeVisible();
});

test("无子的父加子不弹窗", async ({ page, projectKey }) => {
  const parent = await apiCreateItem(projectKey, "E2E 无时间线父");
  await page.goto(`/p/${projectKey}/i/${parent}`);
  await page.getByPlaceholder("加一个子条目…").fill("E2E 直接子");
  await page.getByRole("button", { name: "添加", exact: true }).click();
  await expect(page.getByText("E2E 直接子")).toBeVisible();
  await expect(page.getByRole("dialog")).toHaveCount(0);
  // 定案:加了子即禁用(哪怕父从没填过时间线)
  await expect(page.getByRole("group", { name: "开始日期" })).toHaveAttribute(
    "aria-disabled",
    "true",
  );
});

test("v2 数据锚定轴 + 连续缩放(YPJ-219)", async ({ page, projectKey }) => {
  const item = await apiCreateItem(projectKey, "E2E 缩放条");
  await apiCall("PATCH", `/api/items/${item}`, {
    startDate: "2020-01-01",
    dueDate: "2020-01-03",
  });

  await page.goto(`/p/${projectKey}/gantt`);
  await pickScope(page, "全部");
  await expect(page.getByText("E2E 缩放条")).toBeVisible();

  // v2:无语义档位按钮;缩放控件 + 今日 + 日窗预设 + 视点日导航在
  await expect(page.getByTestId("gantt-gran-day")).toHaveCount(0);
  for (const tid of [
    "gantt-zoom-in",
    "gantt-zoom-out",
    "gantt-fit",
    "gantt-today",
    "gantt-win-1",
    "gantt-win-2",
    "gantt-win-3",
    "gantt-win-5",
    "gantt-center-nav",
  ]) {
    await expect(page.getByTestId(tid)).toBeVisible();
  }

  // 自适应 header:小时行 + 日行在(3 天区间撑满一屏 → 3 行封顶)
  await expect(page.getByTestId("gantt-row-hour")).toBeVisible();
  await expect(page.getByTestId("gantt-row-day")).toBeVisible();

  // 默认全区间压一屏:内容撑满可视区
  await expect
    .poll(() =>
      page
        .getByTestId("gantt-scroll")
        .evaluate(
          (el) =>
            (el.firstElementChild as HTMLElement).scrollWidth >=
            el.clientWidth - 2,
        ),
    )
    .toBe(true);

  // 连续缩放:+ 一档 → 内容变宽
  const before = await page
    .getByTestId("gantt-scroll")
    .evaluate((el) => (el.firstElementChild as HTMLElement).scrollWidth);
  await page.getByTestId("gantt-zoom-in").click();
  await expect
    .poll(async () =>
      page
        .getByTestId("gantt-scroll")
        .evaluate((el) => (el.firstElementChild as HTMLElement).scrollWidth),
    )
    .toBeGreaterThan(before);

  // 1 日预设:0–24 墙钟小时刻度(视口 1000px/天 → 2h 步长,末档 22:00)
  await page.getByTestId("gantt-win-1").click();
  await expect(page.getByText("00:00", { exact: true }).first()).toBeVisible();
  await expect(page.getByText("22:00", { exact: true }).first()).toBeVisible();

  // 工况保持:切页再回来,日窗预设仍在(小时刻度还在)
  await page.goto(`/p/${projectKey}/backlog`);
  await page.goto(`/p/${projectKey}/gantt`);
  await expect(page.getByText("00:00", { exact: true }).first()).toBeVisible();
});

test("视点日导航(YPJ-219 v2)", async ({ page, projectKey }) => {
  const item = await apiCreateItem(projectKey, "E2E 时轴条");
  // 排明天 10:00–12:00;数据锚定轴默认包含它
  const tomorrow = new Date();
  tomorrow.setDate(tomorrow.getDate() + 1);
  const y = tomorrow.getFullYear();
  const md = `${String(tomorrow.getMonth() + 1).padStart(2, "0")}-${String(tomorrow.getDate()).padStart(2, "0")}`;
  await apiCall("PATCH", `/api/items/${item}`, {
    startDate: `${y}-${md}T10:00`,
    dueDate: `${y}-${md}T12:00`,
  });

  await page.goto(`/p/${projectKey}/gantt`);
  await pickScope(page, "全部");
  await expect(page.getByText("E2E 时轴条")).toBeVisible();

  // 视点日设为明天(fillDate 三段式)
  const group = page.getByRole("group", { name: "视点日", exact: true });
  await group.getByLabel("月", { exact: true }).fill(md.slice(0, 2));
  await group.getByLabel("日", { exact: true }).fill(md.slice(3, 5));
  await group.getByLabel("年", { exact: true }).fill(String(y));
  await group.getByLabel("年", { exact: true }).blur();
  const v = await group.getAttribute("data-value");
  expect(v).toBe(`${y}-${md}`);

  // ◀ 后退一天 → 视点日回到今日
  await page.getByTestId("gantt-center-prev").click();
  const v2 = await group.getAttribute("data-value");
  expect(v2).not.toBe(`${y}-${md}`);
});

test("范围过滤(YPJ-220):缺省 board 祖先链 / group 多选并集 / milestone / all / 工况跨页", async ({
  page,
  projectKey,
}) => {
  const parent = await apiCreateItem(projectKey, "E2E 范围父");
  const a = await makeChild(
    projectKey,
    "E2E 范围A",
    parent,
    "2030-01-01",
    "2030-01-03",
  );
  const b = await makeChild(
    projectKey,
    "E2E 范围B",
    parent,
    "2030-02-01",
    "2030-02-03",
  );
  const o = await apiCreateItem(projectKey, "E2E 圈外O");
  await apiCall("PATCH", `/api/items/${o}`, {
    startDate: "2030-03-01",
    dueDate: "2030-03-03",
  }); // 排期:不落「未排期」折叠组,可见性断言才有效

  // 两个 active 组型下的 sprint 组:A∈S1,B∈S2;S1 激活
  const g1 = (
    await apiCall<{ groupId: string }>(
      "POST",
      `/api/projects/${projectKey}/groups`,
      { typeKey: "sprint", name: "E2S1" },
    )
  ).groupId;
  const g2 = (
    await apiCall<{ groupId: string }>(
      "POST",
      `/api/projects/${projectKey}/groups`,
      { typeKey: "sprint", name: "E2S2" },
    )
  ).groupId;
  await apiCall("PUT", `/api/items/${a}/group-membership`, {
    groupId: g1,
    groupType: "sprint",
  });
  await apiCall("PUT", `/api/items/${b}/group-membership`, {
    groupId: g2,
    groupType: "sprint",
  });
  await apiCall(
    "POST",
    `/api/projects/${projectKey}/groups/${g1}/actions/start`,
    {},
  );
  // 里程碑挂 A
  const ms = (
    await apiCall<{ milestoneId: string }>(
      "POST",
      `/api/projects/${projectKey}/milestones`,
      { name: "E2M1" },
    )
  ).milestoneId;
  await apiCall("PUT", `/api/projects/${projectKey}/milestones/${ms}/items`, {
    itemIds: [a],
  });

  // 缺省 board:S1 active → A + 祖先父;B(planned 组籍)/O(backlog)不在——祖先链不截断
  await page.goto(`/p/${projectKey}/gantt`);
  await expect(page.getByText("E2E 范围父")).toBeVisible();
  await expect(page.getByText("E2E 范围A")).toBeVisible();
  await expect(page.getByText("E2E 范围B")).toBeHidden();
  await expect(page.getByText("E2E 圈外O")).toBeHidden();

  // group 多选:S1+S2 并集 → A,B + 祖先父;O 仍不在
  await pickScope(page, "分组");
  await page.getByPlaceholder("选择分组").click();
  await page.getByRole("option", { name: "E2S1" }).click();
  await page.getByRole("option", { name: "E2S2" }).click();
  await page.keyboard.press("Escape");
  await expect(page.getByText("E2E 范围A")).toBeVisible();
  await expect(page.getByText("E2E 范围B")).toBeVisible();
  await expect(page.getByText("E2E 圈外O")).toBeHidden();

  // milestone:E2M1 → A + 祖先父;B 退场
  await pickScope(page, "里程碑");
  await page.getByTestId("gantt-scope-milestone").click();
  await page.waitForTimeout(300); // MUI Menu 淡入期吞点击,先等落定
  await page.getByRole("option", { name: "E2M1" }).click();
  await expect(page.getByRole("listbox")).toBeHidden();
  await expect(page.getByText("E2E 范围A")).toBeVisible();
  await expect(page.getByText("E2E 范围B")).toBeHidden();

  // all 兜底:O 也在
  await pickScope(page, "全部");
  await expect(page.getByText("E2E 圈外O")).toBeVisible();

  // 工况跨页:甘特→其它页→回甘特,范围原样恢复(仍 all)
  await page.goto(`/p/${projectKey}/backlog`);
  await page.goto(`/p/${projectKey}/gantt`);
  await expect(page.getByText("E2E 圈外O")).toBeVisible();

  // 行标题可点进 item 详情(YPJ-220 反馈)
  await page.getByText("E2E 范围A").click();
  await expect(page).toHaveURL(new RegExp(`/p/${projectKey}/i/.+`));
});

test("父自有日期不撑轴(YPJ-220 反馈):轴取子孙并集,忽略父自身排期", async ({
  page,
  projectKey,
}) => {
  const parent = await apiCreateItem(projectKey, "E2E 轴父");
  // 父自有 due 远期(若参与锚定,轴会拉到 2031);仅子项排期在 2020-01
  await apiCall("PATCH", `/api/items/${parent}`, {
    startDate: "2031-06-01",
    dueDate: "2031-06-05",
  });
  await makeChild(projectKey, "E2E 轴子", parent, "2020-01-01", "2020-01-03");

  await page.goto(`/p/${projectKey}/gantt`);
  await pickScope(page, "全部");
  await expect(page.getByText("E2E 轴父")).toBeVisible();
  // 月刻度无 2031(父日期未参与锚定);子区间 2020-01 在
  await expect(page.getByText("2031-06")).toHaveCount(0);
  await expect(page.getByTestId("gantt-row-day")).toBeVisible();
});

test("无 due 的 item 画起点小标记,不外延到轴末端(YPJ-220 反馈)", async ({
  page,
  projectKey,
}) => {
  const item = await apiCreateItem(projectKey, "E2E 仅开始");
  await apiCall("PATCH", `/api/items/${item}`, { startDate: "2020-03-01" });

  await page.goto(`/p/${projectKey}/gantt`);
  await pickScope(page, "全部");
  await expect(page.getByText("E2E 仅开始")).toBeVisible();
  // 本项目仅此一条已排期行;若仍外延到轴末端,条宽会是数百 px
  const bar = page.locator('[data-testid^="gantt-bar-"]');
  await expect(bar).toHaveCount(1);
  const w = await bar.evaluate((el) => el.getBoundingClientRect().width);
  expect(w).toBeLessThan(20);
});

// YPJ-229 回归(转正用例):加载中点日窗 → 就绪后补放;今日=当前时刻居中
test("时线回归(YPJ-229):加载中点日窗补放 + 今日时刻居中 + 零时长条可见", async ({
  page,
  projectKey,
}) => {
  const item = await apiCreateItem(projectKey, "E2E 时线零时长");
  // start=due 同刻(零时长条,今天 00:00 → 在数据锚定轴内且今日窗可见)
  const today = new Date();
  const ymd = `${today.getFullYear()}-${String(today.getMonth() + 1).padStart(2, "0")}-${String(today.getDate()).padStart(2, "0")}`;
  await apiCall("PATCH", `/api/items/${item}`, {
    startDate: `${ymd}T00:00`,
    dueDate: `${ymd}T00:00`,
  });
  // 建 active sprint 并挂入:缺省 board 范围内可见(否则空行不渲染轴)
  const gid = await apiCreateSprint(projectKey, "E2S-229");
  await apiCall(
    "POST",
    `/api/projects/${projectKey}/groups/${gid}/actions/start`,
    {},
  );
  await apiCall("PUT", `/api/items/${item}/group-membership`, {
    groupId: gid,
    groupType: "sprint",
  });

  // 拦截 gantt API 延迟 1.5s,模拟慢加载;期间点 1 日
  await page.route("**/gantt*", async (route) => {
    await new Promise((r) => setTimeout(r, 1500));
    await route.continue();
  });
  await page.goto(`/p/${projectKey}/gantt`);
  await page.getByTestId("gantt-win-1").click(); // 数据未到,应挂起
  // 数据到达后:小时刻度出现(1 日窗补放成功),非 fit 挤压
  await expect(page.getByText("00:00", { exact: true }).first()).toBeVisible({
    timeout: 10000,
  });
  // 今日线(2px 蓝竖线)在视口内(视点日=今日 → 当前时刻居中)
  const todayVis = await page.evaluate(() => {
    const scroll = document.querySelector('[data-testid="gantt-scroll"]');
    if (!scroll) return false;
    const lines = [...scroll.querySelectorAll("div")].filter((d) => {
      const s = getComputedStyle(d);
      return s.width === "2px" && s.position === "absolute";
    });
    return lines.some((d) => {
      const x = (d as HTMLElement).offsetLeft - scroll.scrollLeft;
      return x >= 0 && x <= scroll.clientWidth;
    });
  });
  expect(todayVis).toBe(true);
  // 零时长条可见(宽 ≥3px)
  const w = await page
    .locator('[data-testid^="gantt-bar-"]')
    .first()
    .evaluate((el) => el.getBoundingClientRect().width);
  expect(w).toBeGreaterThanOrEqual(3);
});

test("行标题完整呈现+标题列独立横滚 + 1 日窗宽屏不夹(YPJ-230/236)", async ({
  page,
  projectKey,
}) => {
  // 段 1:标题自然宽不截断,标题列超宽时自有底部横滚(YPJ-236;230 缺陷 A 的截断方案已被取代)
  const item = await apiCreateItem(
    projectKey,
    "E2E 标题横滚——超长标题应当完整呈现在标题列内,列底部出现横向滚动条",
  );
  await apiCall("PATCH", `/api/items/${item}`, {
    startDate: "2031-03-01T10:00",
    dueDate: "2031-03-02T12:00",
  });
  await page.goto(`/p/${projectKey}/gantt`);
  await pickScope(page, "全部");
  const title = page
    .locator('[data-testid^="gantt-title-"]')
    .filter({ hasText: "E2E 标题横滚" });
  await expect(title).toBeVisible();
  const w = await title.evaluate((el) => el.getBoundingClientRect().width);
  expect(w).toBeGreaterThanOrEqual(200); // 自然宽呈现(230 缺陷 A 时 inline 文本画出盒/截断 120)
  const pane = await page
    .getByTestId("gantt-titlepane")
    .evaluate((el) => ({ scrollW: el.scrollWidth, clientW: el.clientWidth }));
  expect(pane.scrollW).toBeGreaterThan(pane.clientW); // 标题列自有横滚

  // 段 2:1920 宽屏「1日」不被 MAX_PPD 夹——修复前 pxPerDay 停在 720,显示 ~1.9 天(YPJ-230 缺陷 B)
  await page.setViewportSize({ width: 1920, height: 1080 });
  await page.getByTestId("gantt-win-1").click();
  const gap = await page.getByTestId("gantt-row-day").evaluate((el) => {
    const xs = [...el.children].map((c) => (c as HTMLElement).offsetLeft);
    return xs.length > 1 ? xs[1] - xs[0] : 0;
  });
  const avail = await page
    .getByTestId("gantt-scroll")
    .evaluate((el) => el.clientWidth);
  expect(Math.abs(gap - avail)).toBeLessThanOrEqual(avail * 0.05);
});

test("分割线拖拽改标题列宽并记忆,双击重置(YPJ-237)", async ({
  page,
  projectKey,
}) => {
  const item = await apiCreateItem(
    projectKey,
    "E2E 分割线拖拽——标题列宽可调,刷新后保持",
  );
  await apiCall("PATCH", `/api/items/${item}`, {
    startDate: "2031-03-01T10:00",
    dueDate: "2031-03-02T12:00",
  });
  await page.goto(`/p/${projectKey}/gantt`);
  await pickScope(page, "全部");
  // 等数据/水合就绪再拖:goto 后立即拖有 flake(两轮实锤:down 被 late-hydration 窗口吞掉)
  await page.locator('[data-testid^="gantt-bar-"]').first().waitFor();
  // 1920 视口:1280 下 clamp 上限 = floor(卡片可视宽/2)≈386,拖 +100 会贴脸被夹
  await page.setViewportSize({ width: 1920, height: 1080 });
  const paneW = () =>
    page
      .getByTestId("gantt-titlepane")
      .evaluate((el) => el.getBoundingClientRect().width);
  expect(await paneW()).toBeCloseTo(280, 0); // 出厂宽

  // 拖拽:右移 100px → 标题列变宽(不写死目标像素,断相对变化)
  const sp = page.getByTestId("gantt-splitter");
  const spBox = await sp.boundingBox();
  await page.mouse.move(
    spBox!.x + spBox!.width / 2,
    spBox!.y + spBox!.height / 2,
  );
  await page.mouse.down();
  await page.mouse.move(
    spBox!.x + spBox!.width / 2 + 100,
    spBox!.y + spBox!.height / 2,
    { steps: 5 },
  );
  await page.mouse.up();
  const after = await paneW();
  expect(after).toBeGreaterThanOrEqual(370); // 280+100(±热区中线偏移 ≤1px)
  expect(after).toBeLessThanOrEqual(385);

  // 记忆:reload 后保持(裸 item 缺省 board 不可见,先切「全部」;再等行渲染防 hydration 竞态)
  await page.reload();
  await pickScope(page, "全部");
  await page.locator('[data-testid^="gantt-bar-"]').first().waitFor();
  expect(await paneW()).toBeCloseTo(after, 0);

  // 双击重置 → 280
  await sp.dblclick();
  expect(await paneW()).toBeCloseTo(280, 0);
});

test("实际条+偏移徽标+清单遮罩+折叠徽标+hover 卡(YPJ-222/spec 0147)", async ({
  page,
  projectKey,
}) => {
  const statuses = (
    await apiCall<{ statuses: { statusId: string; name: string }[] }>(
      "GET",
      `/api/projects/${projectKey}`,
    )
  ).statuses;
  const wipId = statuses.find((s) => s.name === "In Progress")!.statusId;
  const finalId = statuses[statuses.length - 1].statusId;

  // 甲(#1):start+due → 迁 In Progress → Done;清单 2 完 1;指派 me(FINAL/迁状态需有主)
  // 日期用近期动态值:actualEnd=now 纳入轴范围(spec 0147),老日期会把 fit 轴拉爆
  const d = (offset: number) => {
    const t = new Date();
    t.setDate(t.getDate() + offset);
    return `${t.getFullYear()}-${String(t.getMonth() + 1).padStart(2, "0")}-${String(t.getDate()).padStart(2, "0")}`;
  };
  const a = await apiCreateItem(projectKey, "E2E 实际甲");
  await apiCall("PATCH", `/api/items/${a}`, {
    startDate: d(-4),
    dueDate: d(-1),
  });
  await apiAssignMe(a);
  const t1 = await apiCall<{ checklistItemId: string }>(
    "POST",
    `/api/items/${a}/checklist`,
    { text: "t1" },
  );
  await apiCall("POST", `/api/items/${a}/checklist`, { text: "t2" });
  await apiCall("PATCH", `/api/checklist/${t1.checklistItemId}`, {
    done: true,
  });
  await apiCall("PATCH", `/api/items/${a}`, { statusItemId: wipId });
  await apiCall("PATCH", `/api/items/${a}`, { statusItemId: finalId });

  // 乙(#2):start+due 在近过去,迁 In Progress 未完成 → 实际条延伸到今日线,徽标延期
  const b = await apiCreateItem(projectKey, "E2E 实际乙");
  await apiCall("PATCH", `/api/items/${b}`, {
    startDate: d(-6),
    dueDate: d(-2),
  });
  await apiAssignMe(b);
  await apiCall("PATCH", `/api/items/${b}`, { statusItemId: wipId });

  // 父(#3)+ 两个子(#4/#5):折叠徽标 +2 子任务(日期同样近期,防轴拉爆)
  const parent = await apiCreateItem(projectKey, "E2E 折叠父");
  await makeChild(projectKey, "E2E 折叠子1", parent, d(-10), d(-8));
  await makeChild(projectKey, "E2E 折叠子2", parent, d(-4), d(-1));

  await page.goto(`/p/${projectKey}/gantt`);
  await pickScope(page, "全部");

  const row = (n: number) =>
    page.locator(`a[aria-label="${projectKey}-${n}"]`).locator("xpath=..");

  // 甲:实际条在(FINAL → 止于 actualEnd),偏移徽标正(延期),遮罩 50%
  await expect(
    row(1).getByTestId(`gantt-actual-${projectKey}-1`),
  ).toBeVisible();
  await expect(row(1).getByTestId(`gantt-offset-${projectKey}-1`)).toHaveText(
    /^\+\d+d$/,
  );
  // 遮罩宽 = 清单完成率 50%(宽度走 emotion class,inline style 拿不到——仓库已知陷阱,读几何比值)
  const pct = await row(1)
    .getByTestId(`gantt-actual-${projectKey}-1`)
    .evaluate((el) => {
      const inner = el.firstElementChild as HTMLElement;
      const overlay = inner.firstElementChild as HTMLElement | null;
      if (!overlay) return null;
      const w = inner.getBoundingClientRect().width;
      const ow = overlay.getBoundingClientRect().width;
      return w > 0 ? Math.round((ow / w) * 100) : null;
    });
  expect(pct).toBe(50);

  // 乙:实际条延伸到今日线(未完成),徽标延期
  await expect(
    row(2).getByTestId(`gantt-actual-${projectKey}-2`),
  ).toBeVisible();
  await expect(row(2).getByTestId(`gantt-offset-${projectKey}-2`)).toHaveText(
    /^\+\d+d$/,
  );

  // 折叠父 → 徽标 +2 子任务;展开 → 消失(折叠钮在标题列面板;工具栏还有个 ▶,须收窄到 titlepane)
  const titlePane = page.getByTestId("gantt-titlepane");
  await titlePane.getByRole("button", { name: "▼", exact: true }).click();
  await expect(
    row(3).getByTestId(`gantt-collapsed-${projectKey}-3`),
  ).toHaveText("+2 子任务");
  await expect(row(4)).toBeHidden();
  await titlePane.getByRole("button", { name: "▶", exact: true }).click();
  await expect(
    row(3).getByTestId(`gantt-collapsed-${projectKey}-3`),
  ).toHaveCount(0);

  // hover 甲计划条 → 详情卡(含清单计数与实际行)
  const bb = await row(1)
    .getByTestId(`gantt-bar-${projectKey}-1`)
    .boundingBox();
  await page.mouse.move(bb!.x + bb!.width / 2, bb!.y + bb!.height / 2);
  const tip = page.locator(".MuiTooltip-tooltip");
  await expect(tip).toContainText("E2E 实际甲");
  await expect(tip).toContainText("清单:1/2");
  await expect(tip).toContainText("实际:");
});

test("缺省视窗下限(YPJ-234):宽区间溢出可读 + 今日按钮只居中不重置缩放(案乙)", async ({
  page,
  projectKey,
}) => {
  // 宽跨度 fixture(~±400 天,今日居中位):本用例专用,老日期不跨用例(A22 教训)
  const d = (offset: number) => {
    const t = new Date();
    t.setDate(t.getDate() + offset);
    return `${t.getFullYear()}-${String(t.getMonth() + 1).padStart(2, "0")}-${String(t.getDate()).padStart(2, "0")}`;
  };
  const far = await apiCreateItem(projectKey, "E2E 宽窗远端");
  await apiCall("PATCH", `/api/items/${far}`, {
    startDate: d(-400),
    dueDate: d(-390),
  });
  const nearId = await apiCreateItem(projectKey, "E2E 宽窗近端");
  await apiCall("PATCH", `/api/items/${nearId}`, {
    startDate: d(390),
    dueDate: d(400),
  });

  await page.goto(`/p/${projectKey}/gantt`);
  await pickScope(page, "全部"); // API 直建 item 不在 board,切全量口径(与其他用例同)
  await expect(page.getByText("E2E 宽窗远端").first()).toBeVisible();

  // 缺省态:fit < 3px/天 → 下限生效,内容溢出可横滚(不再压满一屏)
  await expect
    .poll(() =>
      page
        .getByTestId("gantt-scroll")
        .evaluate(
          (el) =>
            (el.firstElementChild as HTMLElement).scrollWidth >
            el.clientWidth + 100,
        ),
    )
    .toBe(true);

  // 条可读:任一条 ≥3px
  const w = await page
    .locator('[data-testid^="gantt-bar-"]')
    .first()
    .evaluate((el) => el.getBoundingClientRect().width);
  expect(w).toBeGreaterThanOrEqual(3);

  // 挂载:今日线入视口(0147 语义保留,每挂载至多一次)
  const inView = () =>
    page.evaluate(() => {
      const scroll = document.querySelector(
        '[data-testid="gantt-scroll"]',
      ) as HTMLElement;
      if (!scroll) return false;
      const lines = [...scroll.querySelectorAll("div")].filter((el) => {
        const s = getComputedStyle(el);
        return s.width === "2px" && s.position === "absolute";
      });
      return lines.some((el) => {
        const x = (el as HTMLElement).offsetLeft - scroll.scrollLeft;
        return x >= 0 && x <= scroll.clientWidth;
      });
    });
  await expect.poll(inView).toBe(true);

  // 放大两档 → 滚回最左 → 点今日:今日线居中且缩放不变(案乙:不重置缩放)
  await page.getByTestId("gantt-zoom-in").click();
  await page.getByTestId("gantt-zoom-in").click();
  const contentW = await page
    .getByTestId("gantt-scroll")
    .evaluate((el) => (el.firstElementChild as HTMLElement).scrollWidth);
  await page.evaluate(() => {
    (
      document.querySelector('[data-testid="gantt-scroll"]') as HTMLElement
    ).scrollLeft = 0;
  });
  await page.getByTestId("gantt-today").click();
  await expect
    .poll(() =>
      page.evaluate(() => {
        const scroll = document.querySelector(
          '[data-testid="gantt-scroll"]',
        ) as HTMLElement;
        const lines = [...scroll.querySelectorAll("div")].filter((el) => {
          const s = getComputedStyle(el);
          return s.width === "2px" && s.position === "absolute";
        });
        return lines.some((el) => {
          const x = (el as HTMLElement).offsetLeft - scroll.scrollLeft;
          return Math.abs(x - scroll.clientWidth / 2) < 40;
        });
      }),
    )
    .toBe(true);
  const contentW2 = await page
    .getByTestId("gantt-scroll")
    .evaluate((el) => (el.firstElementChild as HTMLElement).scrollWidth);
  expect(contentW2).toBe(contentW); // 缩放未被「今日」重置
});
