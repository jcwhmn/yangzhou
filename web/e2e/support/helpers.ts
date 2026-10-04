// API 预置/清理 helpers:数据准备全走 API,用例本体只测 UI 行为
import { randomUUID } from "node:crypto";
import { expect, type Page } from "@playwright/test";
import { config } from "../config";

let cachedToken: string | null = null;

/** worker 级缓存一个 token(bootstrap 幂等,已有账号 409 → login) */
export async function apiToken(): Promise<string> {
  if (cachedToken) return cachedToken;
  const boot = await fetch(`${config.apiURL}/api/auth/bootstrap`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ username: config.username, password: config.password }),
  });
  if (!boot.ok && boot.status !== 409) throw new Error(`bootstrap ${boot.status}`);
  const login = await fetch(`${config.apiURL}/api/auth/login`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ username: config.username, password: config.password }),
  });
  if (!login.ok) throw new Error(`login ${login.status}`);
  cachedToken = ((await login.json()) as { token: string }).token;
  return cachedToken;
}

let seq = 0;

/** 并行安全的唯一项目 key(9 位:随机 hex6 + 进程内序号2;≤10 留 1 位给表单用例的「P」后缀) */
export function uniqueKey(): string {
  return ("K" + randomUUID().replace(/-/g, "").slice(0, 6).toUpperCase() + (seq++).toString(36).toUpperCase().padStart(2, "0")).slice(0, 9);
}

export async function apiCreateProject(key: string): Promise<void> {
  const res = await fetch(`${config.apiURL}/api/projects`, {
    method: "POST",
    headers: { "Content-Type": "application/json", Authorization: `Bearer ${await apiToken()}` },
    body: JSON.stringify({ key, name: `E2E ${key}` }),
  });
  // 409 = 并行下同名 key 已存在(理论不发生),容忍
  if (!res.ok && res.status !== 409) throw new Error(`创建项目 ${key}: ${res.status}`);
}

export async function apiCreateMember(displayName: string): Promise<void> {
  const res = await fetch(`${config.apiURL}/api/members`, {
    method: "POST",
    headers: { "Content-Type": "application/json", Authorization: `Bearer ${await apiToken()}` },
    body: JSON.stringify({ displayName }),
  });
  // 409 = 并行下同名成员已存在,容忍
  if (!res.ok && res.status !== 409) throw new Error(`建成员 ${displayName}: ${res.status}`);
}

export async function apiCreateItem(key: string, title: string): Promise<string> {
  const res = await fetch(`${config.apiURL}/api/projects/${key}/items`, {
    method: "POST",
    headers: { "Content-Type": "application/json", Authorization: `Bearer ${await apiToken()}` },
    body: JSON.stringify({ title }),
  });
  if (!res.ok) throw new Error(`创建 item: ${res.status}`);
  return ((await res.json()) as { itemId: string }).itemId;
}

export async function apiAssignMe(itemId: string): Promise<void> {
  const members = (await (await fetch(`${config.apiURL}/api/members`, { headers: { Authorization: `Bearer ${await apiToken()}` } })).json()) as {
    memberId: string;
    virtual: boolean;
  }[];
  const meId = members.find((m) => !m.virtual)!.memberId;
  const res = await fetch(`${config.apiURL}/api/items/${itemId}/assignee`, {
    method: "PUT",
    headers: { "Content-Type": "application/json", Authorization: `Bearer ${await apiToken()}` },
    body: JSON.stringify({ assigneeItemId: meId }),
  });
  if (!res.ok) throw new Error(`指派: ${res.status}`);
}

/** 页面导航前注入 token(绕 UI 登录;登录/登出行为本身在 auth.spec 用 UI 测) */
export async function injectToken(page: Page): Promise<void> {
  await page.addInitScript((t) => localStorage.setItem("yz-token", t), await apiToken());
}

/** MUI select 打开:hydration 期 SSR 原生 select 会被替换,首点可能落空;菜单没开就补一下 */
export async function openSelect(page: Page, label: string): Promise<void> {
  const box = page.getByRole("combobox", { name: label });
  await box.click();
  const opened = await page.getByRole("listbox").waitFor({ state: "visible", timeout: 1500 }).then(() => true, () => false);
  if (!opened) await box.click();
}

/** MUI select 选选项:页面 settle 期菜单可能开了又被关,点在卸载节点上会无声无效;
 *  以「combobox 显示值变为目标文本」为选中生效的硬信号,否则重开菜单重试 */
export async function selectOption(page: Page, label: string, name: string, expectText?: string): Promise<void> {
  const box = page.getByRole("combobox", { name: label });
  const opt = page.getByRole("option", { name });
  const lb = page.getByRole("listbox");
  for (let i = 0; i < 8; i++) {
    if (!(await lb.isVisible().catch(() => false))) await box.click();
    if (!(await opt.isVisible().catch(() => false))) {
      await opt.waitFor({ state: "visible", timeout: 1500 }).catch(() => {});
    }
    if (await opt.isVisible().catch(() => false)) {
      await opt.click({ timeout: 2000 }).catch(() => {});
    }
    if (await expect(box).toHaveText(expectText ?? name, { timeout: 2500 }).then(() => true, () => false)) return;
  }
  throw new Error(`下拉「${label}」未能选中「${name}」`);
}
