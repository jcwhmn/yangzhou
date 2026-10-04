// API 预置/清理 helpers:数据准备全走 API,用例本体只测 UI 行为
import type { Page } from "@playwright/test";
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

/** 并行安全的唯一项目 key(2–10 位大写字母数字) */
export function uniqueKey(): string {
  return `K${process.pid.toString(36).slice(-1).toUpperCase()}${Date.now().toString(36).toUpperCase()}${(seq++).toString(36).toUpperCase()}`.slice(0, 10);
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
