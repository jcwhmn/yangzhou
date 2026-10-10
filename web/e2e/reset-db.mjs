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

const self = fileURLToPath(import.meta.url);
if (process.argv[1] && fs.existsSync(process.argv[1]) && fs.realpathSync(process.argv[1]) === self) {
  if (process.env.CI) {
    console.log("CI:跳过清库(service 库每次全新)");
    process.exit(0);
  }
  resetDb();
}
