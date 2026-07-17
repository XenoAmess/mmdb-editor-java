# mmdb-editor-java

MaxMind DB (mmdb) 编辑器与 awdb 转换器。MaxMind DB editor and awdb-to-mmdb converter.

- **主价值 / Primary**: 读取、遍历、编辑、写出 mmdb（自研 writer/reader/editor）
- **次价值 / Secondary**: awdb (Gen-B) → mmdb 转换（全自研，布局映射含 4_6 重 rooting + MaxMind 惯例别名）
- **产物 / Artifacts**: lib jars (Maven Central) + 单一二进制 `mmdb-edit`（CLI 子命令 + `serve` Web UI）

## 模块 / Modules

| 模块 | 职责 |
|---|---|
| `mmdb-editor-core` | mmdb 读/遍历/编辑/写 纯库 |
| `mmdb-converter-awdb` | awdb→mmdb 转换纯库（依赖 Central 的 `com.xenoamess:awdb-java`） |
| `mmdb-editor-app` | Quarkus 应用：picocli CLI + REST + Vue3/Element Plus Web UI |

## 单二进制用法 / Single binary

```bash
mmdb-edit info <file.mmdb>                  # 打印 metadata
mmdb-edit lookup <file.mmdb> <ip>           # 查询记录
mmdb-edit dump <file.mmdb> [--limit N]      # 遍历输出
mmdb-edit set <src> <dst> <prefix> <json>   # 插入/覆盖（如 1.2.3.0/24）
mmdb-edit delete <src> <dst> <prefix>       # 删除（空间向上合并）
mmdb-edit convert <in.awdb> <out.mmdb>      # awdb (Gen-B) 转换
mmdb-edit serve [--port 8080]               # 启动 Web UI（Vue SPA + REST API）
```

## 构建 / Build

```bash
mvn verify                                             # jar + tests（前端经 Quinoa+pnpm 自动构建）
mvn package -Dnative -DskipTests -pl mmdb-editor-app -am   # native 二进制（需 GraalVM CE 21）
```

详细计划见 [docs/plan.md](docs/plan.md)。
