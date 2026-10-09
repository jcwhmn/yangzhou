// Playwright globalSetup:跑前自动 ①清测试库 ②预热路由(spec 0021)。
// CI 短路:service 库每次全新,e2e.yml 自带预热步骤。
import fs from "node:fs";
import { execSync } from "node:child_process";
import { loadEnvFile, prewarm } from "./warmup.mjs";
import { resetDb } from "./reset-db.mjs";

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

// ② 预热全部用例路由:base 可达直接预热现成服务;不可达起临时 dev,预热完杀掉,
//    webServer 随后以热 .next 缓存复用,跑中零按需编译 → 零 Fast Refresh 打断
await prewarm({
  base,
  api,
  username: process.env.E2E_USERNAME ?? "me",
  password: process.env.E2E_PASSWORD ?? "secret",
  frontPort,
  killDevOnDone: true,
});
