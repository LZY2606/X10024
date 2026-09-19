<!---
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

## Commons CSV：收敛 JDK 矩阵、JPMS 与发布包内容校验

### 实现选择

- **单一入口 `verify.sh`**：本地与 CI（`.github/workflows/maven.yml`）调用同一脚本。
  对每个可用 JDK 依次执行 `mvn clean package -DskipTests` 与 `mvn test`，
  与仓库约定的验收命令（`mvn -q -DskipTests package` 后接 `mvn -q test`）保持一致。
- **JDK 矩阵解析**：`--jdk N` 声明的 JDK 为必需项，找不到即以非零退出并给出
  可诊断信息（提示 `JAVA_HOME_N`、`~/.m2/toolchains.xml` 等解析途径）；
  默认矩阵（`VERIFY_JDKS`，缺省 `8 11 17 21 25 26`，与 CI 矩阵对齐）中缺失的
  JDK 打印明确的 `SKIP` 说明。任何情况下都不会静默改用当前 JDK；
  仅当当前 `JAVA_HOME` 的版本与请求版本完全一致时才允许复用。
  解析顺序：`JAVA_HOME_<N>` 环境变量 → 版本匹配的当前 `JAVA_HOME` →
  `~/.m2/toolchains.xml` → 系统级 `java_home` 工具 → 包管理器前缀查询，
  不包含任何本机绝对路径硬编码。
- **自检下沉为 JUnit 用例**（`src/test/java/org/apache/commons/csv/verify/`），
  因此本地 `mvn test`、CI 与 `verify.sh` 共享同一份校验逻辑，且每个用例可单独定位：
  - `ReleaseArtifactsTest`：三种发布包（main jar、sources jar、tests jar）的
    必需文件（`META-INF/MANIFEST.MF`、`META-INF/LICENSE.txt`、`META-INF/NOTICE.txt`、
    `META-INF/versions/9/module-info.class`、主类 `.class` / `.java` / 测试类）
    与禁止文件（main jar 中的 `*Test.class`、基准类、`issues/`、`perf/`、`.java`；
    sources jar 中的 `.class`；tests jar 中泄漏的主类与 `.java`）；
    校验 `Automatic-Module-Name` 与 module-info 描述符的模块名一致且导出
    `org.apache.commons.csv`（通过反射访问 `java.lang.module.ModuleDescriptor`，
    使测试在 `--release 8` 下可编译、在 JDK 9+ 上真实执行）。
  - `PublicApiBaselineTest`：从 `target/classes` 反射收集公开 API
    （public/protected 类、字段、构造器、方法，跳过 synthetic/bridge），
    与签入基线 `src/test/resources/org/apache/commons/csv/verify/public-api-baseline.txt`
    做双向严格比较；用 `-Dapi.baseline.write=true` 重新生成基线。
- **失败传递与零收集拒绝**：脚本对每个子命令显式捕获退出码并原样传递；
  构建前打点、构建后校验 `target/*.jar` 存在、非空且不早于构建起点（拒绝陈旧生成物）；
  校验 surefire 报告中两个校验测试集存在且 `Tests run > 0`（拒绝测试集被静默跳过）；
  全部 JDK 都不可用时以非零退出（拒绝零收集）。

### 原覆盖的空白

- 此前没有任何入口同时覆盖 JDK 矩阵、JPMS 描述符与发布包内容：CI 只跑 Maven 默认
  goal，japicmp 依赖网络下载旧版基线，javadoc 与 assembly 只在 release profile 生效，
  本地无法离线复现同一份校验。
- `Automatic-Module-Name`（清单条目）与 moditect 生成的 `module-info.class`
  之间没有任何一致性检查，两者由不同插件在不同阶段写入。
- 三种发布包的必需/禁止文件此前完全没有自动化检查；空 jar、测试类泄漏进
  main jar、sources jar 混入 `.class` 都不会使构建失败。
- 公开 API 没有离线基线；二进制不兼容变更只有联网跑 japicmp 才能发现。

### 相邻语义的退化保护

- CI 原命令的默认 goal 包含 checkstyle/spotbugs/pmd/cpd、jacoco 覆盖率门禁与
  `changes-validate`。`verify.sh` 在首个可用 JDK 上额外执行
  `mvn verify -DskipTests`（复用前一次 `mvn test` 的覆盖率数据，保留 jacoco 与
  changes 门禁）以及 `checkstyle:check spotbugs:check pmd:check pmd:cpd-check`，
  避免收敛入口后静态分析与覆盖率门禁被静默丢弃（可用 `VERIFY_STATIC=0` 关闭）。
- japicmp 未纳入默认入口（其基线构件需要联网，违背离线可复现），由
  `PublicApiBaselineTest` 的签入基线离线覆盖同一语义；japicmp 仍可通过
  `mvn japicmp:cmp` 单独运行。
- RAT 排除清单仅新增 `.pwgsb-origin`（环境注入的标记文件，不属于发布内容），
  不改变任何源码或许可检查语义。

### 最危险反例与对应回归用例

- **最危险反例**：只跑 `mvn test`（或测试在 `clean verify` 的 test 阶段先于
  package 执行）时 `target/` 中根本没有发布包；若校验用例用
  `assumeTrue(jarExists)` 之类的写法，会在发布包缺失或为空时**静默跳过**，
  给出全绿构建——一个什么都没有校验的“成功”。同理，反射收集到 0 个类时，
  空集合与空基线相等也会让 API 基线检查空转。
- **回归用例**：`ReleaseArtifactsTest` 与 `PublicApiBaselineTest` 在生成物缺失、
  为空或收集数为零时一律 `fail`/`IllegalStateException` 并携带诊断上下文
  （绝对路径、期望的修复命令），绝不 assumption-skip；`verify.sh` 的
  `check_test_sets` 进一步要求两个测试集的 surefire 报告存在且
  `Tests run > 0`，把“测试集被跳过”也收敛为非零退出。
