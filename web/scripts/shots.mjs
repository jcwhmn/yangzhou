// 全页截图走查工具(spec 0017 TC1/TC2):不代管服务,前置 = 8080(test profile)+ dev server 已起。
// 用法:node scripts/shots.mjs(背景已定稿,单版输出)
// 输出:../.scratch/shots/<variant>/<page>.png(.scratch 已 gitignore)
import { chromium } from "playwright";
import { existsSync, mkdirSync, readFileSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";
import { randomUUID } from "node:crypto";

const webRoot = join(dirname(fileURLToPath(import.meta.url)), "..");
const repoRoot = join(webRoot, "..");

// 轻量读 web/e2e/.env(与 playwright 同源凭证;缺省兜底)
const env = {};
const envPath = join(webRoot, "e2e", ".env");
if (existsSync(envPath)) {
  for (const line of readFileSync(envPath, "utf8").split("\n")) {
    const m = line.match(/^([A-Z_]+)=(.*)$/);
    if (m) env[m[1]] = m[2].trim();
  }
}
const base = process.env.E2E_BASE_URL || env.E2E_BASE_URL || "http://localhost:3000";
const apiURL = process.env.E2E_API_URL || env.E2E_API_URL || "http://localhost:8080";
const username = env.E2E_USERNAME || "me";
const password = env.E2E_PASSWORD || "";

async function apiToken() {
  const boot = await fetch(`${apiURL}/api/auth/bootstrap`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ username, password }),
  });
  if (!boot.ok && boot.status !== 409) throw new Error(`bootstrap ${boot.status}`);
  const login = await fetch(`${apiURL}/api/auth/login`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ username, password }),
  });
  if (!login.ok) throw new Error(`login ${login.status}`);
  return (await login.json()).token;
}

function apiFactory(token) {
  return async function api(method, path, body) {
    const res = await fetch(`${apiURL}${path}`, {
      method,
      headers: { "Content-Type": "application/json", Authorization: `Bearer ${token}` },
      body: body === undefined ? undefined : JSON.stringify(body),
    });
    if (!res.ok) throw new Error(`${method} ${path}: ${res.status}`);
    return res.status === 204 ? undefined : res.json();
  };
}

const token = await apiToken();
const api = apiFactory(token);

// 种子数据:项目 + 若干 item + sprint + 指派(走查要有内容可看)
const key = randomUUID().replace(/-/g, "").slice(0, 6).toUpperCase();
await api("POST", "/api/projects", { key, name: `走查 ${key}` });
const titles = ["架构决策落地", "看板列定义", "甘特图数据对齐", "导入导出 round-trip", "通知去重策略"];
const itemIds = [];
for (const t of titles) {
  const it = await api("POST", `/api/projects/${key}/items`, { title: t });
  itemIds.push(it.itemId);
}
const sprint = await api("POST", `/api/projects/${key}/sprints`, { name: "Sprint 1", startDate: null, endDate: null });
const members = await api("GET", "/api/members");
const me = members.find((m) => !m.virtual);
if (me) await api("PATCH", `/api/items/${itemIds[0]}/assignee`, { assigneeItemId: me.memberId });

const pages = [
  ["home", "/"],
  ["board", `/p/${key}`],
  ["overview", `/p/${key}/overview`],
  ["backlog", `/p/${key}/backlog`],
  ["table", `/p/${key}/table`],
  ["gantt", `/p/${key}/gantt`],
  ["time", `/p/${key}/time`],
  ["sprint", `/p/${key}/sprint/${sprint.sprintId}`],
  ["item", `/p/${key}/i/${itemIds[0]}`],
  ["attributes", "/attributes"],
  ["capabilities", "/capabilities"],
  ["members", "/members"],
  ["notifications", "/notifications"],
  ["search", "/search"],
  ["standup", "/standup"],
  ["recycle-bin", "/recycle-bin"],
];

const outDir = join(repoRoot, ".scratch", "shots");
mkdirSync(outDir, { recursive: true });

const browser = await chromium.launch();
const ctx = await browser.newContext({ viewport: { width: 1440, height: 900 } });
await ctx.addInitScript((t) => localStorage.setItem("yz-token", t), token);
const page = await ctx.newPage();

for (const [name, path] of pages) {
  await page.goto(base + path, { waitUntil: "domcontentloaded", timeout: 15000 });
  await page.waitForTimeout(1200); // hydration + 数据拉取 + MUI 过渡收尾
  await page.screenshot({ path: join(outDir, `${name}.png`), fullPage: true });
  console.log(`${name}.png ok`);
}

// login 页 = 登出态
const anon = await browser.newContext({ viewport: { width: 1440, height: 900 } });
const p2 = await anon.newPage();
await p2.goto(base + "/login", { waitUntil: "domcontentloaded", timeout: 15000 });
await p2.waitForTimeout(800);
await p2.screenshot({ path: join(outDir, "login.png"), fullPage: true });
console.log("login.png ok (logged out)");

await browser.close();
console.log(`done: ${pages.length + 1} shots → ${outDir}`);
