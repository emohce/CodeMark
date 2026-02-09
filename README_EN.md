# CodeMark

A structured bookmark and navigation plugin for IntelliJ IDEA, providing tool windows, editor integration, reference relationships, and step-by-step navigation for large-scale code exploration and annotation scenarios.

[📖 中文版](./README.md)

## Overview

**CodeMark** is an IntelliJ IDEA plugin that provides structured code bookmark management with support for:

- **Hierarchical Bookmark System**: Layered management of groups, process nodes, and notes
- **Smart Navigation**: Editor gutter icons, line-end hints, and process step navigation
- **Reference Management**: Inter-node reference relationships with circular dependency detection
- **Data Persistence**: Local JSON storage with undo support
- **Bidirectional Sync**: Synchronized state between tool windows and editor

### Core Features

- **Four Node Types**: Bookmark, Group, Process, DescriptiveBookmark
- **Precise Positioning**: Exact navigation based on file path, line number, and column
- **Reference Management**: Inter-node references with circular dependency detection
- **Process Navigation**: Previous/Next step navigation within process nodes
- **Visual Enhancement**: Gutter icons, line-end inlay hints, and code highlighting
- **Performance Optimization**: Index service, incremental updates, and lazy loading

## Requirements

- **IntelliJ IDEA**: 2025.3+ (sinceBuild 253.*)
- **Kotlin**: 2.1.20
- **Gradle**: intellij-platform-gradle-plugin 2.10.2
- **JDK**: 21 (source/target)

## Installation and Running

### Development Environment
```bash
# Clone the project
git clone <repository-url>
cd CodeRemarkTour

# Run sandbox environment
./gradlew runIde
```

### Build and Release
```bash
# Build plugin package
./gradlew buildPlugin

# Publish to marketplace (requires configuration)
./gradlew publishPlugin
```

> **Note**: The `buildSearchableOptions` task is disabled to reduce build failure risks.

## Quick Start

### Basic Operations

#### Creating Nodes in Editor
- **Right-click Menu**: "Add CodeMark Here", "Add Group Here", "Add Process Entry Here", "Add Note Here"
- **Keyboard Shortcuts**:
  - `Shift+F2` - Add bookmark
  - `Shift+F3` - Add group
  - `Shift+F4` - Add note
  - `Alt+Shift+↓` - Next bookmark
  - `Alt+Shift+↑` - Previous bookmark
  - `Shift+Delete` - Delete current line bookmark

#### Tool Window Operations
- **Tree View**: Support drag-and-drop moving, right-click menu, search filtering
- **Process Navigation**: Previous/Next buttons for process nodes
- **Search Function**: Top search bar supports name and description search

#### Editor Integration
- **Gutter Icons**: Left-click selects tree node, right-click shows operation menu
- **Line-end Hints**: Display node information, click to navigate
- **Bidirectional Selection**: Tool window selection syncs with editor position

## Architecture Design

### Layered Architecture

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

### Core Components

#### Data Models
- **BookmarkNode**: sealed class containing four node types
  - `Bookmark`: File location bookmarks
  - `Group`: Group containers
  - `Process`: Process nodes with step navigation support
  - `DescriptiveBookmark`: Descriptive bookmarks

#### Storage System
- **BookmarkStore**: In-memory data management with undo support
- **File Persistence**: `.bookmarkx/bookmarkx.json`
- **Index Service**: `BookmarkIndexService` for fast lookup

#### Editor Integration
- **BookmarkHighlighterService**: Gutter icons and line highlighting
- **BookmarkLineEndInlayProvider**: Line-end hints
- **SelectionBus**: Component state synchronization

#### Reactive Architecture
- **StateFlow**: UI state management
- **SharedFlow**: Side effect handling (navigation, notifications, etc.)
- **Coroutines**: Async operations and concurrency control

## Advanced Features

### Reference System
- **Node References**: Support establishing reference relationships between nodes
- **Circular Detection**: Automatically detect and prevent reference cycles
- **Reference Counting**: Track how many times nodes are referenced

### Process Management
- **Process Nodes**: Organize related bookmarks into processes
- **Step Navigation**: Previous/Next navigation within processes
- **Progress Tracking**: Display current process progress

### Search and Filtering
- **Full-text Search**: Support name and description search
- **Real-time Filtering**: Update results in real-time as you type
- **Highlight Display**: Highlight search results

### Data Security
- **Auto Save**: Operations automatically saved locally
- **Undo Support**: Support undoing recent operations
- **Data Backup**: Automatically create backup files

## Known Limitations

### Feature Limitations
- ❌ No remote sync functionality
- ❌ No multi-user collaboration
- ❌ No permission control mechanism
- ❌ No complex conflict resolution strategies

### Performance Limitations
- ⚠️ Large-scale data (1000+ bookmarks) not yet stress-tested
- ⚠️ Complex reference relationships may affect performance
- ⚠️ Index building may be slow for large projects on startup

### Compatibility
- ⚠️ IDE built-in bookmark sync disabled by default
- ⚠️ Advanced features require manual enablement

## Project Documentation

### Design Documents
- **Feature Logic Analysis**: [260126-cursor-功能逻辑梳理.md](./260126-cursor-功能逻辑梳理.md)
- **Refactoring Plan**: [260126-cursor-重构方案.md](./260126-cursor-重构方案.md)
- **TODO List**: [doc/CODE_REMARK_TOUR_TODO.md](./doc/CODE_REMARK_TOUR_TODO.md)

### Development Documentation
- **Build Guide**: [doc/build-guide.md](./doc/build-guide.md)
- **Change Log**: [doc/change-log.md](./doc/change-log.md)
- **Operation Summary**: [doc/260206-操作入口与快捷键汇总.md](./doc/260206-操作入口与快捷键汇总.md)

## Development Guide

### Key Entry Points
- **ToolWindow Factory**: `emohce.presentation.toolwindow.CodeMarkToolWindowFactory`
- **Startup Activity**: `emohce.core.startup.BookmarkStartupActivity`
- **Plugin Configuration**: `./src/main/resources/META-INF/plugin.xml`

### Core Classes
- **BookmarkViewModel**: UI state management and business logic
- **BookmarkRepository**: Data access layer
- **BookmarkStore**: Data storage and persistence
- **SelectionBus**: Inter-component communication

### Build Configuration
- **Build Script**: `./build.gradle.kts`
- **JDK Version**: 21
- **IDE Version**: IntelliJ IDEA 2025.3+ (sinceBuild 253)

### Debug and Configuration
- **Built-in Bookmark Sync**: Force enable via Registry `coderemarktour.enableLegacyIntellijSync`
- **Log Level**: View `[CODEMARK]` prefix logs in IDE logs
- **Data File Location**: Project root directory `.bookmarkx/bookmarkx.json`

## Contributing Guidelines

### Development Environment Setup
1. Clone the project locally
2. Ensure JDK 21 is installed
3. Run `./gradlew runIde` to start development environment
4. Build zip `./gradlew buildPlugin` to create plugin package
5. Test plugin functionality in sandbox IDE

### Code Standards
- Follow Kotlin official coding standards
- Use single data source principle
- Maintain reactive programming patterns
- Add appropriate logging

### Commit Standards
- Use clear commit messages
- One commit per task
- Include necessary test cases
- Update relevant documentation

## License

This project is licensed under the [LICENSE](./LICENSE) license.

## Acknowledgments

Thanks to the IntelliJ Platform for providing the plugin development framework, and to related open source projects for inspiration:

- **[CodeTour](https://github.com/LefterisXris/CodeTour)** - VS Code code navigation plugin, providing design inspiration for process navigation features
- **[Bookmark-X](https://github.com/Nonoas/Bookmark-X)** - IntelliJ bookmark management plugin, providing reference implementation for editor integration

These projects' exploration in code bookmark management and navigation functionality has provided valuable experience and ideas for this project.
