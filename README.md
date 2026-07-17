# mmdb-editor-java

MaxMind DB (mmdb) 编辑器与 awdb 转换器。MaxMind DB editor and awdb-to-mmdb converter.

- **主价值 / Primary**: 读取、遍历、编辑、写出 mmdb（自研 writer/reader/editor）
- **次价值 / Secondary**: awdb (Gen-B) → mmdb 转换
- **产物 / Artifacts**: lib jars (Maven Central) + 单一二进制 `mmdb-edit`（CLI 子命令 + `serve` Web UI）

详细计划见 [docs/plan.md](docs/plan.md)。

## 模块 / Modules

| 模块 | 职责 |
|---|---|
| `mmdb-editor-core` | mmdb 读/遍历/编辑/写 纯库 |
| `mmdb-converter-awdb` | awdb→mmdb 转换纯库 |
| `mmdb-editor-app` | Quarkus 应用：picocli CLI + REST + Vue3/Element Plus Web UI |

## 构建 / Build

```bash
mvn verify                              # jar + tests
mvn package -Dnative -DskipTests -pl mmdb-editor-app -am   # native 二进制（需 GraalVM CE 21）
```
