# 0004 — Monorepo 与工具链

后端(Gradle 多模块:domain / api / persistence / cli)、前端(Next.js)与文档同仓:单人全职开发,跨端改动常同时触及契约两端,单仓原子提交最省。工具链:**JDK 25(LTS,本机现行)**、Gradle Kotlin DSL、Flyway 做 Postgres migration。持久层:**Spring Data JDBC**(禁 JPA/Hibernate/Exposed)——沿用 chess 项目实战约定;本机 Postgres 由共享 compose 提供(`F:/code/docker-compose/postgresql`),不建项目本地 compose。契约流:api 模块导出 OpenAPI,cli/web 从契约生成 client——API-first 的物理形态(见 spec 0001 Implementation Decisions)。
