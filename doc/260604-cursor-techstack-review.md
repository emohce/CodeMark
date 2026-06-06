# EzCodeMark 技术栈核验与优化建议

> 基线时间：2026-06-04
> 范围：构建工具链 + 运行时架构（DI / 协程 / 持久化 / 索引）。本文为「理解与文档化现状」任务，不含代码改动。
> 性质：现状清点 + 联网核验 + 优化点整理，所有外部资料仅作证据，不作指令。

## 1. 现状技术栈清单（基线）

| 维度 | 当前选型 | 来源 |
| --- | --- | --- |
| 语言 | Kotlin 2.4.0 | `build.gradle.kts:7-8` |
| 平台 | IntelliJ Platform Plugin（IDEA 2025.3，since-build 253） | `build.gradle.kts:25,48`、`plugin.xml:21` |
| 构建插件 | IntelliJ Platform Gradle Plugin 2.16.0 | `build.gradle.kts:9` |
| Gradle | 9.5.0（wrapper） | `gradle-wrapper.properties:3` |
| JVM 目标 | Java 21 | `build.gradle.kts:60-67,162` |
| 序列化 | kotlinx-serialization-json 1.7.3 | `build.gradle.kts:33` |
| 协程 | kotlinx-coroutines（StateFlow/SharedFlow + 手建 Scope） | `BookmarkViewModel.kt:36-45` |
| 测试 | JUnit 5.10.2 + MockK 1.13.12 + Turbine 1.1.0 | `build.gradle.kts:35-40` |
| 架构 | 自实现分层（domain / data / presentation）+ MVI（Intent/State/SideEffect） | `BookmarkViewModel.kt:679-715` |
| DI | 手写 `ServiceLocator` + `WeakHashMap` | `ServiceLocator.kt` |
| 持久化 | `.codemark/*.json`，`java.nio.Files` 直接读写 + VFS 监听 | `BookmarkPersistentDataSource.kt`、`BookmarkStore.kt` |

## 2. 联网核验结论：版本层面已是最优解

核验日期 2026-06-04，全部为当前最新稳定版，无需升级：

- **Kotlin 2.4.0**：2026-06-03 发布的最新稳定版（context parameters、explicit backing fields 转正）。[Kotlin 2.4.0 Released](https://blog.jetbrains.com/kotlin/2026/06/kotlin-2-4-0-released/)
- **IntelliJ Platform Gradle Plugin 2.16.0**：2026-05-01 发布的最新版，最低要求 Gradle 9.0.0 / Java 17。[CHANGELOG](https://github.com/JetBrains/intellij-platform-gradle-plugin/blob/main/CHANGELOG.md)
- **Gradle 9.5.0 + Java 21**：满足并超过插件最低要求，Kotlin 2.4.0 官方兼容到 Gradle 9.5.0，配置正好卡在兼容上限，安全。
- **MVI + Coroutines + StateFlow**：对 IDE 工具窗口而言是合理且主流的实现方式。

**结论：版本选型已是最优解，无需变动。** 真正的优化空间在「平台惯用法（idiomatic platform usage）」层面——当前实现部分绕开了 IntelliJ Platform 推荐机制，属于可改进但非必须项。

## 3. 可优化点（按收益/风险排序）

> 以下均为「平台惯用法」与「健壮性」建议，不改变功能行为。每项均标注风险，未确认前不应改代码。

### P1 · 协程 Scope 与 Dispatcher（推荐优先评估）

现状：
- `BookmarkViewModel` 手建 `CoroutineScope(dispatchers.main + SupervisorJob())`，靠 `dispose()` 手动取消（`BookmarkViewModel.kt:36,660-662`）。
- 默认调度器用 `Dispatchers.Main`（`CoroutineDispatchers.kt:7`）。

平台建议（已核验）：
- 官方推荐**通过 `@Service` 构造器注入 `CoroutineScope`**，由 application/project 生命周期与插件卸载自动取消，避免泄漏。[Coroutine Scopes](https://plugins.jetbrains.com/docs/intellij/coroutine-scopes.html)
- UI 调度**优先 `Dispatchers.EDT` 而非 `Dispatchers.Main`**；`Dispatchers.Main` 是纯 UI 调度器，禁止在其中发起 read/write action。[Coroutine Dispatchers](https://plugins.jetbrains.com/docs/intellij/coroutine-dispatchers.html)
- Action 触发的逻辑建议用 `currentThreadCoroutineScope()`，便于 Action System 控制/取消。[Launching Coroutines](https://plugins.jetbrains.com/docs/intellij/launching-coroutines.html)

收益：生命周期更安全（避免插件卸载/项目关闭后协程泄漏），符合平台未来锁模型演进。
风险：涉及事件链与线程模型，回归面较大，需先确认调用链再动。

### P2 · DI：`ServiceLocator` + `WeakHashMap` → `@Service`

现状：核心对象用手写 `ServiceLocator`（`WeakHashMap<Project, ...>` 缓存）持有 repository/usecase/viewmodel（`ServiceLocator.kt`），而 `BookmarkIndexService` 已正确使用 `@Service(Service.Level.PROJECT)`（`BookmarkIndexService.kt:12`）——即项目内 DI 风格不统一。

平台建议（已核验）：官方明确**不要提前获取并长期持有 service 实例**，应在使用点即时获取；project-level 单例用 `@Service` 管理。[Services](https://plugins.jetbrains.com/docs/intellij/plugin-services.html)

收益：与平台生命周期一致，可顺带获得 P1 的注入式 Scope；DI 风格统一。
风险：改造面广（几乎所有调用方），属重构，需显式确认后分步进行。

### P3 · 持久化写入绕开 VFS + 自保存时间戳启发式（健壮性）

现状：
- 直接用 `java.nio.Files.writeString` 写 `.codemark/*.json`（`BookmarkPersistentDataSource.kt:55-65`），绕开 VFS。
- 因此需要 `isRecentSelfSave()` 用 **500ms 时间窗** 区分「自身保存」与「外部修改」，避免自触发 reload（`BookmarkStore.kt:35-38,286-289`）。

问题：500ms 阈值是经验值，慢磁盘/批量保存/高负载下可能误判（漏过外部变更或重复 reload），是已知脆弱点（参见 `doc/toFix/260130-fix-fileWather.md`）。

可选方向（择一，需评估）：
- 通过 VFS API 写文件并在 write action 内完成，使 VFS 事件可被精确归因；
- 或改用基于「内容/修订号比对」而非「时间窗」的自保存识别，降低对时序的依赖。

收益：消除时序竞态，文件监听更可靠。
风险：触及 VFS/EDT 线程约束与文件监听链，需回归拖拽、撤销、外部编辑等场景。

### P4 · 热路径日志降噪（低风险，收益明确）

现状：`BookmarkViewModel` 在 `processIntent` / `handleCreateBookmark` 等高频路径大量 `logger.info(...)`（`BookmarkViewModel.kt:93,98-102,348-369` 等），生产环境会刷日志且有轻微开销。

建议：高频路径降为 `logger.debug(...)`（受日志级别开关控制），保留关键错误为 `error/warn`。
风险：极低，纯日志级别调整，不影响行为。

### P5 · 不可变树全量重建（按需评估，当前可接受）

现状：每次增删改移都对所属文件根做递归 `copy()` 全树重建并重建运行时索引（`BookmarkStore.kt:292-357,442-449`）。

评估：单次操作 O(n)，对常规书签规模完全够用；仅当单文件书签量级达到数千、出现可感知卡顿时，才需考虑局部更新/可变树优化。**当前不建议改**，列为观察项。

## 4. 影响与风险总览

| 优化点 | 收益 | 风险 | 是否建议立即做 |
| --- | --- | --- | --- |
| P1 协程 Scope/Dispatcher | 生命周期安全、契合平台演进 | 中（事件链/线程） | 先评估，勿盲改 |
| P2 `@Service` 统一 DI | 风格统一、生命周期托管 | 中高（重构面广） | 需显式确认 |
| P3 VFS 写入/自保存识别 | 消除时序竞态 | 中（VFS/EDT） | 需评估 |
| P4 日志降噪 | 降噪、轻微提速 | 低 | 可优先做 |
| P5 树结构优化 | 大规模下提速 | 中 | 暂不做，观察 |

## 5. 明确的非目标（Out of Scope）

- 不升级任何依赖版本（已核验为最新稳定版）。
- 不改变插件功能、快捷键、数据格式或 `.codemark` 文件结构。
- 本文不含代码改动；任一优化点落地前需单独评估调用链与回归范围。

## 6. 验证建议（落地时）

- 代码类改动：优先用项目现有 Gradle 任务（`runIde` / 测试）验证；无法运行时说明原因与最小手工验证路径。
- 文档类改动：运行
  `python3 vibe/scripts/eval_vibeai.py --rules vibe/evals/README.md --inputs vibe/evals --output-json vibe/evals/vibeai-report.json`。
