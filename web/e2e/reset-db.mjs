// 测试库残渣清理:TRUNCATE yangzhou_test 全部业务表(spec 0021)
// CLI:npm run db:reset;也导出 resetDb() 供 global-setup.mjs 复用;CI 由调用方短路
import { execSync } from "node:child_process";
import fs from "node:fs";
import { fileURLToPath } from "node:url";

export function resetDb() {
  const container = process.env.POSTGRES_CONTAINER ?? "postgres";
  const sql = fs.readFileSync(
    new URL("../../backend/script/reset-test-db.sql", import.meta.url),
    "utf8",
  );
  execSync(
    `docker exec -i ${container} psql -U postgres -d yangzhou_test -v ON_ERROR_STOP=1`,
    { input: sql, stdio: ["pipe", "ignore", "inherit"] },
  );
  console.log(`测试库已清空(docker:${container} / yangzhou_test)`);
}

/** 清库后补种默认账号(api.http/e2e 共用 me/secret123;201/409 幂等)——否则清库后所有请求 401。 */
export async function ensureBootstrapUser() {
  const api = process.env.E2E_API_URL ?? "http://127.0.0.1:8082";
  const res = await fetch(`${api}/api/auth/bootstrap`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ username: "me", password: "secret123" }),
  }).catch(() => null);
  if (res && (res.status === 201 || res.status === 409)) {
    console.log(`默认账号已就绪(me/secret123,${res.status})`);
  } else {
    console.warn(
      `补种失败(status=${res ? res.status : "网络错误"})——api.http 需先手动跑 Bootstrap 段`,
    );
  }
}

const self = fileURLToPath(import.meta.url);
if (
  process.argv[1] &&
  fs.existsSync(process.argv[1]) &&
  fs.realpathSync(process.argv[1]) === self
) {
  if (process.env.CI) {
    console.log("CI:跳过清库(service 库每次全新)");
    process.exit(0);
  }
  resetDb();
  await ensureBootstrapUser();
}
