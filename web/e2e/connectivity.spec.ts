// 0104:后端不可达 → 静默重试(1s/2s)后全局横幅;恢复后任一请求成功自愈。
// route abort 模拟断连,不依赖真停后端,CI 可跑。自愈用客户端导航(模块级 store 不因导航重置,reload 则会)。
import { expect, test } from "@playwright/test";
import { injectToken } from "./support/helpers";

test("API 不可达显示横幅,恢复后自愈 @smoke", async ({ page }) => {
  await injectToken(page);
  await page.route("**/api/**", (route) => route.abort());
  await page.goto("/");
  // 退避 1s+2s 后 setDown;给足重试窗口
  await expect(page.getByText(/后端不可达/)).toBeVisible({ timeout: 15_000 });
  await page.unroute("**/api/**");
  // 客户端导航触发新请求 → 成功 → setUp 自愈(不 reload,否则 store 重置测不到自愈)
  await page.getByRole("link", { name: "我的能力" }).click();
  await expect(page).toHaveURL(/\/capabilities$/);
  await expect(page.getByText(/后端不可达/)).toBeHidden();
});
