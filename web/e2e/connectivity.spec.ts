// 0104:后端不可达 → 静默重试(1s/2s)后全局横幅;恢复后任一请求成功自愈。
// route abort 模拟断连,不依赖真停后端,CI 可跑。自愈用客户端导航(模块级 store 不因导航重置,reload 则会)。
import { expect, test } from "@playwright/test";
import { config } from "./config";
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

test("代理 502 同样触发横幅(Next rewrites 断连形态)", async ({ page }) => {
  await injectToken(page);
  await page.route("**/api/**", (route) =>
    route.fulfill({ status: 502, body: "Bad Gateway" }),
  );
  await page.goto("/");
  // 502 走重试路径(非业务错误),耗尽后 setDown;自愈已由上一用例覆盖
  await expect(page.getByText(/后端不可达/)).toBeVisible({ timeout: 15_000 });
});

test("登录页断连——横幅亮 + 中文报错(而非 Failed to fetch)", async ({
  page,
}) => {
  // bootstrapOrLogin 曾裸 fetch:网络失败把 TypeError 原文抛给页面且不 setDown(YPJ-153 手工验证实锤)
  await page.route("**/api/**", (route) => route.abort());
  await page.goto("/login");
  await page.getByLabel("用户名").fill(config.username);
  await page.getByLabel("密码").fill(config.password);
  await page.getByRole("button", { name: "登录" }).click();
  // 横幅(登录页也覆盖)+ login 页错误文案均为中文;英文原文绝不允许出现
  await expect(page.getByText(/后端不可达/).first()).toBeVisible({
    timeout: 15_000,
  });
  await expect(page.getByText("Failed to fetch")).toHaveCount(0);
});
