# spec 0086 V44 YPJ-117 dev profile:本地开发 scratch 环境(8082 / yangzhou_dev)

票:YPJ-117。语义已随立票拍板(2026-10-29),本 spec 为其转录 + 实现细节。

## 一、需求

### 范围
- 本地开发需要可丢弃数据的 scratch 环境:**不动 PM 真实库**(`yangzhou`,8081 default profile)、**不被 e2e 清库**(`yangzhou_test`,8080 test profile——globalSetup 全清)。
- `application-dev.yml`:仅覆盖 datasource url → `jdbc:postgresql://localhost:5432/yangzhou_dev`,其余全部继承默认(Flyway enabled、JWT、github poll、attachment)。
- 共享 postgres 建 `yangzhou_dev` 库;首启 Flyway 自动建表(41 个迁移跑一遍)。
- 启动约定:`gradle :api:bootRun --args='--spring.profiles.active=dev --server.port=8082'`。
- AGENTS.md 约定更新:双 profile 并存 → 三 profile 并存(8080 test / 8081 PM default / 8082 dev)。

### 非目标
- 种子数据脚本(空库首启后用 `POST /api/auth/bootstrap` 引导管理员 + `yz items create` 手造)。
- attachment 独立 bucket(dev 暂共用 rustfs `yangzhou` bucket;需隔离时另票)。
- 护栏/启动横幅(超需要)。
- yz CLI 无 profile 概念,session 单槽照旧(测试 dev 后须恢复 PM 会话,操作纪律见手工用例 5)。

## 二、详细设计

改 2 文件 + 1 次性建库:

1. `backend/api/src/main/resources/application-dev.yml`(新):
```yaml
spring:
  datasource:
    url: jdbc:postgresql://localhost:5432/yangzhou_dev
```
   与 application-test.yml 完全同构(该文件即此形状)。
2. `AGENTS.md`:「双 profile 并存」段改「三 profile 并存」,加 8082 dev 行,其余文字保留。
3. 一次性:`CREATE DATABASE yangzhou_dev`(docker exec 进共享 postgres 容器 psql;幂等 `IF NOT EXISTS` 不可用于 CREATE DATABASE,用查重后建)。

否决的备选:
- dev 占 8080——与 test/e2e 冲突(CI 与本地 e2e 都假定 8080=test);8082 三者错开可同时跑。
- 复制全量 yml——继承即可,test profile 先例就是单覆盖。
- seed 脚本——yz CLI 造数据已够快,加脚本反成维护负担。
- dev 也要求真实 JWT 密钥——dev 库硬编码指向 yangzhou_dev,触不到真实数据,与 test 同威胁模型;实现中发现 JwtService 启动护栏(spec 0051)只豁免 test,扩为 test/dev 豁免(JwtService.kt 一行 + 注释)。

## 三、手工测试用例

1. dev 起得来:8082 bootRun,`GET /api/version` 200,Flyway 41 迁移全绿。
2. 隔离:dev 里 bootstrap 管理员 + 造数据后,PM(8081)与 test(8080)实例数据不受影响;`yangzhou_dev` 库独立存在。
3. yz 指向 dev:`yz login --server http://localhost:8082` 后 items/sprints 命令读写 dev 库。
4. **115 命令在 dev 实测**(sprints move / items assign 五条用例,spec 0085 §三)。
5. 会话恢复:测完 session.json 恢复指向 8081(备份/回填),PM 实例操作不受影响。

## As-built(完工后补)
