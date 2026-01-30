# 打包指南

## IntelliJ 插件打包方式

### 方法 1：构建插件 ZIP（推荐，用于分发）

这是标准的 IntelliJ 插件分发格式，生成一个包含所有依赖的 ZIP 文件，可以直接安装到 IntelliJ IDEA。

**命令：**
```bash
# Windows
gradlew.bat buildPlugin

# Linux/Mac
./gradlew buildPlugin
```

**输出位置：**
- `build/distributions/CodeRemarkTour-0.0.1.zip`

**安装方式：**
1. 打开 IntelliJ IDEA
2. Settings → Plugins → ⚙️ → Install Plugin from Disk...
3. 选择生成的 ZIP 文件

### 方法 2：构建 JAR 文件

如果需要单独的 JAR 文件（不包含依赖）：

**命令：**
```bash
# Windows
gradlew.bat jar

# Linux/Mac
./gradlew jar
```

**输出位置：**
- `build/libs/CodeRemarkTour-0.0.1.jar`

**注意：** 这个 JAR 不包含依赖库，仅包含编译后的类文件。

### 方法 3：构建包含依赖的 Fat JAR

如果需要包含所有依赖的完整 JAR，需要在 `build.gradle.kts` 中添加配置：

```kotlin
tasks {
    val fatJar = register<Jar>("fatJar") {
        archiveClassifier.set("fat")
        duplicatesStrategy = DuplicatesStrategy.EXCLUDE
        from(configurations.runtimeClasspath.get().map { if (it.isDirectory) it else zipTree(it) })
        from(sourceSets.main.get().output)
    }
    build {
        dependsOn(fatJar)
    }
}
```

然后运行：
```bash
gradlew.bat fatJar
```

### 方法 4：验证插件

在打包前验证插件配置：

```bash
# Windows
gradlew.bat verifyPlugin

# Linux/Mac
./gradlew verifyPlugin
```

这会检查：
- plugin.xml 配置是否正确
- 依赖是否满足
- 代码是否有明显错误

### 构建输出目录结构

```
build/
├── distributions/
│   └── CodeRemarkTour-0.0.1.zip  # 插件 ZIP（可安装）
├── libs/
│   └── CodeRemarkTour-0.0.1.jar  # 普通 JAR
└── searchableOptions/
    └── ...                        # 可搜索选项（如果执行了 buildSearchableOptions）
```

### 常用 Gradle 任务

| 任务 | 说明 |
|------|------|
| `buildPlugin` | 构建插件 ZIP（推荐） |
| `jar` | 构建普通 JAR |
| `build` | 编译和测试 |
| `clean` | 清理构建目录 |
| `verifyPlugin` | 验证插件配置 |
| `runIde` | 运行带插件的 IDE（开发用） |
| `buildSearchableOptions` | 构建可搜索选项 |

### runIde 与 Kubernetes 错误抑制

执行 `runIde` 时，IDE 内置的 Kubernetes 插件可能输出 “No remote API found” 的 `IllegalStateException`。项目已在 `build.gradle.kts` 中为 `runIde` 配置了 JVM 参数以抑制此类日志：

- `idea.suppress.frequent.exception.logging=true`：抑制频繁异常日志
- `idea.kubernetes.enabled=false`：关闭 Kubernetes 相关初始化

无需额外操作，直接运行 `runIde` 即可。

### 发布到 JetBrains Marketplace

如果需要发布到插件市场，需要：

1. 在 [JetBrains Marketplace](https://plugins.jetbrains.com/) 注册账号
2. 创建插件条目
3. 使用 `buildPlugin` 构建 ZIP
4. 在 Marketplace 上传 ZIP 文件

### 注意事项

1. **版本号**：在 `build.gradle.kts` 中修改 `version` 字段
2. **插件 ID**：在 `plugin.xml` 中的 `<id>` 标签，一旦发布不能更改
3. **依赖检查**：确保所有依赖都正确声明
4. **测试**：打包前运行测试确保功能正常

### 快速打包命令

**Windows:**
```cmd
gradlew.bat clean buildPlugin
```

**Linux/Mac:**
```bash
./gradlew clean buildPlugin
```

这会先清理旧的构建文件，然后构建新的插件 ZIP。

### 常见问题

#### buildSearchableOptions 任务失败

如果遇到 `buildSearchableOptions` 任务失败的错误，这是正常的。该任务用于生成可搜索选项，但对于 MVP 版本不是必需的。

**解决方案：**
已在 `build.gradle.kts` 中禁用了该任务。如果仍然遇到问题，可以：

1. **跳过该任务（推荐）：**
   ```cmd
   gradlew.bat buildPlugin -x buildSearchableOptions
   ```

2. **或者直接构建 JAR：**
   ```cmd
   gradlew.bat jar
   ```

3. **检查构建配置：**
   确保 `build.gradle.kts` 中的 `buildSearchableOptions` 任务已禁用：
   ```kotlin
   named("buildSearchableOptions") {
       enabled = false
   }
   ```
