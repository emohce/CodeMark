# Bookmark-X IntelliJ 插件完整实现指导文档（AI 开发用）

> 目标：  
> 从一个“仅勾选 IntelliJ Platform 的新插件项目”开始，  
> 完整实现 Bookmark-X 插件的所有核心需求：  
> 书签管理 / 分组 / 描述性节点 / 流程 / 顺序导航 / 引用与同步 / 编辑器集成 / 持久化。

---

## 一、全局约束（必须遵守）

- 使用 **Kotlin**
- IntelliJ Platform SDK ≥ 2023.2
- 不使用 Compose UI
- UI 使用 Swing + IntelliJ UI DSL
- 架构采用：**Clean Architecture + MVI**
- Domain 层 **禁止依赖 IntelliJ API**
- 所有状态变更通过 Repository + Event 驱动
- 数据必须持久化，支持未来版本升级

---

## 二、总代办清单（TodoList）

### A. 项目基础

- [ ] 使用 Gradle Kotlin DSL
- [ ] 配置 IntelliJ Plugin SDK
- [ ] 配置 Kotlin 1.9+，JVM 17
- [ ] 保持插件依赖最小化

### B. 核心领域模型（Domain）

- [ ] 定义 BookmarkNode（sealed class）
- [ ] Bookmark / DescriptiveBookmark / Group / Process
- [ ] 定义 Reference
- [ ] 定义 ProcessProgress

### C. 持久化模型（Data）

- [ ] NodeData（@Serializable sealed class）
- [ ] BookmarkPersistentState
- [ ] PersistentStateComponent
- [ ] NodeData ↔ BookmarkNode 映射

### D. Repository 层

- [ ] BookmarkRepository
- [ ] ReferenceRepository
- [ ] Repository 实现
- [ ] 领域事件触发

### E. 事件系统

- [ ] BookmarkEvent
- [ ] BookmarkEventBus（Flow）

### F. 用例层（UseCases）

- [ ] CRUD
- [ ] 流程导航
- [ ] 引用同步
- [ ] 循环检测

### G. 表现层（Presentation）

- [ ] ToolWindow
- [ ] ViewModel（MVI）
- [ ] Intent / State / SideEffect
- [ ] Tree Renderer
- [ ] Dialog

### H. 编辑器集成

- [ ] Gutter
- [ ] LineEndPainter
- [ ] 跳转与刷新

---

## 三、推荐包结构

```
indi.bookmarkx
├── domain
├── data
├── presentation
├── core
└── plugin
```

---

## 四、最优实现原则

- 单向数据流
- UI 无业务逻辑
- Repository 为唯一数据源
- Editor 为被动视图
- 所有资源绑定 Project 生命周期

---

> 本文档用于 **直接指导 AI 编码实现 Bookmark-X 插件**。
