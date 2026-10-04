// 登录/会话/上次项目——本文件走 UI 登录(登录行为本身是被测对象)
import { expect, test } from "@playwright/test";
import { config } from "./config";
import { apiCreateProject, apiToken, uniqueKey } from "./support/helpers";

async function uiLogin(page: import("@playwright/test").Page) {
  await page.goto("/login");
  await page.getByLabel("用户名").fill(config.username);
  await page.getByLabel("密码").fill(config.password);
  await page.getByRole("button", { name: "登录" }).click();
}

test.describe.configure({ mode: "serial" });

test("登录进首页", async ({ page }) => {
  await page.goto("/login");
  await page.getByLabel("用户名").fill(config.username);
  await page.getByLabel("密码").fill(config.password);
  await page.getByRole("button", { name: "登录" }).click();
  // 登录后落在首页或上次项目(V11-S1),两者都算成功
  await expect(page).toHaveURL(/\/$|\/p\/[A-Z0-9]+$/);
});

test("V11-S1 登录后进入上次项目", async ({ page }) => {
  const key = uniqueKey();
  await apiCreateProject(key);
  const token = await apiToken();
  // 看板 mount 写 last-project;API 再兜底写一次,压掉并行用例的干扰写
  await page.goto(`/p/${key}`);
  await fetch(`${config.apiURL}/api/members/me/last-project`, {
    method: "PUT",
    headers: { "Content-Type": "application/json", Authorization: `Bearer ${token}` },
    body: JSON.stringify({ key }),
  });
  // 清会话重新登录 → 落回该项目
  await page.evaluate(() => localStorage.clear());
  await uiLogin(page);
  await expect(page).toHaveURL(new RegExp(`/p/${key}$`));
});

test("V13-S2 退出登录 + 悬空 last-project 回首页", async ({ page }) => {
  const token = await apiToken();
  // last-project 指向不存在的项目(模拟项目被删/并行写覆盖)
  await fetch(`${config.apiURL}/api/members/me/last-project`, {
    method: "PUT",
    headers: { "Content-Type": "application/json", Authorization: `Bearer ${token}` },
    body: JSON.stringify({ key: "GONE99" }),
  });
  await uiLogin(page);
  // V13-S2(b):悬空 key 校验 404 → 停留首页
  await expect(page).toHaveURL(/\/$/);
  await expect(page.getByRole("heading", { name: "项目", exact: true })).toBeVisible();
  // 退出登录:token 清掉,回 /login
  await page.getByRole("button", { name: "退出登录" }).click();
  await expect(page).toHaveURL(/\/login$/);
  expect(await page.evaluate(() => localStorage.getItem("yz-token"))).toBeNull();
});
