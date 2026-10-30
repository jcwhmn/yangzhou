// DateInput 非法日期处理流 + 月历键盘导航(spec 0029 / YPJ-34+23;PRD_V2 §6.1/§7.1/§10)
import type { Page } from "@playwright/test";
import { expect, test } from "./support/fixtures";
import { apiCreateItem, expectDate, fillDate } from "./support/helpers";

const seg = (page: Page, label: string, name: "月" | "日" | "年") =>
  page
    .getByRole("group", { name: label, exact: true })
    .getByLabel(name, { exact: true });

async function gotoDetail(page: Page, projectKey: string, title: string) {
  const itemId = await apiCreateItem(projectKey, title);
  await page.goto(`/p/${projectKey}/i/${itemId}`);
}

test("段内 digit 拒绝:月 1+3 拒、日 0+0 拒(§6.1/§7.1)", async ({
  page,
  projectKey,
}) => {
  await gotoDetail(page, projectKey, "E2E 日期 digit 拒绝");
  const mm = seg(page, "开始日期", "月");
  const dd = seg(page, "开始日期", "日");
  await mm.click(); // 空控件聚焦预填今天
  await mm.fill(""); // 清掉预填,从空开始
  await page.keyboard.press("1");
  await expect(mm).toHaveValue("1"); // 等待第二位
  await page.keyboard.press("3"); // 13 非法 → 拒 + 红
  await expect(mm).toHaveValue("1");
  await expect(mm).toHaveAttribute("aria-invalid", "true");
  await page.keyboard.press("2"); // 12 → 跳日段
  await expect(mm).toHaveValue("12");
  await expect(dd).toBeFocused();
  await expect(mm).not.toHaveAttribute("aria-invalid", "true");

  await dd.fill("");
  await page.keyboard.press("0"); // 等待第二位
  await page.keyboard.press("0"); // 00 → 拒
  await expect(dd).toHaveValue("0");
  await page.keyboard.press("5"); // 05 → 跳年段
  await expect(dd).toHaveValue("05");
  await expect(seg(page, "开始日期", "年")).toBeFocused();
  const yyyy = seg(page, "开始日期", "年");
  const sel = await yyyy.evaluate((el) => {
    const inp = el as HTMLInputElement;
    return [inp.selectionStart, inp.selectionEnd];
  });
  expect(sel).toEqual([0, 1]); // 落段首位 digit 选中(overwrite 视觉)
});

test("commit 阻止:02/31/2026 不提交、focus 拉回日段(§10.3)", async ({
  page,
  projectKey,
}) => {
  await gotoDetail(page, projectKey, "E2E 日期 commit 阻止");
  await fillDate(page, "开始日期", "2026-02-31");
  const g = page.getByRole("group", { name: "开始日期", exact: true });
  await expect(g).toHaveAttribute("data-value", "");
  await expect(seg(page, "开始日期", "日")).toBeFocused();
});

test("闰年 02/29/2028 提交;非闰年 02/29/2027 阻止", async ({
  page,
  projectKey,
}) => {
  await gotoDetail(page, projectKey, "E2E 闰年边界");
  await fillDate(page, "开始日期", "2028-02-29");
  await expectDate(page, "开始日期", "2028-02-29");
  await fillDate(page, "截止日期", "2027-02-29");
  await expect(
    page.getByRole("group", { name: "截止日期", exact: true }),
  ).toHaveAttribute("data-value", "");
  await expect(seg(page, "截止日期", "日")).toBeFocused();
});

test("空控件聚焦预填今天,未输入失焦 → value 保持空(§10.2)", async ({
  page,
  projectKey,
}) => {
  await gotoDetail(page, projectKey, "E2E 日期空进空出");
  const g = page.getByRole("group", { name: "截止日期", exact: true });
  await seg(page, "截止日期", "月").click(); // 预填今天(仅显示基准)
  await seg(page, "截止日期", "月").blur();
  await expect(g).toHaveAttribute("data-value", "");
});

test("部分输入:只改日段,blur 提交月/年=今天(§17-3)", async ({
  page,
  projectKey,
}) => {
  await gotoDetail(page, projectKey, "E2E 日期部分输入");
  const dd = seg(page, "截止日期", "日");
  await dd.click(); // 预填今天
  await dd.fill("08"); // 逐位写完跳年段(§7.1)
  await seg(page, "截止日期", "年").blur(); // 从年段离开触发 commit
  const t = new Date();
  const exp = `${t.getFullYear()}-${String(t.getMonth() + 1).padStart(2, "0")}-08`;
  await expectDate(page, "截止日期", exp);
});

test("Esc 回滚 committed value 且消红", async ({ page, projectKey }) => {
  await gotoDetail(page, projectKey, "E2E 日期 Esc 回滚");
  await fillDate(page, "开始日期", "2026-01-15");
  await expectDate(page, "开始日期", "2026-01-15");
  const dd = seg(page, "开始日期", "日");
  await dd.press("Home");
  await page.keyboard.press("3"); // 35 = 合法部分态(3_)
  await page.keyboard.press("5"); // 35 > 31 → 拒
  await expect(dd).toHaveValue("35");
  await page.keyboard.press("Escape");
  await expect(dd).toHaveValue("15");
  await expectDate(page, "开始日期", "2026-01-15");
});

test("月历:点日回填提交关闭;←/→ 移格 Enter 选中(YPJ-23)", async ({
  page,
  projectKey,
}) => {
  await gotoDetail(page, projectKey, "E2E 月历导航");
  await fillDate(page, "开始日期", "2026-01-15");
  const calBtn = page.getByRole("button", { name: "月历 开始日期" });
  const pop = page.locator(".MuiPopover-paper");
  await calBtn.click();
  await expect(pop).toBeVisible();
  await pop.getByRole("button", { name: "20", exact: true }).click();
  await expectDate(page, "开始日期", "2026-01-20");
  await expect(pop).toBeHidden();

  await calBtn.click();
  await expect(pop).toBeVisible();
  await pop.getByRole("button", { name: "15", exact: true }).focus();
  await page.keyboard.press("ArrowRight"); // → 16
  await page.keyboard.press("Enter");
  await expectDate(page, "开始日期", "2026-01-16");
  await expect(pop).toBeHidden();
});

test("draft 不外泄:编辑中 data-value 不变,提交才变(§3.1/§3.2)", async ({
  page,
  projectKey,
}) => {
  await gotoDetail(page, projectKey, "E2E draft 不外泄");
  const g = page.getByRole("group", { name: "开始日期", exact: true });
  const mm = seg(page, "开始日期", "月");
  const dd = seg(page, "开始日期", "日");
  await mm.click(); // 预填今天
  await mm.fill("");
  await page.keyboard.press("1"); // 月段 1_(等待位)
  await expect(g).toHaveAttribute("data-value", ""); // 编辑中不外泄
  await page.keyboard.press("2"); // 12 → 跳日段
  await dd.press("Home");
  // 键盘写入而非 fill:预填日段=今天,每月 20 号时 fill("20") 同值覆写被 React value-tracker 去重,
  // onChange 不派发 → §7.1 跳段不触发 → 年段无焦点、blur no-op(2026-10-20 实证);keydown 路径无此坑
  await dd.pressSequentially("20");
  await expect(g).toHaveAttribute("data-value", ""); // 仍不外泄
  await seg(page, "开始日期", "年").blur(); // fill("20") 跳年段(§7.1),从年段离开触发 commit
  const t = new Date();
  const exp = `${t.getFullYear()}-12-20`; // 月/日为键入值,年=今天基准
  await expectDate(page, "开始日期", exp);
});

test("Clear:清空、回焦月段并以今天为基准(§14)", async ({
  page,
  projectKey,
}) => {
  await gotoDetail(page, projectKey, "E2E Clear 契约");
  await fillDate(page, "开始日期", "2026-01-15");
  await expectDate(page, "开始日期", "2026-01-15");
  const g = page.getByRole("group", { name: "开始日期", exact: true });
  const clearBtn = g.getByRole("button", { name: "清除 开始日期" });
  await expect(clearBtn).toBeVisible();
  await clearBtn.click();
  await expect(g).toHaveAttribute("data-value", "");
  await expect(seg(page, "开始日期", "月")).toBeFocused(); // 回焦(§14)
  await seg(page, "开始日期", "月").blur(); // 纯预填不产生值(§10.2)
  await expect(g).toHaveAttribute("data-value", "");
});

test("ARIA:group role/name、段名、月历按钮名(§2.2/§15)", async ({
  page,
  projectKey,
}) => {
  await gotoDetail(page, projectKey, "E2E ARIA 契约");
  const g = page.getByRole("group", { name: "开始日期", exact: true });
  await expect(g).toBeVisible();
  for (const s of ["月", "日", "年"] as const)
    await expect(seg(page, "开始日期", s)).toBeVisible();
  await expect(g.getByRole("button", { name: "月历 开始日期" })).toBeVisible();
});
