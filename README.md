# yangzhou

开源项目管理系统——以**匹配引擎**为核心:单人模式输出技能差距与可行性分析("这项目我做得起吗?该提升什么?"),团队模式输出分配建议。

Kotlin · Spring Boot 4 · PostgreSQL · Next.js · CLI-first · MIT

状态:V1 已交付(引擎/API/Workflow/CLI/导入导出/Web);V2 团队分配进行中。

## CLI

```bash
script/yz.cmd members list        # Windows(首次自动构建 fat-jar)
script/yz members list            # Linux/macOS
script/yz feasibility CHE         # 示例:项目可行性/技能差距
```

全部命令:`script/yz`(无参数)看用法;详见 `backend/cli/README.md`。文档:`docs/`;开发票:Linear `JCW-77`。
