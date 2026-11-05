# Docker Compose 运行指南(三 profile 常驻容器)

> YPJ-118(spec 0087)落地形态。真相源:`script/app-infra.ps1` + `infra/compose/docker-compose.{prod,dev,test}.yaml`;本文是操作手册,与其不一致时以脚本/compose 注释为准。

## 一、总体格局

| 环境 | compose 文件 | compose project | web | api | 数据库 | 用途 |
|---|---|---|---|---|---|---|
| prod | `docker-compose.prod.yaml` | `yz-prod` | localhost:3000 | localhost:8080 | `yangzhou` | PM 日常(dogfood,真实数据) |
| dev | `docker-compose.dev.yaml` | `yz-dev` | localhost:3001 | localhost:8081 | `yangzhou_dev` | scratch 调试(可整库丢弃) |
| test | `docker-compose.test.yaml` | `yz-test` | —(E2E 由 Playwright 拉起) | localhost:8082 | `yangzhou_test` | E2E/调试(E2E globalSetup 会清库) |

镜像只有 2 个、三环境共用:`yangzhou-api:local`(JRE + app.jar)与 `yangzhou-web:local`(next standalone)。profile/数据源全靠运行期 env 注入,镜像不带环境差异。

**外部共享依赖,不在本项目 compose 定义**(刻意,定义重复即双真相源):

- **postgres**:多项目共享实例,提供方 `F:/code/docker-compose/postgresql`,localhost:5432。容器内经 `host.docker.internal` 访问(compose 里 `extra_hosts: host-gateway`)。
- **rustfs**(S3 兼容,附件存储):项目独用,定义在 `infra/compose/docker-compose.yaml`(9000/9001),由 `script/dev-infra.ps1` 管理——它不属于任何 app profile,但 api 三环境都依赖它的 9000。

compose 文件顶部 `name:` 三文件互异——同名 project 会互相顶替容器(实测踩坑),新增环境时勿复制忘改。

## 二、前置(一次性)

1. **postgres**:启动外部共享实例(`F:/code/docker-compose/postgresql`),确认库 `yangzhou` / `yangzhou_dev` / `yangzhou_test` 已建。
2. **rustfs**:`.\script\dev-infra.ps1 up`(rustfs 起 9000/9001;`status`/`down` 同理)。
3. **prod 专用密钥**:`infra/compose/prod.env`(gitignored)写入 `YANGZHOU_JWT_SECRET=...`——护栏拒绝默认密钥,缺文件 prod 起不来。dev/test 无需。

## 三、日常命令(app-infra)

```powershell
.\script\app-infra.ps1 <build|rebuild|up|down|status> <prod|dev|test>
```

- **build**:① 宿主 `gradle :api:bootJar`(容器内不跑 gradle,快)→ ② 产物复制为 `backend/app.jar` → ③ `docker compose build`(web 在镜像内两阶段 `next build`)。
- **rebuild** = build + up 一条命令(改码后最常用)。
- **up**:`up -d` 起本组容器(起前有端口占用预检,被占会报错停下——按端口找 PID 处理,**严禁 `taskkill /IM node.exe`**)。
- **down**:`compose down`,**不带 -v**(本组 compose 无卷,数据全在外部 postgres,停了不丢)。
- **status**:`compose ps`。

典型流程:

```powershell
# 改了后端代码,重建 dev(3001/8081)
.\script\app-infra.ps1 rebuild dev

# 只重启 test(镜像已有切片更新时)
.\script\app-infra.ps1 up test
```

**注意**:三环境共用同一对镜像 tag。`build` 会覆盖 `yangzhou-api:local`/`yangzhou-web:local`——已在跑的环境不受影响(容器用旧镜像),但下次 `up/rebuild` 该环境会用新镜像。哪个环境要吃到新代码就 rebuild 哪个。

## 四、自动化测试接线

- **e2e**(web/ 下,test 容器 + Playwright 自拉前端):

  ```powershell
  .\script\app-infra.ps1 rebuild test   # 有后端改动时先重建
  cd web
  E2E_FRONT_PORT=3002 E2E_BASE_URL=http://localhost:3002 E2E_API_URL=http://127.0.0.1:8082 BACKEND_URL=http://127.0.0.1:8082 npx playwright test
  ```

  `BACKEND_URL` 必须 8082——指 8080 会打 prod 容器、污染 PM 库。手动清测试库:`npm run db:reset`。
- **集成测试**(gradle):不用本 compose——Testcontainers 自起一次性容器,只需 Docker 在跑。

## 五、调试改码(不走容器)

IDE / `gradle bootRun` 直跑更快的反馈环:

- dev profile 数据源默认已指 `yangzhou_dev`;**test profile 必须加 `--server.port=8082`**,且 test 容器已停(8080/8081 归常驻容器,test 容器占 8082,错峰会打到库错)。
- 空库首启用 `POST /api/auth/bootstrap` 引导(bootstrap 幂等,已有账号 409 属预期)。

## 六、常见坑

- **端口被占**:`netstat -ano | findstr :3000` → 按 PID 杀;docker infra 进程(com.docker.backend/wslrelay)持有的端口属自家容器,脚本会放行。
- **prod 起不来报 JWT**:`infra/compose/prod.env` 缺失或没配 `YANGZHOU_JWT_SECRET`。
- **容器连不上库**:postgres 未起 / 未监听 5432;容器侧走 `host.docker.internal`,不是 localhost。
- **web 的 BACKEND_URL 是 build 期固化**(next standalone 烤进 routes-manifest):compose 内恒为 `http://api:8080`(容器网络内),三环境同值;改它需重建 web 镜像。
- **数据备份**:手工期 `pg_dump`(自动化另票);`down` 不动库,但**别动外部 postgres 容器**。
