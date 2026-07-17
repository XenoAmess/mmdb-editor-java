# mmdb-editor-java 总体计划

> 状态：已锁定（2026-07-17 多轮拍板）。施工依据文档。

## 定位

- **主价值**：读取 & 编辑 mmdb（MaxMind DB 格式，规范：MaxMind-DB-spec v2.0）
- **次价值**：读取其他格式（首发 awdb Gen-B）转换为 mmdb 输出
- 产物：各 lib jar 发布 Maven Central；应用产出**单一 native 二进制 `mmdb-edit`**（CLI + Web 合一），同时发布 jar 与二进制命令行

## 关键背景

- AWDB 两代格式分裂已证实，官方现行文件 = **Gen-B**（文件头 2 字节 meta 长度 + meta JSON；
  叶子公式 `baseOffset + nodeIndex - nodeCount - 10`）。Gen-A（mmdb 衍生，尾标
  `\xAB\xCD\xEFipplus360.com`）的整个 Go/Python/C 生态对现行文件已失效。
- 官方无 Java mmdb writer（仅 Go/Perl），故自研；官方 maxmind-db reader 仅作 test oracle。
- 真实 .awdb 在内网不可取出 → 全部验证基于合成 fixture；awdb 读取复用
  `com.xenoamess:awdb-java`（Central），先补 walk 遍历 API 并发 2.0.1-rc2。
- 禁第三方转换工具（ips 等），转换能力 100% 自研。

## 坐标与基线

| 项 | 值 |
|---|---|
| repo | XenoAmess/mmdb-editor-java（public，remote=origin） |
| groupId | `com.xenoamess.mmdb_editor` |
| version | 0.1.0-SNAPSHOT |
| Java | release 21；native 用 GraalVM CE 21，走 Quarkus |
| 许可证 | Apache-2.0 |

## 模块结构（父 pom + 3 模块）

| 模块 artifactId | packaging | 职责 | 产物 |
|---|---|---|---|
| `mmdb-editor-parent` | pom | 反应堆根：quarkus-bom、依赖/插件管理、native·release profile | — |
| `mmdb-editor-core` | jar | 纯库：mmdb 读/遍历/编辑/写 | jar → Central |
| `mmdb-converter-awdb` | jar | 纯库：awdb→mmdb（依赖 core + awdb-java rc2），无独立 CLI | jar → Central |
| `mmdb-editor-app` | jar | Quarkus 应用：picocli 子命令树 + REST + Vue SPA，唯一 CLI 宿主 | jar + native `mmdb-edit` |

依赖图：app → {core, converter-awdb} → core。无"app 兼 lib"妥协结构。

## 单二进制设计（`mmdb-edit`）

```
mmdb-edit info <file>                  打印 metadata 后退出
mmdb-edit lookup <file> <ip>           查询输出 JSON 后退出
mmdb-edit dump <file> [--limit]        遍历输出后退出
mmdb-edit set/delete <file> <prefix>   编辑后退出
mmdb-edit convert <in.awdb> <out.mmdb> 转换后退出（能力来自 converter-awdb）
mmdb-edit serve [--port 8080]          启动 Web（Vue SPA + REST API），常驻
无参数                                  打印帮助
```

机制：`application.properties` 设 `quarkus.http.host-enabled=false`；`main()` 在
`Quarkus.run` 前检测 `args[0]=="serve"` 则置 true；serve 子命令 `Quarkus.waitForExit()`。

## 前端（Vue 3 + Element Plus + pnpm）

- 集成：`io.quarkiverse.quinoa:quarkus-quinoa`，`quarkus.quinoa.package-manager=pnpm`；
  mvn 构建自动 `pnpm install && pnpm build`，产物随 jar/native 打包；dev 走 Vite 代理热更
- 目录：`mmdb-editor-app/src/main/frontend`（Vite + Vue3 + Element Plus + vue-router，人工提交脚手架）
- 页面 ⇄ REST：
  - 文件管理（上传/打开/下载）⇄ `POST /api/files/{open,upload}`、`GET /api/files/{id}/download`
  - IP 查询 ⇄ `GET /api/files/{id}/lookup?ip=`
  - 浏览（el-table 分页 + el-tree 前缀树）⇄ `GET /api/files/{id}/records`
  - 编辑（类型感知表单）⇄ `PUT/DELETE /api/files/{id}/records/{prefix}`
  - 转换（上传 awdb→进度→下载）⇄ `POST /api/convert`、`GET /api/convert/{jobId}[/download]`
  - 保存 ⇄ `POST /api/files/{id}/save`（临时工作副本，原子替换）
- CI：setup-node + corepack pnpm + pnpm store 缓存（或 Quinoa 自动安装 node，施工时定）

## mmdb writer/reader 要点（core）

- DataEncoder：控制字节（高 3 位类型/扩展类型；低 5 位尺寸 29/30/31 三档）、15 类型、
  pointer `001SSVVV` 四档；值模型 String/byte[]/Double/Float/Integer/Long/BigInteger/Boolean/
  Uint16/Uint32/Map/List
- Trie：插入 + 同值子节点合并压缩 + DAG 别名（`::ffff:0/96` → v4 子树根，MaxMind 惯例）
- TreeSerializer：24/28/32 位 record 打包（28 位中间字节共享配金样）
- ValueDeduper：值→偏移缓存，重复值写 pointer（禁 pointer→pointer）
- MetadataEncoder：尾标 `\xAB\xCD\xEFMaxMind.com` + MAP
- MmdbReader：自研读取 + 全量遍历（官方 Java reader 无公开迭代器）
- MmdbEditor：`transform(src, dst, fn)` 流式改写，大文件不进堆

## 转换布局映射（最高风险点）

| awdb ip_version | mmdb 输出 |
|---|---|
| `"4"` | ip_version=4，32 位树直搬 |
| `"6"` | ip_version=6，128 位树直搬 |
| `"4_6"` | ip_version=6；`::ffff:0/96` 子树**重 rooting 到 `::/96`**，`::ffff:0:0/96` 写别名指针 |

JsonNode→mmdb 值：TextNode→utf8，LongNode→uint64，IntNode→int32，DoubleNode→double，
FloatNode→float，BooleanNode→boolean，ArrayNode→array，ObjectNode→map。

## 验证策略（全合成）

- spec 内嵌示例金样（控制字节/pointer/28 位打包）
- oracle 对拍：test-scope `com.maxmind.db:maxmind-db:4.1.0`（Java 17+，新仓库基线 21 可用）
- awdb 5 fixture 端到端：转换 → oracle 读取 ↔ awdb-java 读取，逐 IP 对拍 + 全量前缀集合相等

## 执行序列

- A. awdb-java：清理（oracle 依赖/旧计划文档/未提交 writer 文件）→ walk 遍历 API + 测试 → 发 2.0.1-rc2
- B1. 骨架：parent + 3 模块 + LICENSE/README/AGENTS.md + CI（JDK 21/25 + node/pnpm +
  GraalVM CE 21 native job，native 不进 PR 必需检查）+ dependabot 全套管线
- B2. core：writer 全家 + oracle 对拍 + MmdbReader + MmdbEditor
- B3. converter-awdb：布局映射 + fixture 端到端对拍
- B4. app：picocli 子命令树 → REST API → Quinoa+Vue SPA → serve 合流 → REST Assured 测试
- B5. native 单二进制全链路验证 + release 流水线（jars→Central 复用 awdb-java 的
  GPG/secrets 方案；二进制 → GH Release assets）+ 文档收尾

## 边界声明

全部验证基于合成 fixture（以 awdb-java 实现的 Gen-B 理解为 ground truth）；
真实文件验证待内网运行 `mmdb-edit`/`convert` 回执。
