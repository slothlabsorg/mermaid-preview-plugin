# Contributing to Mermaid Preview

Thanks for considering a contribution. This doc covers dev setup, architecture, roadmap, and how to claim / submit work.

## Dev loop

Requirements: **JDK 17** (any distribution — Corretto, Temurin, Zulu…).

```bash
git clone https://github.com/slothlabsorg/mermaid-preview-plugin.git
cd mermaid-preview-plugin

./gradlew runIde        # launches a sandbox IntelliJ Community 2024.1 with the plugin loaded
./gradlew buildPlugin   # produces build/distributions/mermaid-preview-<ver>.zip
./gradlew verifyPlugin  # runs the official plugin verifier (compatibility checks)
./gradlew compileKotlin # fast compile-only — useful before committing
```

Open the project in IntelliJ IDEA; it'll pick up the Gradle model automatically. The first `runIde` downloads IC-2024.1 (~1.5GB) into `.intellijPlatform/` — cached afterwards.

## Code map

| Path | Purpose |
|------|---------|
| `src/main/kotlin/org/slothlabs/mermaidpreview/MermaidToolWindowFactory.kt` | `ToolWindowFactory` entry; creates one panel per project |
| `src/main/kotlin/org/slothlabs/mermaidpreview/MermaidPreviewPanel.kt` | `JPanel` holding `JBCefBrowser`, wiring to file + document listeners, debounced refresh |
| `src/main/kotlin/org/slothlabs/mermaidpreview/MermaidBlockExtractor.kt` | Pure function: `String → List<MermaidBlock>`. Regex-based fenced-block parser |
| `src/main/kotlin/org/slothlabs/mermaidpreview/MermaidResourceManager.kt` | APP-level service, extracts bundled JS/HTML to a temp dir on first use |
| `src/main/resources/web/preview.html` | Embedded web view — cards, toggle, mermaid init |
| `src/main/resources/mermaid/mermaid.min.js` | Bundled mermaid 10.9.3 |
| `src/main/resources/META-INF/plugin.xml` | Plugin descriptor |
| `.github/workflows/build.yml` | CI: verify + build on every push/PR |
| `.github/workflows/release.yml` | Tag `vX.Y.Z` → builds and publishes a GitHub Release with the zip |

## How data flows

1. User switches to a `.md` file → `FileEditorManagerListener.selectionChanged` fires.
2. `MermaidPreviewPanel.updateFor(file)` attaches a `DocumentListener` and schedules an immediate refresh via `Alarm` (250ms debounce on edits).
3. `refresh()` reads the document text on the EDT (wrapped in `runReadAction`), invokes `MermaidBlockExtractor.extract(text)` → `List<MermaidBlock>`.
4. `sendPayload` serializes `{status, fileName, blocks}` to JSON with Gson, escapes for JS string-literal context, and calls `cefBrowser.executeJavaScript("window.setPayload(...)")`.
5. `preview.html` renders one card per block. Clicking **Diagram** or **Code** swaps the card body locally — no round-trip to Kotlin.

## Style

- Kotlin, 4-space indent, no trailing commas in multi-line params (matches IntelliJ defaults).
- One top-level class per file. Package layout follows `org.slothlabs.mermaidpreview.*`.
- Prefer plain platform APIs. No new runtime dependencies unless there's a strong reason.
- Keep the webview self-contained — every asset we need must live under `src/main/resources/` and be picked up by `MermaidResourceManager`.

## Releasing

1. Bump `version` in `build.gradle.kts` and `META-INF/plugin.xml` (keep them in sync).
2. Commit: `chore: bump to X.Y.Z`.
3. Tag: `git tag vX.Y.Z && git push origin main --tags`.
4. `.github/workflows/release.yml` picks up the tag, runs `./gradlew buildPlugin`, and publishes a GitHub Release with the zip and auto-generated changelog.

## Roadmap

Status legend: `[ ]` = open · `[~]` = in progress · `[x]` = done in `main`.

### v0.1 — MVP ✅

- [x] Tool window that auto-populates from the active `.md` file
- [x] ```` ```mermaid ```` fenced-block extractor
- [x] Per-block toggle **Diagram ↔ Code**
- [x] Debounced live refresh on file edits
- [x] Theme follow (dark/light)
- [x] Bundled mermaid.js (offline)
- [x] Graceful fallback if JCEF is disabled

### v0.1.1 — quality of life 🚀 (just shipped)

- [x] Export per-block: **Download SVG** / **Download PNG** buttons in the card header
- [x] Copy-to-clipboard button for block source
- [x] Actionable error overlay (highlights offending line, shows mermaid parser message)
- [x] Screenshots in the README

### v0.2 + v0.3 — editor integration 🚀 (just shipped)

- [x] Click on the card header to jump to that line in the editor (JBCefJSQuery ↔ OpenFileDescriptor)
- [x] Unit tests for `MermaidBlockExtractor` (happy path, multiple blocks, tilde fence, CRLF, unclosed)
- [x] Preserve scroll position across refreshes (await all renders then `scrollTo`)
- [x] Inline editor inlays: rendered SVG appears below each closing fence via `InlayModel.addBlockElement`
- [x] Gutter icon on every ` ```mermaid ` fence — click to focus Mermaid Preview tool window
- [x] Standalone `.mmd` / `.mermaid` files treated as a single diagram block
- [x] Project-level `MermaidSvgCache` — tool window pushes SVG per block for inlay consumption

### v0.4 — richer web view

- [ ] Pan + zoom per diagram (mouse wheel + drag)
- [ ] Fullscreen mode for a single block
- [ ] Live error overlay with line pointer (mermaid's parser reports offsets — we can highlight the offending line in the source view)
- [ ] Settings panel: default view (Diagram vs Code), theme override, mermaid config overrides
- [ ] Remember toggle state per-file across IDE restarts

### v0.5 — distribution

- [ ] Publish to [JetBrains Marketplace](https://plugins.jetbrains.com/) so users can install via `Settings → Plugins → Marketplace`
- [ ] Plugin signing in CI (`signPlugin` task, keys stored as repo secrets)
- [ ] `--snapshot` builds published on every push to `main` as a pre-release

### ideas / backlog

- [ ] Non-JCEF fallback: shell out to `mmdc` (mermaid CLI) and embed the SVG as `ImageIcon`
- [ ] Diagram caching — don't re-render unchanged blocks on refresh
- [ ] Hot-swap mermaid version from a setting (download at runtime, pin by version)
- [ ] Support **D2**, **PlantUML**, **Graphviz** fenced blocks via pluggable renderers
- [ ] Dashboard: "show all mermaid diagrams in the project" as a single scroll view

## Claiming work

- Check the [Issues](https://github.com/slothlabsorg/mermaid-preview-plugin/issues) tab for `good-first-issue` / `help-wanted` labels, OR pick anything from the roadmap.
- Comment on the issue (or open one referencing the roadmap item) before starting bigger work — avoids duplicates.
- Small fixes (typos, small bugs): PR directly, no issue needed.

## PR checklist

- [ ] `./gradlew verifyPlugin buildPlugin` passes locally
- [ ] Commit message in conventional form: `feat: …`, `fix: …`, `chore: …`, `docs: …`, `refactor: …`
- [ ] If you touched code inside `MermaidPreviewPanel` or `MermaidResourceManager`, manually validate via `./gradlew runIde`
- [ ] Screenshots attached for UI changes
- [ ] Roadmap item checked off in `CONTRIBUTING.md` if relevant

## Code of conduct

Be kind, concise, and direct. No drama, no rudeness. Disagreements are fine — personal attacks aren't.

## License

By contributing, you agree that your work will be licensed under the project's MIT License.
