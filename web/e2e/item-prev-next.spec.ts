// YPJ-257(spec 0168):详情页桶内前后导航——切换、边界 Snackbar 提示
import { expect, test } from "@playwright/test";
import { config } from "./config";

test("257 边界 Snackbar", async ({ page, request }) => {
  const login = await request.post(`${config.apiURL}/api/auth/login`, {
    data: { username: config.username, password: config.password },
  });
  const { token } = await login.json();
  const proj = await request.post(`${config.apiURL}/api/projects`, {
    headers: { Authorization: `Bearer ${token}` },
    data: {
      name: "P257",
      key: "P" + Date.now().toString(36).toUpperCase().slice(-6),
    },
  });
  const p = await proj.json();
  const key = p.key ?? p.projectKey;
  const mk = (title: string) =>
    request
      .post(`${config.apiURL}/api/projects/${key}/items`, {
        headers: { Authorization: `Bearer ${token}` },
        data: { title },
      })
      .then((r) => r.json());
  const [a, b] = [await mk("票一"), await mk("票二")];
  const on = (itemId: string) =>
    request.put(`${config.apiURL}/api/items/${itemId}/board-membership`, {
      headers: { Authorization: `Bearer ${token}` },
      data: { onBoard: true },
    });
  await on(a.itemId);
  await on(b.itemId);
  await page.goto("/login");
  await page.evaluate((t) => localStorage.setItem("yz-token", t), token);
  await page.addInitScript(() => {
    (window as any).__kd = [];
    window.addEventListener("keydown", (e) =>
      (window as any).__kd.push(`${e.key}@${(e.target as Element).tagName}`),
    );
  });
  await page.goto(`/p/${key}/i/${a.number}`);
  await expect(page.getByLabel("标题")).toBeVisible();
  await expect(page.getByText("1 / 2")).toBeVisible({ timeout: 10000 });
  await page.keyboard.press("Control+ArrowLeft");
  await expect(page.getByText("已经是第一条")).toBeVisible({ timeout: 5000 });
  await page.keyboard.press("Control+ArrowRight"); // 翻到第 2 票(最后一票)
  await expect(page.getByText("2 / 2")).toBeVisible({ timeout: 10000 });
  await page.keyboard.press("Control+ArrowRight");
  await expect(page.getByText("已经是最后一条")).toBeVisible({ timeout: 5000 });
});
