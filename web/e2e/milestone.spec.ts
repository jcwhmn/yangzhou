// Milestone 页(spec 0116 票 1,YPJ-170):弹窗退役 → /p/[key]/milestones 页面承载建/推进/编辑/删除;
// 侧栏收敛(只显当前 in_progress)+ 双徽标(后端算 overdue 红/dueSoon 黄)+ 段头常驻可点进页。
import { expect, test } from "./support/fixtures";
import { apiCall, fillDate } from "./support/helpers";

/** UTC 口径的昨日(后端 Clock=UTC;本地时区翻边时仍严格 < UTC 今天,徽标必红) */
function utcYesterday(): string {
  const now = new Date();
  const d = new Date(
    Date.UTC(now.getUTCFullYear(), now.getUTCMonth(), now.getUTCDate() - 1),
  );
  return d.toISOString().slice(0, 10);
}

test("Milestone 页——建/推进/编辑/删除 @smoke", async ({ page, projectKey }) => {
  // 预置:A 进行中,B 规划中(A 在前)
  const a = (
    await apiCall<{ milestoneId: string }>(
      "POST",
      `/api/projects/${projectKey}/milestones`,
      {
        name: "MVP 里程碑",
        targetDate: "2026-12-01",
      },
    )
  ).milestoneId;
  const b = (
    await apiCall<{ milestoneId: string }>(
      "POST",
      `/api/projects/${projectKey}/milestones`,
      {
        name: "Beta 里程碑",
      },
    )
  ).milestoneId;
  await apiCall("PATCH", `/api/projects/${projectKey}/milestones/${a}`, {
    status: "in_progress",
  });

  await page.goto(`/p/${projectKey}/milestones`);
  await expect(
    page.getByTestId("milestone-row").filter({ hasText: "MVP 里程碑" }),
  ).toBeVisible();
  await expect(
    page.getByTestId("milestone-row").filter({ hasText: "Beta 里程碑" }),
  ).toBeVisible();

  // UI 新建(带日期)
  await page.getByLabel("名称").fill("GA 里程碑");
  await fillDate(page, "目标日期", "2027-06-01");
  await page.getByRole("button", { name: "新建", exact: true }).click();
  const gaRow = page
    .getByTestId("milestone-row")
    .filter({ hasText: "GA 里程碑" });
  await expect(gaRow).toBeVisible();

  // 单向推进:B planned → 开始(A 已在进行 → 后端 409,页面 Alert 原样展示)
  const betaRow = page
    .getByTestId("milestone-row")
    .filter({ hasText: "Beta 里程碑" });
  await betaRow.getByRole("button", { name: /开始/ }).click();
  await expect(page.getByText(/已有进行中的 milestone/)).toBeVisible();

  // 完成 A → 409 解除,B 可开始
  const mvpRow = page
    .getByTestId("milestone-row")
    .filter({ hasText: "MVP 里程碑" });
  await mvpRow.getByRole("button", { name: /完成/ }).click();
  await expect(
    mvpRow.getByRole("button", { name: /开始|完成|取消/ }),
  ).toHaveCount(0); // 终态无状态按钮
  await betaRow.getByRole("button", { name: /开始/ }).click();
  await expect(page.getByText(/已有进行中的 milestone/)).toHaveCount(0);

  // 编辑:行内回填改名
  await gaRow.getByRole("button", { name: "编辑" }).click();
  await expect(page.getByLabel("名称")).toHaveValue("GA 里程碑");
  await page.getByLabel("名称").fill("GA 里程碑 改");
  await page.getByRole("button", { name: "保存" }).click();
  await expect(
    page.getByTestId("milestone-row").filter({ hasText: "GA 里程碑 改" }),
  ).toBeVisible();

  // 两段式删除(改名后重新定位,行文本已变)
  const del = page
    .getByTestId("milestone-row")
    .filter({ hasText: "GA 里程碑 改" })
    .getByRole("button", { name: "删除" });
  await del.click();
  await del.click();
  await expect(
    page.getByTestId("milestone-row").filter({ hasText: "GA 里程碑 改" }),
  ).toHaveCount(0);
});

test("侧栏收敛 双徽标 与段头入口", async ({ page, projectKey }) => {
  // 预置:昨日站进行中(UTC 昨日 → 必逾期红标);明日站规划中 → 只在页内
  const a = (
    await apiCall<{ milestoneId: string }>(
      "POST",
      `/api/projects/${projectKey}/milestones`,
      {
        name: "昨日站",
        targetDate: utcYesterday(),
      },
    )
  ).milestoneId;
  await apiCall("PATCH", `/api/projects/${projectKey}/milestones/${a}`, {
    status: "in_progress",
  });
  await apiCall("POST", `/api/projects/${projectKey}/milestones`, {
    name: "明日站",
    targetDate: "2027-06-01",
  });

  await page.goto(`/p/${projectKey}`);
  // 侧栏:当前行 + 红「已逾期」;规划中不上侧栏
  await expect(page.getByText(/▶ 昨日站/)).toBeVisible();
  await expect(page.getByTestId("milestone-nav-badge")).toHaveText("⚠ 已逾期");
  await expect(page.getByText("明日站")).toHaveCount(0);

  // 段头点击进页面(hydration 竞态可能吞掉首击,重试到 URL/行出现为止)
  await expect(async () => {
    await page.getByRole("link", { name: "Milestone", exact: true }).click();
    await expect(page).toHaveURL(new RegExp(`/p/${projectKey}/milestones`));
    await expect(
      page.getByTestId("milestone-row").filter({ hasText: "昨日站" }),
    ).toBeVisible();
  }).toPass({ timeout: 10_000 });

  // 页内:当前行红标;未来组行无徽标
  await expect(
    page
      .getByTestId("milestone-row")
      .filter({ hasText: "昨日站" })
      .getByTestId("milestone-badge"),
  ).toHaveText("⚠ 已逾期");
  await expect(
    page
      .getByTestId("milestone-row")
      .filter({ hasText: "明日站" })
      .getByTestId("milestone-badge"),
  ).toHaveCount(0);
});

test("侧栏空态与段头入口", async ({ page, projectKey }) => {
  await page.goto(`/p/${projectKey}`);
  // 无 in_progress → 段头常驻 + 空态提示
  await expect(page.getByTestId("milestone-nav-empty")).toHaveText(
    /无进行中里程碑/,
  );
  await expect(async () => {
    await page.getByRole("link", { name: "Milestone", exact: true }).click();
    await expect(page).toHaveURL(new RegExp(`/p/${projectKey}/milestones`));
    await expect(page.getByText("暂无 Milestone")).toBeVisible();
  }).toPass({ timeout: 10_000 });
});

// 票 2(spec 0116 §3.2):gate 挂接(tracks 多归属)+ 关口硬门禁(US-M8)
test("票2 item 详情挂两站 → 页内依据清单可见", async ({ page, projectKey }) => {
  const item = await apiCall<{ itemId: string }>(
    "POST",
    `/api/projects/${projectKey}/items`,
    { title: "支付联调" },
  );
  await apiCall("POST", `/api/projects/${projectKey}/milestones`, {
    name: "MVP 里程碑",
  });
  await apiCall("POST", `/api/projects/${projectKey}/milestones`, {
    name: "GA 里程碑",
  });

  // item 详情:chips 挂两站
  await page.goto(`/p/${projectKey}/i/${item.itemId}`);
  await expect(
    page.getByTestId("item-milestone-chip").filter({ hasText: "MVP 里程碑" }),
  ).toBeVisible();
  await page
    .getByTestId("item-milestone-chip")
    .filter({ hasText: "MVP 里程碑" })
    .click();
  await page
    .getByTestId("item-milestone-chip")
    .filter({ hasText: "GA 里程碑" })
    .click();

  // 页内:MVP 行展开依据 → 清单含 item,就绪度 1/1
  await page.goto(`/p/${projectKey}/milestones`);
  const mvpItem = page
    .getByTestId("milestone-item")
    .filter({ hasText: "MVP 里程碑" });
  await mvpItem.getByTestId("milestone-gates").click();
  await expect(
    mvpItem
      .getByTestId("gate-panel")
      .getByTestId("gate-row")
      .filter({ hasText: "支付联调" }),
  ).toBeVisible();
  // 就绪度 = gateDone/gateTotal(终态比例,US-M9);item 在 To Do 非终态 → 0/1,非挂接数
  await expect(mvpItem.getByTestId("milestone-gates")).toHaveText(/0\/1/);
  // 多归属:GA 行同 item
  const gaItem = page
    .getByTestId("milestone-item")
    .filter({ hasText: "GA 里程碑" });
  await gaItem.getByTestId("milestone-gates").click();
  await expect(
    gaItem
      .getByTestId("gate-panel")
      .getByTestId("gate-row")
      .filter({ hasText: "支付联调" }),
  ).toBeVisible();
});

test("票2 关口硬门禁:未终态 gate 拦完成,终态后放行", async ({
  page,
  projectKey,
}) => {
  const item = await apiCall<{ itemId: string }>(
    "POST",
    `/api/projects/${projectKey}/items`,
    { title: "未终态验收项" },
  );
  const ms = (
    await apiCall<{ milestoneId: string }>(
      "POST",
      `/api/projects/${projectKey}/milestones`,
      { name: "MVP 里程碑" },
    )
  ).milestoneId;
  await apiCall("PATCH", `/api/projects/${projectKey}/milestones/${ms}`, {
    status: "in_progress",
  });
  await apiCall("PUT", `/api/projects/${projectKey}/milestones/${ms}/items`, {
    itemIds: [item.itemId],
  });

  await page.goto(`/p/${projectKey}/milestones`);
  const row = page
    .getByTestId("milestone-row")
    .filter({ hasText: "MVP 里程碑" });
  await expect(row).toBeVisible();
  await expect(row.getByTestId("milestone-gates")).toHaveText(/0\/1/);

  // 完成被拦:409 错误条 + 行仍在进行中
  await row.getByRole("button", { name: /完成/ }).click();
  await expect(page.getByText(/关口未就绪/)).toBeVisible();
  await expect(row.getByRole("button", { name: /完成/ })).toBeVisible();

  // gate 终态化(开工须有主 → 先指派再推 Done)后放行
  const me = await apiCall<{ memberId: string }[]>("GET", "/api/members");
  await apiCall("PUT", `/api/items/${item.itemId}/assignee`, {
    assigneeItemId: me[0].memberId,
  });
  const proj = await apiCall<{
    statuses: { statusId: string; name: string }[];
  }>("GET", `/api/projects/${projectKey}`);
  const done = proj.statuses.find((s) => s.name === "Done")!.statusId;
  await apiCall("PATCH", `/api/items/${item.itemId}`, {
    statusItemId: done,
  });
  await row.getByRole("button", { name: /完成/ }).click();
  await expect(row.getByRole("button", { name: /开始|完成|取消/ })).toHaveCount(
    0,
  );
});

test("票3 时间线:日期落位与 overdue 红描边", async ({ page, projectKey }) => {
  // 预置:昨日(planned → overdue)+ 远期 + 无日期
  await apiCall("POST", `/api/projects/${projectKey}/milestones`, {
    name: "线昨日",
    targetDate: utcYesterday(),
  });
  await apiCall("POST", `/api/projects/${projectKey}/milestones`, {
    name: "线远期",
    targetDate: "2027-06-01",
  });
  await apiCall("POST", `/api/projects/${projectKey}/milestones`, {
    name: "线无期",
  });

  await page.goto(`/p/${projectKey}/milestones`);
  const nodes = page.getByTestId("timeline-node");
  await expect(nodes).toHaveCount(2); // 无日期不入轴,入「未排期」
  const yest = nodes.filter({ hasText: "线昨日" });
  const far = nodes.filter({ hasText: "线远期" });
  // 按日期落位:昨日 x < 远期 x
  const yx = (await yest.boundingBox())!.x;
  const fx = (await far.boundingBox())!.x;
  expect(yx).toBeLessThan(fx);
  // overdue:红描边 + ⚠;远期无徽标
  await expect(yest).toContainText("⚠");
  await expect(far).not.toContainText("⚠");
  const border = await yest
    .locator("p")
    .first()
    .evaluate((el) => getComputedStyle(el).borderColor);
  expect(border).toBe("rgb(198, 40, 40)"); // tokens.error #c62828
  // 无日期站入「未排期」区
  await expect(page.getByTestId("timeline-unscheduled")).toContainText(
    "线无期",
  );
});
