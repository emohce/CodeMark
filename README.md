# CodeMark

结构化书签与流程导航插件，提供工具窗口、编辑器联动、引用关系与流程步进导航，面向大项目的"代码导览 + 备注"场景。

[📖 English Version](./README_EN.md)

## 概览

**CodeMark** 是一个 IntelliJ IDEA 插件，提供结构化的代码书签管理功能，支持：

- **树形书签系统**：分组、流程节点、备注的层次化管理
- **智能导航**：编辑器 gutter 图标、行尾提示、流程步进
- **引用关系**：节点间引用关联与循环检测
- **数据持久化**：本地 JSON 存储，支持撤销操作
- **双向联动**：工具窗口与编辑器状态同步

### 核心特性

- **四种节点类型**：Bookmark（书签）、Group（分组）、Process（流程-暂未实现）、DescriptiveBookmark（描述性书签）
- **精确定位**：基于文件路径、行号、列号的精确导航
- **引用管理**：节点间引用关系，支持循环检测
- **流程导航**：流程节点的上一条/下一条步进功能
- **视觉增强**：gutter 图标、行尾 inlay 提示、代码高亮
- **性能优化**：索引服务、增量更新、懒加载策略

## 环境要求
- **IntelliJ IDEA**：2025.3+（sinceBuild 253.*）
- **Kotlin**：2.1.20
- **Gradle**：intellij-platform-gradle-plugin 2.10.2
- **JDK**：21（source/target）

## 安装与运行

### 开发环境
```bash
# 克隆项目
git clone <repository-url>
cd CodeRemarkTour

# 运行沙盒环境
./gradlew runIde
```

### 构建发布
```bash
# 构建插件包
./gradlew buildPlugin

# 发布到市场（需配置）
./gradlew publishPlugin
```

> **注意**：`buildSearchableOptions` 任务已禁用以减少构建失败风险。

## 快速上手

### 基本操作

#### 编辑器中创建节点
- **右键菜单**："Add CodeMark Here"、"Add Group Here"、"Add Process Entry Here"、"Add Note Here"
- **快捷键**：
  - `Shift+F2` - 添加书签
  - `Shift+F3` - 添加分组
  - `Shift+F4` - 添加备注
  - `Alt+Shift+↓` - 下一个书签
  - `Alt+Shift+↑` - 上一个书签
  - `Shift+Delete` - 删除当前行书签

#### 工具窗口操作
- **树视图**：支持拖拽移动、右键菜单、搜索过滤
- **流程导航**：流程节点的上一条/下一条按钮
- **搜索功能**：顶部搜索框支持名称和描述搜索

#### 编辑器联动
- **Gutter 图标**：左键选中树节点，右键显示操作菜单
- **行尾提示**：显示节点信息，点击可导航
- **双向选择**：工具窗口选中与编辑器位置同步

## 架构设计

### 分层架构

```
┌─────────────────────────────────────────────────────────┐
│                    Presentation Layer                   │
├─────────────────┬─────────────────┬─────────────────────┤
│   ToolWindow    │   Editor Actions│   Editor Integration│
│  BookmarkPanel  │ Create*Actions │ Highlighter/Inlay   │
│   ViewModel     │  Navigation    │  SelectionBus       │
└─────────────────┴─────────────────┴─────────────────────┘
┌─────────────────────────────────────────────────────────┐
│                     Domain Layer                        │
├─────────────────┬─────────────────┬─────────────────────┤
│    Models       │   Repositories  │     UseCases        │
│  BookmarkNode   │  BookmarkRepo   │ ProcessNavigation   │
│   Reference     │ ReferenceRepo   │  SyncReferences      │
│  ProcessProgress│                 │ DetectCircularRef   │
└─────────────────┴─────────────────┴─────────────────────┘
┌─────────────────────────────────────────────────────────┐
│                      Data Layer                         │
├─────────────────┬─────────────────┬─────────────────────┤
│   BookmarkStore │  DataSource     │    Persistence       │
│   IndexService  │ PersistentState │   NodeData/JSON     │
└─────────────────┴─────────────────┴─────────────────────┘
```

### 核心组件

#### 数据模型
- **BookmarkNode**：sealed class 包含四种节点类型
  - `Bookmark`：文件位置书签
  - `Group`：分组容器
  - `Process`：流程节点，支持步骤导航
  - `DescriptiveBookmark`：描述性书签

#### 存储系统
- **BookmarkStore**：内存数据管理，支持撤销操作
- **文件持久化**：`.bookmarkx/bookmarkx.json`
- **索引服务**：`BookmarkIndexService` 提供快速查找

#### 编辑器集成
- **BookmarkHighlighterService**：gutter 图标与行高亮
- **BookmarkLineEndInlayProvider**：行尾提示
- **SelectionBus**：组件间状态同步

#### 响应式架构
- **StateFlow**：UI 状态管理
- **SharedFlow**：副作用处理（导航、通知等）
- **协程**：异步操作与并发控制

## 高级特性

### 引用系统
- **节点引用**：支持节点间的引用关系建立
- **循环检测**：自动检测并防止引用循环
- **引用计数**：跟踪节点被引用次数

### 流程管理 (暂未实现)
- **流程节点**：将相关书签组织成流程
- **步进导航**：流程内上一条/下一条导航
- **进度跟踪**：显示当前流程进度

### 搜索与过滤
- **全文搜索**：支持名称和描述搜索
- **实时过滤**：输入时实时更新结果
- **高亮显示**：搜索结果高亮

### 数据安全
- **自动保存**：操作自动保存到本地
- **撤销支持**：支持撤销最近的操作
- **数据备份**：自动创建备份文件

## 已知限制

### 功能限制
- ❌ 不提供远端同步功能
- ❌ 不支持多用户协同
- ❌ 无权限控制机制
- ❌ 无复杂冲突解决策略

### 性能限制
- ⚠️ 大规模数据（1000+ 书签）尚未压测
- ⚠️ 复杂引用关系可能影响性能
- ⚠️ 大项目启动时索引构建可能较慢

### 兼容性
- ⚠️ IDE 内置书签同步默认关闭
- ⚠️ 需要手动开启高级功能

## 项目文档

### 设计文档
- **功能逻辑梳理**：[260126-cursor-功能逻辑梳理.md](./260126-cursor-功能逻辑梳理.md)
- **重构方案**：[260126-cursor-重构方案.md](./260126-cursor-重构方案.md)
- **TODO 列表**：[doc/CODE_REMARK_TOUR_TODO.md](./doc/CODE_REMARK_TOUR_TODO.md)

### 开发文档
- **构建指南**：[doc/build-guide.md](./doc/build-guide.md)
- **变更日志**：[doc/change-log.md](./doc/change-log.md)
- **操作入口汇总**：[doc/260206-操作入口与快捷键汇总.md](./doc/260206-操作入口与快捷键汇总.md)

## 开发指南

### 关键入口点
- **ToolWindow 工厂**：`emohce.presentation.toolwindow.CodeMarkToolWindowFactory`
- **启动活动**：`emohce.core.startup.BookmarkStartupActivity`
- **插件配置**：`./src/main/resources/META-INF/plugin.xml`

### 核心类说明
- **BookmarkViewModel**：UI 状态管理与业务逻辑
- **BookmarkRepository**：数据访问层
- **BookmarkStore**：数据存储与持久化
- **SelectionBus**：组件间通信

### 构建配置
- **构建脚本**：`./build.gradle.kts`
- **JDK 版本**：21
- **IDE 版本**：IntelliJ IDEA 2025.3+（sinceBuild 253）

### 调试与配置
- **内置书签同步**：通过 Registry `coderemarktour.enableLegacyIntellijSync` 强制开启
- **日志级别**：在 IDE 日志中查看 `[CODEMARK]` 前缀日志
- **数据文件位置**：项目根目录 `.bookmarkx/bookmarkx.json`

## 贡献指南

### 开发环境设置
1. 克隆项目到本地
2. 确保 JDK 21 已安装
3. 运行 `./gradlew runIde` 启动开发环境
4. 构建 zip `./gradlew buildPlugin` 创建插件包
5. 在沙盒 IDE 中测试插件功能

### 代码规范
- 遵循 Kotlin 官方编码规范
- 使用单一数据源原则
- 保持响应式编程模式
- 添加适当的日志记录

### 提交规范
- 使用清晰的提交信息
- 一个提交只做一件事
- 包含必要的测试用例
- 更新相关文档

## 许可证

本项目采用 [LICENSE](./LICENSE) 许可证。

## 致谢

感谢 IntelliJ Platform 提供的插件开发框架，以及相关开源项目的启发：

- **[CodeTour](https://github.com/LefterisXris/CodeTour)** - IntelliJ 代码导览插件，为流程导航功能提供设计灵感
- **[Bookmark-X](https://github.com/Nonoas/Bookmark-X)** - IntelliJ 书签管理插件，为编辑器集成提供参考实现

这些项目在代码书签管理和导航功能方面的探索为本项目提供了宝贵的经验和思路。
