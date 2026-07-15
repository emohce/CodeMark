# EzCodeMark Technical Details

Tool: codex
Date: 2026-06-12

## Sync Rule

Update this file when a maintained module's entrypoint, storage/data contract, integration boundary, key workflow, or verification command changes. Keep entries as module + technology + code address.

## Module Index

| Module | Technology / Mechanism | Code Address | Current Notes | Last Verified |
| --- | --- | --- | --- | --- |
| IntelliJ plugin descriptor | Gradle / IntelliJ Platform plugin XML | [../../build.gradle.kts](../../build.gradle.kts:1), [../../src/main/resources/META-INF/plugin.xml](../../src/main/resources/META-INF/plugin.xml:1) | Defines plugin packaging and IDE extension registration. | 2026-06-12 |
| Editor markers | Kotlin editor listeners/highlighters | [../../src/main/kotlin/emohce/presentation/editor/BookmarkDocumentListener.kt](../../src/main/kotlin/emohce/presentation/editor/BookmarkDocumentListener.kt:1), [../../src/main/kotlin/emohce/presentation/editor/highlighter/BookmarkHighlighterService.kt](../../src/main/kotlin/emohce/presentation/editor/highlighter/BookmarkHighlighterService.kt:1) | Tracks and renders bookmark/codemark state in editor surfaces. | 2026-06-12 |
| Actions and navigation | Kotlin IntelliJ actions | [../../src/main/kotlin/emohce/presentation/action/CreateNoteAtCaretAction.kt](../../src/main/kotlin/emohce/presentation/action/CreateNoteAtCaretAction.kt:1), [../../src/main/kotlin/emohce/presentation/action/CodemarkNavigationHelper.kt](../../src/main/kotlin/emohce/presentation/action/CodemarkNavigationHelper.kt:1) | User actions for creating, selecting, and navigating codemarks. | 2026-06-12 |
| Tool window | Kotlin UI/view model | [../../src/main/kotlin/emohce/presentation/toolwindow/CodeMarkToolWindowFactory.kt](../../src/main/kotlin/emohce/presentation/toolwindow/CodeMarkToolWindowFactory.kt:1), [../../src/main/kotlin/emohce/presentation/toolwindow/BookmarkViewModel.kt](../../src/main/kotlin/emohce/presentation/toolwindow/BookmarkViewModel.kt:1) | Main tool window and state model. | 2026-06-12 |
| Settings | Kotlin persistent config | [../../src/main/kotlin/emohce/presentation/settings/EzCodeMarksSettingsState.kt](../../src/main/kotlin/emohce/presentation/settings/EzCodeMarksSettingsState.kt:1), [../../src/main/kotlin/emohce/presentation/settings/EzCodeMarksSettingsConfigurable.kt](../../src/main/kotlin/emohce/presentation/settings/EzCodeMarksSettingsConfigurable.kt:1) | Plugin settings state and configuration UI. | 2026-06-12 |
