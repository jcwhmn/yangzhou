// 甘特 S1(YPJ-218/spec 0139):父汇总条、FINAL 置灰、父时间线禁用与加子迁移拦截
import { expect } from "./support/fixtures";
import { test } from "./support/fixtures";
import {
  apiAssignMe,
  apiCall,
  apiCreateItem,
  apiCreateSprint,
  fillDate,
} from "./support/helpers";

/** UI 切范围(YPJ-220)。依赖缺省 board 后,旧用例需显式切「全部」复现全量语义。 */
async function pickScope(page: import("@playwright/test").Page, label: string) {
  await page.getByTestId("gantt-scope").click();
  // MUI Menu 淡入期(~200ms)会吞下层点击(仓库已知陷阱),先等动画落定再点
  await page.waitForTimeout(300);
  await page.getByRole("option", { name: label }).click();
  await expect(page.getByRole("listbox")).toBeHidden();
}

/** YPJ-245(SRS §10.6):FINAL 行默认折叠进「已完成(N)」组;断言 FINAL 行视觉前先展开。 */
async function expandCompleted(page: import("@playwright/test").Page) {
  // 等组按钮出现(rows 加载完成)再点——isVisible 竞态实锤:数据未载时跑空不点(2026-11-21 三红同根)
  const btn = page.getByTestId("gantt-completed-group");
  await btn.waitFor({ state: "visible", timeout: 10000 }).catch(() => {});
  if (await btn.isVisible().catch(() => false)) {
    await btn.click();
  }
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
  await expandCompleted(page); // 子一 FINAL → 已完成组(YPJ-245)
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

  // YPJ-256:日窗预设按钮已删,小时刻度改 zoom-in 连点(×1.5/档)到达
  for (let i = 0; i < 14; i++) await page.getByTestId("gantt-zoom-in").click();
  await expect(page.getByTestId("gantt-row-hour")).toBeVisible({
    timeout: 10000,
  });
  await expect(page.getByText(/\d{2}:00/).first()).toBeVisible();

  // 工况保持:切页再回来,缩放位记忆保持(小时刻度还在)
  await page.goto(`/p/${projectKey}/backlog`);
  await page.goto(`/p/${projectKey}/gantt`);
  await expect(page.getByTestId("gantt-row-hour")).toBeVisible();
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

// YPJ-229 回归(转正用例):今日=当前时刻居中;挂起补放段随日窗预设删除(YPJ-256)
test("时线回归(YPJ-229):今日时刻居中 + 零时长条可见", async ({
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

  await page.goto(`/p/${projectKey}/gantt`);
  // YPJ-256:日窗预设/挂起补放机制已删;等行渲染后点今日回中(只居中不重置缩放)
  await expect(
    page.locator('[data-testid^="gantt-bar-"]').first(),
  ).toBeVisible();
  await page.getByTestId("gantt-today").click();
  // 今日线(2px 蓝竖线)在视口内(视点日=今日 → 当前时刻居中);poll:居中滚动是异步生效
  await expect
    .poll(() =>
      page.evaluate(() => {
        const scroll = document.querySelector('[data-testid="gantt-scroll"]');
        if (!scroll) return false;
        const lines = [...scroll.querySelectorAll("div")].filter((d) => {
          const s = getComputedStyle(d);
          return s.width === "2px" && s.position === "absolute";
        });
        return lines.some((d) => {
          // 高度 >8px:条区零高度时今日线(top:0/bottom:0)不可见(A44 回归钉)
          const h = (d as HTMLElement).getBoundingClientRect().height;
          const x = (d as HTMLElement).offsetLeft - scroll.scrollLeft;
          return h > 8 && x >= 0 && x <= scroll.clientWidth;
        });
      }),
    )
    .toBe(true);
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

  // 段 2:1920 宽屏缩放不被夹——zoom-in 连点后日距超旧 720 钳制且 ≤ MAX_PPD(YPJ-230 缺陷 B;预设按钮删除 YPJ-256)
  await page.setViewportSize({ width: 1920, height: 1080 });
  for (let i = 0; i < 12; i++) await page.getByTestId("gantt-zoom-in").click();
  const gap = await page.getByTestId("gantt-row-day").evaluate((el) => {
    const xs = [...el.children].map((c) => (c as HTMLElement).offsetLeft);
    return xs.length > 1 ? xs[1] - xs[0] : 0;
  });
  const avail = await page
    .getByTestId("gantt-scroll")
    .evaluate((el) => el.clientWidth);
  expect(gap).toBeGreaterThanOrEqual(1000); // 超过旧 720 钳制(可读)
  expect(gap).toBeLessThanOrEqual(2400 + 1e-6); // MAX_PPD 上限仍生效
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
    dueDate: d(-3), // YPJ-245:Δ≥24h → ±Nd 档确定性(原 -1d 落 ±Nh/On time 时变)
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
  await expandCompleted(page); // 甲 FINAL → 已完成组(YPJ-245)

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

  // 乙(#2):进行中单条覆盖模型(SRS v4 §6.2)——有计划条 → 无下条实际条;
  // YPJ-255:覆盖=进度,乙无清单无工时 → 无覆盖(原时间流逝满条口径废除);进行中零徽标
  await expect(row(2).getByTestId(`gantt-actual-${projectKey}-2`)).toHaveCount(
    0,
  );
  await expect(row(2).getByTestId(`gantt-fill-${projectKey}-2`)).toHaveCount(0);
  await expect(row(2).getByTestId(`gantt-offset-${projectKey}-2`)).toHaveCount(
    0,
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
  // 起止含时分、工时按小时(YPJ-244:数据含时分;实际行含「· 工时 x.xh」)
  await expect(tip).toContainText(/\d{2}:\d{2}/);
  await expect(tip).toContainText(/工时 \d+(\.\d+)?h/);
  await expect(tip).toContainText("实际:");
});

test("YPJ-255 进度覆盖:提前开工行(计划条全在未来)覆盖可见 + 工时/Estimate 兜底(A47 边界)", async ({
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
  const d = (offset: number) => {
    const t = new Date();
    t.setDate(t.getDate() + offset);
    return `${t.getFullYear()}-${String(t.getMonth() + 1).padStart(2, "0")}-${String(t.getDate()).padStart(2, "0")}`;
  };

  // 甲(#1):计划条全在未来(start=明天),已提前开工(迁 In Progress + 补录工时 90m)→ A47 场景:
  // 覆盖 = 90m/4h = 37.5%,照画在未来段上(原时间流逝口径下此处归零不可见)
  const a = await apiCreateItem(projectKey, "E2E 提前开工甲");
  await apiCall("PATCH", `/api/items/${a}`, {
    startDate: d(1),
    dueDate: d(3),
    estimateValue: 4,
    estimateUnit: "hour",
  });
  await apiAssignMe(a);
  await apiCall("POST", `/api/items/${a}/time-entries`, { minutes: 90 });
  await apiCall("PATCH", `/api/items/${a}`, { statusItemId: wipId });

  // 乙(#2):同场景但无清单无工时 → 无覆盖(占比无源)
  const b = await apiCreateItem(projectKey, "E2E 无进度乙");
  await apiCall("PATCH", `/api/items/${b}`, {
    startDate: d(1),
    dueDate: d(3),
  });
  await apiAssignMe(b);
  await apiCall("PATCH", `/api/items/${b}`, { statusItemId: wipId });

  await page.goto(`/p/${projectKey}/gantt`);
  await pickScope(page, "全部");
  await expect(page.getByText("E2E 提前开工甲").first()).toBeVisible();

  const row = (n: number) =>
    page.locator(`a[aria-label="${projectKey}-${n}"]`).locator("xpath=..");

  const ratio = await row(1)
    .getByTestId(`gantt-fill-${projectKey}-1`)
    .evaluate((el) => {
      const bar = el.parentElement as HTMLElement;
      const w = bar.getBoundingClientRect().width;
      const f = el.getBoundingClientRect().width;
      return w > 0 ? f / w : null;
    });
  expect(ratio).not.toBeNull();
  expect(ratio!).toBeGreaterThan(0.3);
  expect(ratio!).toBeLessThan(0.45);
  await expect(row(2).getByTestId(`gantt-fill-${projectKey}-2`)).toHaveCount(0);
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

  // 按钮缩放锚点 = 视口中心(用户 2026-11-19 建议):今日线初始居中,+ 后仍应 ≈ 居中
  await page.getByTestId("gantt-zoom-in").click();
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

// YPJ-244(spec 0151)切片1:工时填充(案A)+ 完成点(H5)+ 依赖锚点位(H6)+ 实际层解耦
test("工时填充+完成点+依赖锚点+实际层解耦(YPJ-244)", async ({
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
  // 日期动态值(仓库惯例:老日期把 fit 轴拉爆)
  const d = (offset: number) => {
    const t = new Date();
    t.setDate(t.getDate() + offset);
    return `${t.getFullYear()}-${String(t.getMonth() + 1).padStart(2, "0")}-${String(t.getDate()).padStart(2, "0")}`;
  };

  // 甲(#1):start=d(-4) due=d(6) 进行中 → 计划条 + 琥珀填充(未过期 → 部分填充)
  const a = await apiCreateItem(projectKey, "E2E 填充甲");
  await apiCall("PATCH", `/api/items/${a}`, {
    startDate: d(-4),
    dueDate: d(6),
    estimateValue: 8,
    estimateUnit: "hour",
  });
  await apiAssignMe(a);
  // YPJ-255:覆盖=进度,无清单 → 工时/Estimate 兕底(60m/8h=12.5%)——补录工时保住「部分填充」断言
  await apiCall("POST", `/api/items/${a}/time-entries`, { minutes: 60 });
  await apiCall("PATCH", `/api/items/${a}`, { statusItemId: wipId });

  // 乙(#2):直建 FINAL(actualEnd 有而 actualStart 无)→ 完成点(终态迁移需有主)
  const b = await apiCreateItem(projectKey, "E2E 完成点乙");
  await apiAssignMe(b);
  await apiCall("PATCH", `/api/items/${b}`, { statusItemId: finalId });

  // 丙(#3):无 start 无 due,依赖甲(甲有 due)→ 锚点位画点(H6)
  const c = await apiCreateItem(projectKey, "E2E 锚点丙");
  await apiCall("POST", `/api/items/${c}/dependencies`, {
    dependsOnItemId: a,
  });

  // 丁(#4):无 start 无 due 进行中 → 计划层点在 actualStart + 实际条(H5 解耦)
  const e = await apiCreateItem(projectKey, "E2E 解耦丁");
  await apiAssignMe(e);
  await apiCall("PATCH", `/api/items/${e}`, { statusItemId: wipId });

  await page.goto(`/p/${projectKey}/gantt`);
  await pickScope(page, "全部");
  await expandCompleted(page); // 乙直建 FINAL → 已完成组(YPJ-245)
  await expect(page.getByText("E2E 填充甲").first()).toBeVisible();

  const row = (n: number) =>
    page.locator(`a[aria-label="${projectKey}-${n}"]`).locator("xpath=..");

  // 甲:填充在,且是部分填充(今天在 start~due 之间,填充比 ∈ (0,1))
  const fillRatio = await row(1)
    .getByTestId(`gantt-fill-${projectKey}-1`)
    .evaluate((el) => {
      const bar = el.parentElement as HTMLElement;
      const w = bar.getBoundingClientRect().width;
      const f = el.getBoundingClientRect().width;
      return w > 0 ? f / w : null;
    });
  expect(fillRatio).not.toBeNull();
  expect(fillRatio!).toBeGreaterThan(0.05);
  expect(fillRatio!).toBeLessThan(0.95);

  // 乙:直建 FINAL——后端迁移史语义:一次 TODO→FINAL 迁移同时记 actualStart/actualEnd(同刻),
  // 渲染为零宽实际条(3px 最小宽);completeDot(实际起点无而终点有)形态在现后端不可达,
  // 分支保留作防御(台账 A30 记录 spec 0151 与后端 hist 语义的偏差)
  await expect(
    row(2).getByTestId(`gantt-actual-${projectKey}-2`),
  ).toBeVisible();

  // 丙:锚点位画点(无 start 无 due 有 anchorDate)
  await expect(
    row(3).getByTestId(`gantt-anchor-${projectKey}-3`),
  ).toBeVisible();

  // 丁:三无进行中(case1)=黄条 actualStart→今天(§五 颜色2),无计划层点
  await expect(
    row(4).getByTestId(`gantt-actual-${projectKey}-4`),
  ).toBeVisible();
  await expect(row(4).getByTestId(`gantt-plandot-${projectKey}-4`)).toHaveCount(
    0,
  );
});

// YPJ-244(spec 0151 §五):case1/2 借 group 开始锚——三无=group 点;有 estimate 无锚=group 起铺算条
test("group 锚:case1 group 点 + case2 group 起铺算条(YPJ-244)", async ({
  page,
  projectKey,
}) => {
  const d = (offset: number) => {
    const t = new Date();
    t.setDate(t.getDate() + offset);
    return `${t.getFullYear()}-${String(t.getMonth() + 1).padStart(2, "0")}-${String(t.getDate()).padStart(2, "0")}`;
  };
  const parent = await apiCreateItem(projectKey, "E2E group 父");
  await apiCall("PATCH", `/api/items/${parent}`, { startDate: d(-2) });
  // C1(#2):有 estimate 无 start 无依赖(case2 未开始)→ 蓝条 @ group 开始,长=4h 铺算
  const c1 = await apiCall<{ itemId: string }>(
    "POST",
    `/api/projects/${projectKey}/items`,
    { title: "E2E 估无锚子", parentItemId: parent },
  ).then((r) => r.itemId);
  await apiCall("PATCH", `/api/items/${c1}`, {
    estimateValue: 4,
    estimateUnit: "hour",
  });
  // C2(#3):三无(case1 未开始)→ 蓝点 @ group 开始
  await apiCall("POST", `/api/projects/${projectKey}/items`, {
    title: "E2E 三无子",
    parentItemId: parent,
  });

  await page.goto(`/p/${projectKey}/gantt`);
  await pickScope(page, "全部");
  await expect(page.getByText("E2E group 父").first()).toBeVisible();

  const row = (n: number) =>
    page.locator(`a[aria-label="${projectKey}-${n}"]`).locator("xpath=..");

  // C1:铺算条在(宽度=4h 工作窗铺算,>3px 非零点)
  const w = await row(2)
    .getByTestId(`gantt-bar-${projectKey}-2`)
    .evaluate((el) => (el.firstElementChild as HTMLElement).offsetWidth);
  expect(w).toBeGreaterThan(3);
  // C2:group 点在
  await expect(
    row(3).getByTestId(`gantt-grouppoint-${projectKey}-3`),
  ).toBeVisible();
});

// 钟表轴线性(2026-11-20 拍板):同起点 8h vs 4h,计划条宽 2:1
test("条长∝钟表跨度:同起点 8h vs 4h 计划条宽 2:1(A28)", async ({
  page,
  projectKey,
}) => {
  const start = "2031-01-06T09:00"; // 周一 09:00(窗起点)
  const a = await apiCreateItem(projectKey, "E2E 工时比例 8h");
  await apiCall("PATCH", `/api/items/${a}`, {
    startDate: start,
    estimateValue: 8,
    estimateUnit: "hour",
  });
  const b = await apiCreateItem(projectKey, "E2E 工时比例 4h");
  await apiCall("PATCH", `/api/items/${b}`, {
    startDate: start,
    estimateValue: 4,
    estimateUnit: "hour",
  });

  await page.goto(`/p/${projectKey}/gantt`);
  await pickScope(page, "全部");
  await expect(page.getByText("E2E 工时比例 8h")).toBeVisible();

  const wOf = (n: number) =>
    page
      .locator(`a[aria-label="${projectKey}-${n}"]`)
      .locator("xpath=..")
      .getByTestId(`gantt-bar-${projectKey}-${n}`)
      .evaluate((el) => (el.firstElementChild as HTMLElement).offsetWidth);
  const w8 = await wOf(1);
  const w4 = await wOf(2);
  // 8h = 8/9 列,4h = 4/9 列 → 2:1(±容差)
  expect(w8 / w4).toBeGreaterThan(1.8);
  expect(w8 / w4).toBeLessThan(2.2);
});

// YPJ-244(spec 0151)切片2:表单后写者为准——先改 estimate 后改 due,保存 → due 后发为准,estimate 反推 + warnings toast
test("表单后写者为准:due 后发覆盖 estimate + warnings toast(YPJ-244)", async ({
  page,
  projectKey,
}) => {
  const d = (offset: number) => {
    const t = new Date();
    t.setDate(t.getDate() + offset);
    return `${t.getFullYear()}-${String(t.getMonth() + 1).padStart(2, "0")}-${String(t.getDate()).padStart(2, "0")}`;
  };
  const a = await apiCreateItem(projectKey, "E2E 后写者为准");
  await apiCall("PATCH", `/api/items/${a}`, { startDate: d(-1) });

  await page.goto(`/p/${projectKey}/i/${a}`);
  // 详情页 title 在标题输入框 value 里(getByText 匹配不到),等表单就绪信号
  await expect(
    page.getByRole("button", { name: "保存", exact: true }),
  ).toBeVisible();

  // 先改 estimate(自定义 2 小时)→ estDirty, lastEdited=est
  await page.getByLabel("预估").click();
  await page.waitForTimeout(300); // MUI Menu 淡出陷阱
  await page.getByRole("option", { name: "自定义" }).click();
  await expect(page.getByRole("listbox")).toBeHidden();
  await page.getByLabel("数值").fill("2");

  // 后改 due(明天)→ dueDirty, lastEdited=due;保存 → PATCH1{estimate} → PATCH2{due}
  await fillDate(page, "截止日期", d(1));
  await page.getByRole("button", { name: "保存", exact: true }).click();

  // warnings toast(后端 due 后写反推警告)
  await expect(
    page.getByTestId("item-warnings").locator(".MuiAlert-root"),
  ).toBeVisible();

  // estimate 被反推(≠2;date-only 窗口两天 >18h)——用 toHaveValue 重试等 load() 刷新表单
  // (直接 inputValue 有竞态:toast 可见≠load 完成,全量负载下单跑碰巧绿的假阳性,台账 A32)
  await expect(page.getByLabel("数值")).not.toHaveValue("2");
  const est = await page.getByLabel("数值").inputValue();
  expect(Number(est)).toBeGreaterThan(0);
});

// YPJ-245(spec 0152):徽标三档/返工渲染/已完成折叠组
test("YPJ-245 徽标三档:On time / ±Nh / 提前绿(仅 FINAL)", async ({
  page,
  projectKey,
}) => {
  const statuses = (
    await apiCall<{ statuses: { statusId: string; name: string }[] }>(
      "GET",
      `/api/projects/${projectKey}`,
    )
  ).statuses;
  const finalId = statuses[statuses.length - 1].statusId;
  const dtH = (offsetHours: number) => {
    // 毫秒偏移:setHours 加小数会被截断成整数小时(-0.5h→-1h,A45 实锤)
    const t = new Date(Date.now() + offsetHours * 3600000);
    const p2 = (n: number) => String(n).padStart(2, "0");
    return `${t.getFullYear()}-${p2(t.getMonth() + 1)}-${p2(t.getDate())}T${p2(t.getHours())}:${p2(t.getMinutes())}`;
  };

  // On time:FINAL 时刻 ≈ due+0.5h(|Δ|<1h 中性)
  const a = await apiCreateItem(projectKey, "E2E 徽标准点");
  await apiAssignMe(a);
  await apiCall("PATCH", `/api/items/${a}`, {
    startDate: dtH(-1),
    dueDate: dtH(-0.5),
  });
  await apiCall("PATCH", `/api/items/${a}`, { statusItemId: finalId });

  // ±Nh:due = now-5h,FINAL → +5h 红
  const b = await apiCreateItem(projectKey, "E2E 徽标Nh");
  await apiAssignMe(b);
  await apiCall("PATCH", `/api/items/${b}`, {
    startDate: dtH(-6),
    dueDate: dtH(-5),
  });
  await apiCall("PATCH", `/api/items/${b}`, { statusItemId: finalId });

  // 提前绿:due 未来 2 天,FINAL → -2d 绿
  const c = await apiCreateItem(projectKey, "E2E 徽标提前");
  await apiAssignMe(c);
  await apiCall("PATCH", `/api/items/${c}`, {
    startDate: dtH(-1),
    dueDate: dtH(48),
  });
  await apiCall("PATCH", `/api/items/${c}`, { statusItemId: finalId });

  await page.goto(`/p/${projectKey}/gantt`);
  await pickScope(page, "全部");
  await expandCompleted(page); // 三条全 FINAL → 已完成组

  const row = (n: number) =>
    page.locator(`a[aria-label="${projectKey}-${n}"]`).locator("xpath=..");
  await expect(row(1).getByTestId(`gantt-offset-${projectKey}-1`)).toHaveText(
    "On time",
  );
  await expect(row(2).getByTestId(`gantt-offset-${projectKey}-2`)).toHaveText(
    /^\+\d+h$/,
  );
  await expect(row(3).getByTestId(`gantt-offset-${projectKey}-3`)).toHaveText(
    /^-\d+d$/,
  );
});

test("YPJ-245 返工渲染:当前状态优先,完成史进 hover(SRS §6.6)", async ({
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
  const d = (offset: number) => {
    const t = new Date();
    t.setDate(t.getDate() + offset);
    const p2 = (n: number) => String(n).padStart(2, "0");
    return `${t.getFullYear()}-${p2(t.getMonth() + 1)}-${p2(t.getDate())}`;
  };

  const a = await apiCreateItem(projectKey, "E2E 返工甲");
  await apiAssignMe(a);
  await apiCall("PATCH", `/api/items/${a}`, {
    startDate: d(-4),
    dueDate: d(-1),
    estimateValue: 4,
    estimateUnit: "hour",
  });
  // YPJ-255:覆盖=进度——补录工时给覆盖一个源(60m/4h),「黄覆盖在」仍是进行中渲染的探针
  await apiCall("POST", `/api/items/${a}/time-entries`, { minutes: 60 });
  await apiCall("PATCH", `/api/items/${a}`, { statusItemId: wipId });
  await apiCall("PATCH", `/api/items/${a}`, { statusItemId: finalId });
  await apiCall("PATCH", `/api/items/${a}`, { statusItemId: wipId }); // FINAL 后返工

  await page.goto(`/p/${projectKey}/gantt`);
  await pickScope(page, "全部");

  const row = (n: number) =>
    page.locator(`a[aria-label="${projectKey}-${n}"]`).locator("xpath=..");
  // 返工行按 §6.2 进行中渲染:黄覆盖在(主树,不入已完成组)、无下条、零徽标
  await expect(row(1).getByTestId(`gantt-fill-${projectKey}-1`)).toBeVisible();
  await expect(row(1).getByTestId(`gantt-actual-${projectKey}-1`)).toHaveCount(
    0,
  );
  await expect(row(1).getByTestId(`gantt-offset-${projectKey}-1`)).toHaveCount(
    0,
  );
  // 完成史进 hover
  // hover 目标 = Tooltip 命中区(设计上覆盖条面,fill 被其覆盖不可直 hover,A45)
  await row(1).getByTestId(`gantt-hover-plan-${projectKey}-1`).hover();
  await expect(page.getByText(/上次完成:/)).toBeVisible();
});

test("YPJ-245 已完成折叠组:FINAL 默认收起可展开(SRS §10.6)", async ({
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
  const d = (offset: number) => {
    const t = new Date();
    t.setDate(t.getDate() + offset);
    const p2 = (n: number) => String(n).padStart(2, "0");
    return `${t.getFullYear()}-${p2(t.getMonth() + 1)}-${p2(t.getDate())}`;
  };

  const a = await apiCreateItem(projectKey, "E2E 完成一");
  await apiAssignMe(a);
  await apiCall("PATCH", `/api/items/${a}`, {
    startDate: d(-4),
    dueDate: d(-2),
  });
  await apiCall("PATCH", `/api/items/${a}`, { statusItemId: wipId });
  await apiCall("PATCH", `/api/items/${a}`, { statusItemId: finalId });
  const b = await apiCreateItem(projectKey, "E2E 完成二");
  await apiAssignMe(b);
  await apiCall("PATCH", `/api/items/${b}`, {
    startDate: d(-3),
    dueDate: d(-1),
  });
  await apiCall("PATCH", `/api/items/${b}`, { statusItemId: finalId });
  const c = await apiCreateItem(projectKey, "E2E 进行中");
  await apiAssignMe(c);
  await apiCall("PATCH", `/api/items/${c}`, {
    startDate: d(-1),
    dueDate: d(1),
  });
  await apiCall("PATCH", `/api/items/${c}`, { statusItemId: wipId });

  await page.goto(`/p/${projectKey}/gantt`);
  await pickScope(page, "全部");

  const grp = page.getByTestId("gantt-completed-group");
  await expect(grp).toContainText("已完成(2)");
  const row = (n: number) =>
    page.locator(`a[aria-label="${projectKey}-${n}"]`).locator("xpath=..");
  await expect(row(1)).toBeHidden(); // FINAL 默认收起
  await expect(row(2)).toBeHidden();
  await expect(row(3)).toBeVisible(); // 进行中在主树
  await grp.click();
  await expect(row(1)).toBeVisible();
  await expect(row(2)).toBeVisible();
  await grp.click();
  await expect(row(1)).toBeHidden();
});

// ===== YPJ-221(spec 0158 r2):里程碑/发布时点竖线 + 旗标带 =====

/** 近期动态日期(A22 教训:固定老日期拉爆轴;marker 日期同理) */
function dayOffset(offset: number): string {
  const t = new Date();
  t.setDate(t.getDate() + offset);
  return `${t.getFullYear()}-${String(t.getMonth() + 1).padStart(2, "0")}-${String(t.getDate()).padStart(2, "0")}`;
}

/** 已排期底座 item:双面板仅 rows 非空渲染,行区撑起后竖线/旗标才可见 */
async function makeScheduledItem(key: string, title: string): Promise<void> {
  const id = await apiCreateItem(key, title);
  await apiCall("PATCH", `/api/items/${id}`, {
    startDate: dayOffset(0),
    dueDate: dayOffset(2),
  });
}

test("里程碑竖线+旗标:两态 x 位有序 + gate 章 + tooltip + 点击跳页(YPJ-221)", async ({
  page,
  projectKey,
}) => {
  await makeScheduledItem(projectKey, "E2E 竖线底座");
  const m1 = (
    await apiCall<{ milestoneId: string }>(
      "POST",
      `/api/projects/${projectKey}/milestones`,
      { name: "E2M1", targetDate: dayOffset(3) },
    )
  ).milestoneId;
  const m2 = (
    await apiCall<{ milestoneId: string }>(
      "POST",
      `/api/projects/${projectKey}/milestones`,
      { name: "E2M2", targetDate: dayOffset(10) },
    )
  ).milestoneId;
  await apiCall("PATCH", `/api/projects/${projectKey}/milestones/${m2}`, {
    status: "in_progress",
  });
  // gate 里程碑:挂 1 个 FINAL item → 旗标 1/1(无 targetDate 的里程碑不上图,故带日期)
  const statuses = (
    await apiCall<{ statuses: { statusId: string; name: string }[] }>(
      "GET",
      `/api/projects/${projectKey}`,
    )
  ).statuses;
  const finalId = statuses[statuses.length - 1].statusId;
  const gi = await apiCreateItem(projectKey, "E2E 关口件");
  await apiAssignMe(gi);
  await apiCall("PATCH", `/api/items/${gi}`, { statusItemId: finalId });
  const m3 = (
    await apiCall<{ milestoneId: string }>(
      "POST",
      `/api/projects/${projectKey}/milestones`,
      { name: "E2M3", targetDate: dayOffset(6) },
    )
  ).milestoneId;
  await apiCall("PUT", `/api/projects/${projectKey}/milestones/${m3}/items`, {
    itemIds: [gi],
  });

  await page.goto(`/p/${projectKey}/gantt`);
  await pickScope(page, "全部");
  await expect(page.getByTestId("gantt-flag-band")).toBeVisible();
  await expect(page.getByTestId(`gantt-ms-flag-${m1}`)).toBeVisible();
  await expect(page.getByTestId(`gantt-ms-flag-${m2}`)).toBeVisible();
  await expect(page.getByTestId(`gantt-ms-flag-${m3}`)).toHaveText(/1\/1/);
  // x 位有序:d(3) 在 d(10) 左侧(轴已纳入 marker 日期,无钳边假位移)
  const l1 = await page
    .getByTestId(`gantt-ms-flag-${m1}`)
    .evaluate((el) => el.getBoundingClientRect().left);
  const l2 = await page
    .getByTestId(`gantt-ms-flag-${m2}`)
    .evaluate((el) => el.getBoundingClientRect().left);
  expect(l2).toBeGreaterThan(l1);
  // hover → tooltip 全信息;点击 → 跳里程碑管理页
  await page.getByTestId(`gantt-ms-flag-${m1}`).hover();
  await expect(page.getByText(/目标日期:/)).toBeVisible();
  await page.getByTestId(`gantt-ms-flag-${m1}`).click();
  await expect(page).toHaveURL(new RegExp(`/p/${projectKey}/milestones`));
});

test("发布竖线:planned 虚线@targetDate / released 实线@releasedDate(YPJ-221)", async ({
  page,
  projectKey,
}) => {
  await makeScheduledItem(projectKey, "E2E 发布底座");
  const r1 = (
    await apiCall<{ releaseId: string }>(
      "POST",
      `/api/projects/${projectKey}/releases`,
      { name: "E2R1", targetDate: dayOffset(5) },
    )
  ).releaseId;
  const r2 = (
    await apiCall<{ releaseId: string }>(
      "POST",
      `/api/projects/${projectKey}/releases`,
      {
        name: "E2R2",
        version: "1.0",
        targetDate: dayOffset(-5),
        status: "released",
        releasedDate: dayOffset(-2),
      },
    )
  ).releaseId;

  await page.goto(`/p/${projectKey}/gantt`);
  await pickScope(page, "全部");
  const styleOf = (testid: string) =>
    page
      .getByTestId(testid)
      .evaluate((el) => getComputedStyle(el).borderLeftStyle);
  await expect(page.getByTestId(`gantt-rel-flag-${r1}`)).toBeVisible();
  await expect(page.getByTestId(`gantt-rel-flag-${r2}`)).toHaveText(/v1\.0/);
  // 线型:planned 虚线 / released 实线;r2 只画 releasedDate(拍板:targetDate 不画)
  expect(await styleOf(`gantt-rel-line-${r1}`)).toBe("dashed");
  expect(await styleOf(`gantt-rel-line-${r2}`)).toBe("solid");
  // r2 线在 r1 左侧(d(-2) < d(5));轴纳入 releasedDate,不在钳边(负向最早日期 → 轴起点)
  const l1 = await page
    .getByTestId(`gantt-rel-line-${r1}`)
    .evaluate((el) => el.getBoundingClientRect().left);
  const l2 = await page
    .getByTestId(`gantt-rel-line-${r2}`)
    .evaluate((el) => el.getBoundingClientRect().left);
  expect(l2).toBeLessThan(l1);
});

test("无里程碑/发布:旗标带不渲染,行位不受影响(YPJ-221 E1)", async ({
  page,
  projectKey,
}) => {
  await makeScheduledItem(projectKey, "E2E 空态底座");
  await page.goto(`/p/${projectKey}/gantt`);
  await pickScope(page, "全部");
  await expect(page.getByTestId("gantt-flag-band")).toHaveCount(0);
  await expect(page.getByText("E2E 空态底座")).toBeVisible();
});
