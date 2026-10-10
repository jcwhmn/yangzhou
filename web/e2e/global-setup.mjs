// Playwright globalSetup:跑前自动清测试库(spec 0021)。
// CI 短路:service 库每次全新。
// As-built(v3 方案):前端跑 prod server(next build && next start),无 HMR/按需编译,
// Fast Refresh 广播类 flake 物理根除——预热机制(warmup.mjs / pid 收尾)已随 dev 模式删除。
// 实测顺序:webServer 先起、globalSetup 后跑;globalSetup 不再接触前端服务,顺序无关紧要。
import { resetDb } from "./reset-db.mjs";

export default async function globalSetup() {
  if (process.env.CI) {
    console.log("CI:globalSetup 短路(service 库全新)");
    return; // 勿 process.exit:会连整个测试运行一起退出
  }
  resetDb();
}
