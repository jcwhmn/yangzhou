// Playwright globalSetup:跑前自动 ①清测试库 ②起 dev 并预热路由(spec 0021)。
// CI 短路:service 库每次全新,e2e.yml 自带预热步骤。
//
// 生命周期:这里 spawn 的 dev **不杀**——保留运行让 webServer reuseExistingServer 复用
// 同一进程(进程内路由全热,跑中零按需编译 → 零 Fast Refresh 广播);
// global-teardown.mjs 按 pid 文件收尾。用户自己起过的 dev(base 可达)不 pid 不杀。
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import { execSync } from "node:child_process";
import { loadEnvFile, ensureDev, warmRoutes } from "./warmup.mjs";
import { resetDb } from "./reset-db.mjs";

export default async function globalSetup() {
  if (process.env.CI) {
    console.log("CI:globalSetup 短路(service 库全新,e2e.yml 自带预热)");
    process.exit(0);
  }

  loadEnvFile();
  const frontPort = process.env.E2E_FRONT_PORT ?? "3000";
  const base = process.env.E2E_BASE_URL ?? `http://localhost:${frontPort}`;
  const api = process.env.E2E_API_URL ?? "http://localhost:8080";

  // ① 清测试库(残渣治理;手动命令 = npm run db:reset)
  const sql = fs.readFileSync(
    new URL("../../backend/script/reset-test-db.sql", import.meta.url),
    "utf8",
  );
  execSync(
    `docker exec -i ${process.env.POSTGRES_CONTAINER ?? "postgres"} psql -U postgres -d yangzhou_test -v ON_ERROR_STOP=1`,
    { input: sql, stdio: ["pipe", "ignore", "inherit"] },
  );
  console.log("globalSetup:测试库已清空");

  // ② 起(或复用)dev 进程,预热全部用例路由
  const dev = await ensureDev(base, frontPort);
  await warmRoutes({
    base,
    api,
    username: process.env.E2E_USERNAME ?? "me",
    password: process.env.E2E_PASSWORD ?? "secret",
  });
  if (dev) {
    // 路径与 web/e2e/global-teardown.mjs 保持一致
    fs.writeFileSync(path.join(os.tmpdir(), "yangzhou-e2e-dev.pid"), String(dev.pid));
    console.log(`globalSetup:预热 dev(pid ${dev.pid})保留运行,供 webServer 复用`);
  } else {
    console.log("globalSetup:复用现成 dev server 预热完成(不接管生命周期)");
  }
}
