import { defineConfig } from "@playwright/test";
import { config } from "./e2e/config";

/**
 * E2E:并行、按用例隔离(project fixture 唯一 key,数据走 API 预置)。
 * 前置:本机 Postgres(共享 compose)+ 后端 test profile:
 *   gradle :api:bootRun --args='--spring.profiles.active=test'
 * 前端 prod server(next build && next start)由 webServer 拉起(已在跑则复用);
 * 构建走独立目录 .next-e2e(NEXT_DIST_DIR),不踩常驻 dev 的 .next。
 * 凭证/地址见 e2e/.env(模板 .env.example)。
 */
export default defineConfig({
  testDir: "./e2e",
  globalSetup: "./e2e/global-setup.mjs",
  timeout: 30_000,
  retries: process.env.CI ? 1 : 0,
  fullyParallel: true,
  // YPJ-5:auth 断言依赖 me.last_project_key(用例池唯一共享可变态,任何 /p mount 都会覆盖它),
  // auth 独占串行先行;其余用例 dependencies 强制在 auth 全部完成后才跑,杜绝写入竞态
  projects: [
    { name: "auth", testMatch: /auth\.spec\.ts/, fullyParallel: false },
    { name: "e2e", testIgnore: /auth\.spec\.ts/, dependencies: ["auth"] },
  ],
  workers: process.env.CI ? 2 : 4,
  use: {
    baseURL: config.baseURL,
    locale: "zh-CN",
    screenshot: "only-on-failure",
    trace: "retain-on-failure",
  },
  webServer: {
    // 双 profile 并存(YPJ-3):日常 3000→8081;E2E 可用 E2E_FRONT_PORT 另起临时前端(配 E2E_BASE_URL + BACKEND_URL 指 test profile)
    command: `npx next build && npx next start -p ${process.env.E2E_FRONT_PORT ?? 3000}`,
    env: { ...process.env, NEXT_DIST_DIR: ".next-e2e" },
    url: config.baseURL,
    reuseExistingServer: !process.env.CI,
    timeout: 300_000,
  },
});
