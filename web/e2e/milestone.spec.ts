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
