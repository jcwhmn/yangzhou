// Playwright globalTeardown:杀掉 globalSetup 起的预热 dev(按 pid 文件;无文件=复用的现成服务,不动)。
import fs from "node:fs";
import os from "node:os";
import { execSync } from "node:child_process";

const pidFile = `${os.tmpdir().replace(/\/+$/, "")}/yangzhou-e2e-dev.pid`;

export default async function globalTeardown() {
  if (process.env.CI) return;
  if (!fs.existsSync(pidFile)) return;
  const pid = Number(fs.readFileSync(pidFile, "utf8").trim());
  try {
    if (process.platform === "win32") {
      execSync(`taskkill /pid ${pid} /T /F`, { stdio: "ignore" });
    } else {
      process.kill(pid, "SIGTERM");
    }
    console.log(`globalTeardown:预热 dev(pid ${pid})已关闭`);
  } catch {
    /* 进程已退出 */
  } finally {
    fs.rmSync(pidFile, { force: true });
  }
}
