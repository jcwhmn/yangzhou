// YPJ-238(spec 0166):看板列内箭头排序——用户主路径:点列头排序按钮 → 卡片出上下箭头 →
// 点箭头相邻交换 → 界面顺序变 → 刷新持久;再点按钮退出排序,箭头消失。
// 后端语义(全列重写/负例 400/删 item 级联清)由 BoardOrderApiTest 覆盖,此处不重复。
// 断言口径(2026-11-24 复盘 R2):必须断言「界面可见顺序」,不是「PUT 发出」——
// 本票曾因只断言请求不断言渲染而假绿,界面序从未变过(台账 A48)。
import { expect, test } from "./support/fixtures";
import { apiCall, apiCreateItem } from "./support/helpers";

test.describe("YPJ-238 箭头排序(S0 板上三票)", () => {
  test.use({ s0: true });

  const TITLES = ["BO 排序甲", "BO 排序乙", "BO 排序丙"];

  /** 界面可见顺序:全列卡片按渲染序提取标题(断言对象=用户看到的,非网络层) */
  async function visibleOrder(page: import("@playwright/test").Page) {
    return page.locator(".MuiCard-root").evaluateAll(
      (els, titles) =>
        els.map(
          (e) =>
            titles.find((t) => (e as HTMLElement).innerText.includes(t)) ?? "?",
        ),
      TITLES, // evaluateAll 闭包变量不序列化,必须作实参传入(A48 教训延伸:断言也要过真跑)
    );
  }

  test("箭头交换_界面顺序变_刷新持久_退出排序", async ({
    page,
    projectKey,
  }) => {
    for (const title of TITLES) {
      const id = await apiCreateItem(projectKey, title);
      await apiCall("PUT", `/api/items/${id}/board-membership`, {
        onBoard: true,
      });
    }
    await page.goto(`/p/${projectKey}/board`);
    await expect(page.getByText(TITLES[0])).toBeVisible();
    expect(await visibleOrder(page)).toEqual(TITLES); // 初始=编号自然序

    // 进入排序模式:列头按钮,卡片出箭头
    await page.getByRole("button", { name: "排序" }).first().click();
    await expect(page.getByRole("button", { name: "上移" })).toHaveCount(3);
    await expect(page.getByRole("button", { name: "下移" })).toHaveCount(3);

    // 甲下移:与相邻乙交换
    const put1 = page.waitForResponse(
      (r) =>
        r.url().includes("/board-order/move") &&
        r.request().method() === "POST",
    );
    await page
      .locator(".MuiCard-root", { hasText: TITLES[0] })
      .getByRole("button", { name: "下移" })
      .click();
    await put1;
    await expect
      .poll(() => visibleOrder(page))
      .toEqual([TITLES[1], TITLES[0], TITLES[2]]);

    // 刷新:顺序持久(排序是服务端记忆,不是前端态);排序模式是 UI 态,刷新后需重进
    await page.reload();
    await expect(page.getByText(TITLES[0])).toBeVisible();
    expect(await visibleOrder(page)).toEqual([TITLES[1], TITLES[0], TITLES[2]]);
    await page.getByRole("button", { name: "排序" }).first().click();

    // 甲上移:回到首位
    const put2 = page.waitForResponse(
      (r) =>
        r.url().includes("/board-order/move") &&
        r.request().method() === "POST",
    );
    await page
      .locator(".MuiCard-root", { hasText: TITLES[0] })
      .getByRole("button", { name: "上移" })
      .click();
    await put2;
    await expect.poll(() => visibleOrder(page)).toEqual(TITLES);

    // 退出排序:箭头消失,按钮复位
    await page.getByRole("button", { name: "退出排序" }).first().click();
    await expect(page.getByRole("button", { name: "上移" })).toHaveCount(0);
    await expect(page.getByRole("button", { name: "下移" })).toHaveCount(0);
  });

  test("边界_列首无上移_列尾无下移", async ({ page, projectKey }) => {
    for (const title of TITLES.slice(0, 2)) {
      const id = await apiCreateItem(projectKey, title);
      await apiCall("PUT", `/api/items/${id}/board-membership`, {
        onBoard: true,
      });
    }
    await page.goto(`/p/${projectKey}/board`);
    await page.getByRole("button", { name: "排序" }).first().click();
    // 两张卡:列首只有下移可用,列尾只有上移可用
    await expect(
      page
        .locator(".MuiCard-root", { hasText: TITLES[0] })
        .getByRole("button", { name: "上移" }),
    ).toBeDisabled();
    await expect(
      page
        .locator(".MuiCard-root", { hasText: TITLES[1] })
        .getByRole("button", { name: "下移" }),
    ).toBeDisabled();
  });
});
