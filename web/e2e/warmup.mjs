// 路由预热:把全部用例路由编译进当前 dev 进程,消除跑中按需编译触发的 Fast Refresh 打断导航(spec 0021)。
// 两个入口:CLI 直跑(npm run e2e)或被 global-setup.mjs import。
// 关键:必须在**同一个** dev 进程里预热并被测试复用——新起进程哪怕有 .next 缓存,
// 首次请求仍会进程内按需编译并向所有已连页面广播更新,照样打断并行用例的导航。
import { execSync, spawn } from "node:child_process";
import fs from "node:fs";
import { fileURLToPath } from "node:url";

const envFile = new URL("./.env", import.meta.url);

export function loadEnvFile() {
  if (!fs.existsSync(envFile)) return;
  for (const line of fs.readFileSync(envFile, "utf8").split("\n")) {
    const m = line.match(/^\s*([A-Z0-9_]+)\s*=\s*(.*?)\s*$/);
    if (m && process.env[m[1]] === undefined) process.env[m[1]] = m[2];
  }
}

const up = async (base) => {
  try {
    await fetch(`${base}/login`, { signal: AbortSignal.timeout(3000) });
    return true;
  } catch {
    return false;
  }
};

/** base 不可达则起 detached dev(frontPort)并等就绪;可达则复用现成服务。返回 dev 子进程或 null。 */
export async function ensureDev(base, frontPort) {
  if (await up(base)) return null;
  const dev = spawn("npx", ["next", "dev", "-p", frontPort], {
    detached: true, stdio: "ignore",
  });
  dev.unref();
  let ready = false;
  for (let i = 0; i < 60 && !ready; i++) {
    await new Promise((r) => setTimeout(r, 1000));
    ready = await up(base);
  }
  if (!ready) {
    console.error("dev server 未就绪");
    process.exit(1);
  }
  return dev;
}

/** 登录后 fetch 全部用例路由,触发本 dev 进程把它们编译进模块图。 */
export async function warmRoutes({ base, api, username, password }) {
  // 用户可能不存在(清库后):先 bootstrap(409=已存在),再登录
  await fetch(`${api}/api/auth/bootstrap`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ username, password }),
  }).catch(() => {});
  const login = await fetch(`${api}/api/auth/login`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ username, password }),
  });
  if (!login.ok) {
    console.error(
      `后端登录失败 ${login.status}——确认后端已以 test profile 运行:\n  gradle :api:bootRun --args='--spring.profiles.active=test'`,
    );
    process.exit(1);
  }
  const { token } = await login.json();
  const H = { Authorization: `Bearer ${token}`, "Content-Type": "application/json" };
  await fetch(`${api}/api/projects`, {
    method: "POST", headers: H, body: JSON.stringify({ key: "WARMUP1", name: "warmup" }),
  }).catch(() => {});
  const item = await fetch(`${api}/api/projects/WARMUP1/items`, {
    method: "POST", headers: H, body: JSON.stringify({ title: "warmup" }),
  }).then((r) => r.json()).catch(() => ({ itemId: "" }));
  const routes = item.itemId
    ? [...ROUTES.slice(0, 11), `/p/WARMUP1/i/${item.itemId}`, ...ROUTES.slice(11)]
    : ROUTES;
  for (const r of routes) await fetch(base + r, { headers: H }).catch(() => {});
  console.log(`预热完成:${routes.length} 条路由`);
}

const ROUTES = [
  "/", "/login", "/notifications", "/recycle-bin", "/capabilities",
  "/attributes", "/members", "/p/WARMUP1", "/p/WARMUP1/backlog",
  "/p/WARMUP1/gantt", "/p/WARMUP1/table",
  "/p/WARMUP1/sprint/00000000-0000-0000-0000-000000000000",
];

// CLI 直跑入口(npm run e2e;dev 保留运行供 webServer 复用)
const self = fileURLToPath(import.meta.url);
if (process.argv[1] && fs.existsSync(process.argv[1]) && fs.realpathSync(process.argv[1]) === self) {
  loadEnvFile();
  const frontPort = process.env.E2E_FRONT_PORT ?? "3000";
  const base = process.env.E2E_BASE_URL ?? `http://localhost:${frontPort}`;
  const dev = await ensureDev(base, frontPort);
  await warmRoutes({
    base,
    api: process.env.E2E_API_URL ?? "http://localhost:8080",
    username: process.env.E2E_USERNAME ?? "me",
    password: process.env.E2E_PASSWORD ?? "secret",
  });
  if (dev) console.log(`dev server(${dev.pid})保留运行,供 Playwright webServer 复用`);
}
