// 本地 e2e 预热:起 dev(若无)、等就绪、登录并编译全部用例路由后退出(dev 保留给 Playwright 复用)
import { spawn } from "node:child_process";
import fs from "node:fs";
import { fileURLToPath } from "node:url";

const base = process.env.E2E_BASE_URL ?? "http://localhost:3000";
const api = process.env.E2E_API_URL ?? "http://localhost:8080";
const envFile = new URL("./.env", import.meta.url);
if (fs.existsSync(envFile)) {
  for (const line of fs.readFileSync(envFile, "utf8").split("\n")) {
    const m = line.match(/^\s*([A-Z0-9_]+)\s*=\s*(.*?)\s*$/);
    if (m && process.env[m[1]] === undefined) process.env[m[1]] = m[2];
  }
}

const up = async () => {
  try {
    await fetch(base + "/login");
    return true;
  } catch {
    return false;
  }
};

let dev = null;
if (!(await up())) {
  dev = spawn("npm", ["run", "dev"], { shell: true, stdio: "ignore" });
  let up2 = false;
  for (let i = 0; i < 60 && !up2; i++) {
    await new Promise((r) => setTimeout(r, 1000));
    up2 = await up();
  }
  if (!up2) {
    console.error("dev server 未就绪");
    process.exit(1);
  }
}

const login = await fetch(`${api}/api/auth/login`, {
  method: "POST",
  headers: { "Content-Type": "application/json" },
  body: JSON.stringify({ username: process.env.E2E_USERNAME, password: process.env.E2E_PASSWORD }),
});
if (!login.ok) {
  console.error(`后端登录失败 ${login.status}——确认后端已以 test profile 运行:\n  gradle :api:bootRun --args='--spring.profiles.active=test'`);
  process.exit(1);
}
const { token } = await login.json();
const H = { Authorization: `Bearer ${token}`, "Content-Type": "application/json" };
await fetch(`${api}/api/projects`, { method: "POST", headers: H, body: JSON.stringify({ key: "WARMUP1", name: "warmup" }) }).catch(() => {});
const item = await fetch(`${api}/api/projects/WARMUP1/items`, { method: "POST", headers: H, body: JSON.stringify({ title: "warmup" }) })
  .then((r) => r.json())
  .catch(() => ({ itemId: "" }));
const routes = [
  "/",
  "/login",
  "/notifications",
  "/recycle-bin",
  "/capabilities",
  "/attributes",
  "/members",
  "/p/WARMUP1",
  "/p/WARMUP1/backlog",
  "/p/WARMUP1/gantt",
  "/p/WARMUP1/table",
  `/p/WARMUP1/i/${item.itemId}`,
  "/p/WARMUP1/sprint/00000000-0000-0000-0000-000000000000",
];
for (const r of routes) await fetch(base + r, { headers: H }).catch(() => {});
console.log(`预热完成:${routes.length} 条路由;dev server 保留运行`);
