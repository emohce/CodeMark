# CodeRemarkTour AI 开发指导

## 一、项目定位

- 项目名：CodeRemarkTour
- 包名：emohce
- 初始版本：0.0.1
- 目标平台：IntelliJ IDEA 2025.3 起始版本

## 二、目标与范围

- MVP 目标：书签树管理、编辑器定位、流程导航、引用同步、搜索、持久化
- 非目标：旧版本数据迁移、跨项目同步、多人协作

## 三、技术栈与版本

- Kotlin 1.9+，Gradle 8.5+ (Kotlin DSL)
- IntelliJ Plugin 1.17+，Platform SDK 2025.3+
- Coroutines + Flow，kotlinx.serialization
- 测试：JUnit 5 + MockK + Turbine

## 四、关键决策（已确认）

1. 引用模型：采用独立 Reference 实体
   - 理由：引用同步与引用删除解耦，避免源节点删除时误删引用体
2. 事件机制：Repository.observeChanges() 作为唯一事件源
   - 理由：降低全局事件总线耦合，保持数据变化入口单一
3. 持久化方式：独立 JSON 文件
   - 落盘位置：$PROJECT/.bookmarkx/bookmarkx.json
   - 要求：包含版本字段，支持未来升级

## 五、架构与模块

- domain：模型、用例、事件（不依赖 IntelliJ）
- data：持久化、缓存、映射、仓库实现
- presentation：ToolWindow、Dialogs、Editor(Gutter/Inlay)、Actions
- core：协程调度、工具类、平台桥接

## 六、里程碑与交付物

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

## 七、当前实现进度（同步更新）

- 引用关系：已提供引用对列表视图，多选跳转；树节点显示引用数和被引用标记
- 引用同步：手动同步 + 编辑源书签自动同步；失败会进行一次重试并给出告警
- 流程步骤：新增“Add Step”入口，可将现有节点加入流程步骤
- 搜索体验：结果树保留完整层级路径；支持 ESC 清空并返回树、Ctrl+Enter 触发搜索
- 展开状态：展开/折叠状态由 ViewModel 维护，刷新后恢复
- 删除提示：删除书签时提示引用影响，避免误删

## 八、最小验收标准

- 增删改查、搜索、流程导航、引用同步完成
- 1000 节点加载 < 1s，UI 响应 < 100ms
- 持久化稳定，重启不丢数据
- 异常可恢复（写入失败、引用冲突、空数据）

## 九、待完善项（后续）

- 引用链可视化：增加更直观的关系图/双向视图
- Editor 侧 Inlay/LineEnd 提示
- 大树性能：懒加载、批量更新、最小重绘
- 快捷键冲突检查与可配置
- 测试补齐：repository、持久化、引用链、UI 交互

## 十、风险与缓解

| 风险 | 级别 | 缓解措施 |
|------|------|----------|
| IntelliJ API 变动 | 高 | 版本检测，条件分支 |
| 引用循环 | 高 | 创建前 DFS 检测 |
| 性能回退 | 中 | 基准测试、缓存优化 |
| 协程误用 | 中 | 结构化并发、代码审查 |
