# Bookmark-X 从零开始方案

## 一、目标与范围

- MVP 目标：书签树管理、编辑器定位、流程导航、引用同步、搜索、持久化
- 非目标：旧版本数据迁移、跨项目同步、多人协作

## 二、技术栈与版本

- Kotlin 1.9+，Gradle 8.5+ (Kotlin DSL)
- IntelliJ Plugin 1.17+，Platform SDK 2023.2+
- Coroutines + Flow，kotlinx.serialization
- 测试：JUnit 5 + MockK + Turbine

## 三、关键决策（必须先定）

1. 引用模型二选一
   - 方案 A：独立 Reference 实体
   - 方案 B：Bookmark(referenceId) 作为引用
   - 结论：选定其一，全链路统一
2. 事件机制
   - 方案 A：Repository.observeChanges() 作为唯一事件源
   - 方案 B：EventBus 作为唯一事件源
   - 结论：避免双轨并存
3. 持久化方式
   - 方案 A：IDE 持久化组件
   - 方案 B：独立 JSON 文件
   - 结论：明确落盘路径、时机、格式与版本字段

## 四、架构与模块

- domain：模型、用例、事件（不依赖 IntelliJ）
- data：持久化、缓存、映射、仓库实现
- presentation：ToolWindow、Dialogs、Editor(Gutter/Inlay)、Actions
- core：协程调度、工具类、平台桥接

## 五、里程碑与交付物

### Phase 1：项目脚手架（1 周）
- 创建插件工程与 build.gradle.kts
- 完成 plugin.xml 基础声明
- 交付物：可运行的空插件 + ToolWindow 骨架

### Phase 2：领域层与用例（1-2 周）
- 定义 BookmarkNode/Process/Reference 模型
- 定义核心用例接口与事件模型
- 交付物：domain 模块 + 单元测试雏形

### Phase 3：数据层落地（1-2 周）
- 定义持久化格式与版本号
- 完成 NodeData 映射与 Repository 实现
- 交付物：CRUD 可用、重启后数据一致

### Phase 4：表现层 MVP（2-3 周）
- 书签树展示、增删改、搜索、对话框
- 交付物：完整用户流程可操作

### Phase 5：编辑器集成（1-2 周）
- Gutter/Inlay 显示与跳转
- 流程导航上下步
- 交付物：编辑器内可视化与交互闭环

### Phase 6：质量与性能（1 周）
- 单测/集成/端到端清单
- 1000+ 节点性能基线
- 交付物：测试报告与性能指标

## 六、最小验收标准

- 增删改查、搜索、流程导航、引用同步完成
- 1000 节点加载 < 1s，UI 响应 < 100ms
- 持久化稳定，重启不丢数据
- 异常可恢复（写入失败、引用冲突、空数据）

## 七、风险与缓解

| 风险 | 级别 | 缓解措施 |
|------|------|----------|
| IntelliJ API 变动 | 高 | 版本检测，条件分支 |
| 引用循环 | 高 | 创建前 DFS 检测 |
| 性能回退 | 中 | 基准测试、缓存优化 |
| 协程误用 | 中 | 结构化并发、代码审查 |
