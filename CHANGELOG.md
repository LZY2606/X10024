<!--
Licensed to the Apache Software Foundation (ASF) under one or more
contributor license agreements.  See the NOTICE file distributed with
this work for additional information regarding copyright ownership.
The ASF licenses this file to You under the Apache License, Version 2.0
(the "License"); you may not use this file except in compliance with
the License.  You may obtain a copy of the License at

     https://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
-->

# Changelog

## Unreleased — 收敛 JDK 矩阵、JPMS 与发布包内容校验

### 背景与目标

在此变更之前，Commons CSV 的验证能力分散在多处且互不相通：

- CI（`.github/workflows/maven.yml`）直接调用 `mvn`，没有任何本地等价入口；
  开发者在本地运行的命令与 CI 实际执行的命令并不一致。
- JPMS 元数据由父 POM 的 `java-9-up` profile 通过 Moditect 隐式生成
  （`META-INF/versions/9/module-info.class`），但没有任何测试断言它存在、
  模块名正确，或能被一个真正的下游模块编译并加载。
- `japicmp` 基线对比（`commons.bc.version=1.14.1`）只在 `src/site/resources/profile.japicmp`
  标记存在时才绑定到 `verify`，而仓库里没有这个标记，因此默认构建从不校验
  公开 API 基线。
- `bin.xml`/`src.xml` 两个发布装配描述符没有任何“必需文件缺失即失败”的检查；
  装配插件在目录不存在时会静默产出缺件的压缩包。
- “测试跑空”无法被发现：Surefire 在没有测试匹配时退出码仍为 0，
  且会保留上一次运行的报告目录。

本次变更把这些分散的检查收敛成**同一条入口**：`verify.sh`。本地与 CI 都只
调用这个脚本，脚本对“跳过测试集 / 生成物为空 / 陈旧生成物 / 缺失的可选 JDK”
全部给出非零退出与可诊断的上下文。

### 变更清单

- 新增 `verify.sh`（POSIX `sh`，可在 Linux 与 macOS 上运行，`shellcheck` 干净）：
  - `all [JDK...]`：默认入口，依次执行矩阵 + `jpms` + `japicmp` + `packages`。
  - `matrix [JDK...]`：对每个 JDK 执行 `mvn clean package -DskipTests` 再
    `mvn test`；每个 JDK 都从 `clean` 开始，杜绝跨 JDK 复用生成物。
  - `build` / `test` / `jpms` / `japicmp` / `packages` / `list` 子命令，
    子命令失败均原样透传非零退出码。
  - JDK 发现顺序：`JDK_HOME_<v>` / `JAVA_HOME_<v>` → 仅当版本确实匹配时才
    采用 `JAVA_HOME` → Homebrew → `/usr/libexec/java_home` → SDKMAN/Jabba/
    asdf/常见 Linux 路径。缺失的 JDK **绝不**静默替换为当前 JDK：矩阵模式
    默认以退出码 3 失败并打印缺失版本；设置 `VERIFY_ALLOW_MISSING_JDK=1`
    时显式跳过并保留说明；CI 的每个作业只声明一个版本，缺失即失败。
  - 零收集/零用例守卫：解析 `target/surefire-reports/*.txt`，要求至少一个
    测试集且每集 `Tests run` 大于 0、`Failures`/`Errors` 为 0；调用 Maven 前
    写入 `target/.verify-test-started` 新鲜度标记，早于该标记的报告一律视为
    陈旧（覆盖“`-Dtest=NoSuchTest` 时 Surefire 保留旧报告且退出码为 0”的情形）。
  - 陈旧生成物守卫：主 jar 早于 `pom.xml`/`src/main`/`src/test` 中最新源文件
    即拒绝测试；`build`/`packages` 始终 `clean`。
  - `jpms`：JDK 9+ 上运行 `java --describe-module`，断言模块名为
    `org.apache.commons.csv` 且导出 `org.apache.commons.csv`，随后在临时目录
    中编译并运行一个 `requires org.apache.commons.csv;` 的真实下游模块；
    JDK 8 上退化为校验 `Automatic-Module-Name`（且不允许出现 module-info）。
  - `japicmp`：以 `-Pjapicmp verify` 对比已发布基线 1.14.1，并要求生成
    `target/japicmp/japicmp.{diff,xml}` 报告。
  - `packages`：`mvn clean verify -Prelease-verify`，随后断言 Failsafe 内容
    检查已执行且四个装配产物（bin/src × zip/tar.gz）非空。
  - 支持 `VERIFY_OFFLINE=1`（向 Maven 传 `-o`），不包含任何网络、随机 sleep
    或本机绝对路径假设。

- 新增 Maven profile `release-verify`（`pom.xml`，默认不激活）：
  - 在 `package` 生成 Javadoc，在 `integration-test` 生成 `-bin`/`-src` 装配，
    在 `verify` 运行 `japicmp:cmp` 与 Failsafe 的 `ReleaseArtifactsIT`。
  - 与父 POM 的 `release` profile 不同，它不触发 GPG 签名、
    commons-release-plugin 的 detach/stage 等发布基础设施目标，因此可以
    离线、无密钥地在 CI 与本地复现“将要发布的全部内容”。

- 新增 `src/test/java/org/apache/commons/csv/ReleaseArtifactsIT.java`（8 个用例）：
  - 主 jar 非空、包含全部已发布 class；
  - 主 jar 声明 `Automatic-Module-Name: org.apache.commons.csv`、
    `Multi-Release: true`；
  - `META-INF/versions/9/module-info.class` 与运行时能力一致，且不存在根
    `module-info.class`；
  - 主 jar 含 `META-INF/LICENSE.txt` 与 `META-INF/NOTICE.txt`；
  - sources jar 只含 `src/main/java` 下的 `*.java` 与 `META-INF`，不泄漏
    `issues/`、`perf/` 测试源；
  - tests jar 含编译后的测试类与测试资源、LICENSE/NOTICE，不含主类与
    module descriptor；
  - bin 装配含主 jar、sources jar、非空 `apidocs/`（含类文档）、
    LICENSE/NOTICE/RELEASE-NOTES，且不含松散的 `.class`/`.java`；
  - src 装配含 `pom.xml`、main/test 源、LICENSE/NOTICE，排除 `*Benchmark.java`
    与任何 `target/`/`.class` 构建输出。
  - 该 IT 仅在 `-Prelease-verify` 下由 Failsafe 执行：类名以 `IT` 结尾，
    不在 Surefire 默认的 `*Test` 模式内，因此普通矩阵构建根本不会收集它；
    `@BeforeAll` 中的 JUnit `Assumptions` 是第二道保险，确保手工以
    failsafe 直接调用但缺少生成物/未开 profile 时跳过而非误报。

- 修复 `src/assembly/bin.xml`：Javadoc 目录由陈旧的 `target/site/apidocs`
  改为 maven-javadoc-plugin 3.11+ 实际输出的 `target/reports/apidocs`。
  此前 bin 装配会在 Javadoc 缺失时静默成功，压缩包里根本没有 `apidocs/`。

- `.github/workflows/maven.yml` 改为只调用 `verify.sh`：
  - `matrix` 作业（8/11/17/21/25/26，27-ea 继续 `continue-on-error`）执行
    `./verify.sh matrix <version>`；
  - 新增 `release-verify` 作业（JDK 21，依赖 matrix）依次运行
    `build`+`jpms`、`japicmp`、`packages`。

- `pom.xml` 的 apache-rat `inputExcludes` 增加 `.pwgsb-origin`（本地评测环境
  注入的元数据，不属于源码分发内容）。

### 原覆盖的空白

1. **发布包可以“成功”地缺件。** 旧的 bin 装配没有任何门禁：目录不存在时
   maven-assembly-plugin 照常产出只含 5 个条目的 tar.gz，构建全绿。
2. **Javadoc 输出目录漂移无人发现。** 父 POM 升级到 javadoc 插件 3.12 后
   默认目录变成 `target/reports/apidocs`，而 `bin.xml` 仍引用
   `target/site/apidocs`。
3. **JPMS 描述符零行为验证。** Moditect 生成的 `module-info.class` 只被
   字节码生成器“自证”，没有任何消费者编译/链接测试；`Automatic-Module-Name`
   也没有断言。
4. **API 基线在默认构建中不存在。** `profile.japicmp` 标记缺失意味着
   `mvn verify` 从不对比 1.14.1。
5. **测试零收集全绿。** Surefire 无匹配时返回 0，旧报告目录还在，
   CI 会显示“通过”。
6. **CI 与本地命令不一致**，且没有任何地方强制“每个 JDK 都必须 clean、
   必须真的找到那个 JDK”。

### 相邻语义的退化保护

- 矩阵测试保留原有 surefire 排除规则（`**/perf/PerformanceTest.java`、
  编译期 `**/*Benchmark*`）；`ReleaseArtifactsIT` 不匹配 Surefire 的
  `*Test` 模式，普通 `mvn test` 不会收集它（基线保持 985 tests /
  38 collections），只在 release-verify profile 下由 Failsafe 运行。
- Java 8 语义保持：所有新增 Java 代码按 1.8 源码/目标级别编译，未使用
  `Runtime.Version` 等 9+ API，改用 `java.specification.version` 判定能力。
- src 装配继续排除 Benchmark，与原 `src/assembly/src.xml` 语义一致；
  该排除现在同时被断言。
- 普通 `mvn package`/`mvn test` 行为不变；新增 profile 默认不激活，
  RAT/checkstyle 等已有检查的接线未改动。

### 最危险反例与对应回归用例

| 领域 | 最危险反例 | 回归覆盖 |
| --- | --- | --- |
| Maven 校验（测试可跳过） | 某改动让 surefire 匹配不到任何测试（或新测试类命名不被模式命中），Maven 仍退出 0，CI 全绿；更糟时报告目录里是上一次运行的结果。 | `verify.sh check_surefire_reports`：空报告目录、`Tests run: 0`、失败计数任一非零都非零退出；`target/.verify-test-started` 新鲜度标记拒绝陈旧报告。已用 `mvn test -Dtest=NoSuchTest -Dsurefire.failIfNoSpecifiedTests=false` 实证退出 1。 |
| Maven 校验（生成物为空/陈旧） | 装配描述符引用的目录不存在（原 `target/site/apidocs`），或本地拿旧 JDK 的 jar 冒充新构建。 | `ReleaseArtifactsIT.testBinAssemblyContainsJarSourcesJavadocAndLegalFiles` 断言 `apidocs/index.html` 与类文档存在；`verify.sh` 的非空 jar/装配检查与陈旧产物（源文件新于 jar 即拒绝）守卫。 |
| JPMS | Moditect 丢失/改名导致 jar 退化成自动模块或模块名漂移，下游 `requires org.apache.commons.csv;` 在编译期才爆炸；或在 Java 8 构建中错误携带 `module-info.class`。 | `verify.sh jpms` 的 `--describe-module` + 真实消费者模块编译运行；`ReleaseArtifactsIT.testMainJarDeclaresAutomaticModuleName`、`testMainJarModuleDescriptorMatchesRuntime`。 |
| 发布包可复现性 | “在我的机器上有 apidocs”式的隐式前置状态、缺失 JDK 被当前 JDK 顶替、复用非 clean 的 target，导致发布内容随机器而变。 | 本地与 CI 同一条 `verify.sh`；`jdk_home` 仅接受版本确实匹配的 home，`assert_maven_jdk` 拒绝身份不符；矩阵每个 JDK 均 `clean`；`release-verify` profile 不依赖 GPG/发布插件，`VERIFY_OFFLINE=1` 可离线复现。 |
| 公开 API 基线 | 删除/改动公开类或方法破坏二进制兼容，但默认构建从不运行 japicmp。 | `verify.sh japicmp`（`-Pjapicmp verify`，失败即非零退出且要求非空报告）；`packages` 流程同样执行 japicmp。 |

### 验证记录（本地，JDK 17/21/25/26）

- `mvn -q -DskipTests package` 与 `mvn -q test` 在四个 JDK 上均通过：
  985 tests、38 collections、0 失败、0 错误。
- `./verify.sh all 17 21 25 26`（带 `VERIFY_ALLOW_MISSING_JDK=1`，本机无 8/11）
  全部通过；`./verify.sh list` 正确报告 `missing: 8 11` 且不替换 JDK。
- `./verify.sh packages 17`：8/8 `ReleaseArtifactsIT` 通过，bin zip 含
  81 个 apidocs 条目与 sources jar。
- 负向实证：空报告目录/零用例/失败计数 → 退出 1；陈旧报告 → 退出 1；
  JDK 身份不符 → 退出 1；缺失 JDK 默认 → 退出 3。
- `shellcheck -x verify.sh` 无告警；`apache-rat:check` 0 unapproved。
