# CodeRemarkTour

结构化书签与流程导航插件，提供工具窗口、gutter/inlay 联动、引用关系与流程步进导航，面向大项目的“代码导览 + 备注”场景。

## 概要
- **核心能力**：树形书签（分组/流程/备注）、引用关联、编辑器 gutter 图标与行尾 inlay、流程上一条/下一条导航、选中联动。
- **数据源**：插件自有 BookmarkStore（本地 JSON），单一事实源；IDE 内置书签同步默认关闭（后续以开关灰度）。
- **主要入口**：右侧 ToolWindow“CodeRemarkTour”、编辑器右键菜单、快捷键（见下）。

## 环境要求
- IntelliJ IDEA：目标 2025.3，对应 sinceBuild 253.*。
- Kotlin 2.1.20，Gradle + intellij-platform-gradle-plugin 2.10.2。
- JDK 21 目标（source/target）。

## 安装与运行
- 本地开发：使用 Gradle 任务 `runIde` 运行沙盒；`buildSearchableOptions` 已禁用以减少失败风险。
- 发布：后续可接入 `publishPlugin`，暂未配置市场发布信息。

## 快速上手
- 在编辑器中：
  - 右键菜单 “Add Bookmark/Group/Process/Note” 创建节点。
  - Gutter 图标左键选中树节点并（可选）导航，右键菜单执行编辑/删除/引用等操作。
  - 行尾 inlay 显示节点提示，点击可导航。
- 在工具窗口：
  - 树视图支持创建、编辑、删除、移动；流程节点可用上一条/下一条按钮。
  - 搜索与筛选入口见工具窗口顶部。

快捷键（默认）：
- Add Bookmark Here: `Shift+F2`
- Add Process Entry Here: 右键菜单（可自定义）
- Add Group Here: `Shift+F3`
- Add Note Here: `Shift+F4`
- Navigate Next/Prev in Process: `Ctrl+Shift+N` / `Ctrl+Shift+P`

## 架构概览
- **数据存储**：`.bookmarkx/bookmarkx.json` 通过 BookmarkStore 管理。
- **Repository & UseCase**：`BookmarkRepositoryImpl`、`ReferenceRepositoryImpl`，配合 ProcessNavigation/SyncReferences/DetectCircularRef 等用例。
- **ViewModel**：`BookmarkViewModel` 基于 StateFlow + SharedFlow 管理状态与副作用。
- **编辑器集成**：
  - `BookmarkHighlighterService`（gutter 图标 + 行高亮，单源 RangeHighlighter）
  - `BookmarkLineEndInlayProvider`（行尾 inlay）
- **交互总线**：`SelectionBus` 协调工具窗口与编辑器选中状态。

## 已知限制
- 不提供远端同步、多用户、权限控制。
- IDE 内置书签同步默认关闭；后续通过配置开关灰度。
- 性能与大规模数据（1000+ 书签）尚未压测，需补测试。

## 关联文档
- 重构方案：`./260126-cursor-重构方案.md`
- 冗余/优化核查：`./260127-cursor-冗余优化核查.md`
- TODO 进度：`./doc/CODE_REMARK_TOUR_TODO.md`

## 开发提示
- 代码入口：ToolWindow 工厂 `emohce.presentation.toolwindow.CodeRemarkTourToolWindowFactory`，启动活动 `emohce.core.startup.BookmarkStartupActivity`。
- 插件声明：`./src/main/resources/META-INF/plugin.xml`（gutter 由 BookmarkHighlighterService 绘制，内置书签同步开关默认关）。
- 依赖与构建：`./build.gradle.kts`，JDK 21；如需下调 IDEA 版本请同步 sinceBuild。
- 内置书签同步开关：项目设置 `BookmarkSettingsService`（默认 false），可通过 Registry `coderemarktour.enableLegacyIntellijSync` 强制开启；`IntelliJBookmarkManager` 标记 @Deprecated。

## 变更记录
- 2026-01-27：重写 README，模板移至 `doc/README-template.md`；确认单一数据源策略，计划恢复自定义 gutter 注册并关闭内置书签同步。