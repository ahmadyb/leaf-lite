# LEAF LITE — Master Build Prompt (v1.0)

> **How to use this document:** Paste this entire file as the first message to your AI coding assistant (Claude, ChatGPT, Cursor, Gemini, etc.). If the tool supports attachments, also attach `leaf lite.html` — it is the visual source of truth. Every design token in this document was extracted from that mockup.

---

## 1. ROLE & MISSION

You are an expert Android engineer specializing in lightweight, high-performance apps for low-end devices. Your task is to build **Leaf Lite** — a complete, production-quality, fully buildable Android application — from scratch, delivering every file of the project.

**Product one-liner:** *A tiny viewer that opens almost any document without the weight.*

Leaf Lite is an **offline, multi-format document viewer** that:
- Discovers documents already on the device and lets users import files manually.
- Views **PDF, DOCX, XLSX, TXT, and Markdown** with clean, native rendering.
- Feels instant, weighs almost nothing, and runs on devices as old as **Android 6.0 (API 23)**.

---

## 2. NON-NEGOTIABLE CONSTRAINTS (read twice)

| # | Constraint | Detail |
|---|-----------|--------|
| 1 | **minSdk 23** (Android 6.0 Marshmallow) | Every API used must exist on API 23 or be guarded by `Build.VERSION.SDK_INT` checks. |
| 2 | **targetSdk / compileSdk = latest stable** | API 36 at time of writing; use newer if available. |
| 3 | **Tiny footprint** | Budget: **≤ 8 MB universal release APK, ≤ 5 MB Play (AAB) download size, ≤ 15 MB installed on device** (APK + ART artifacts + app data; expected realistically ~8–12 MB). No native libraries anywhere. Verify with `apkanalyzer` and report all numbers when done. |
| 4 | **Language/UI: Kotlin + classic XML Views + ViewBinding** | **NO Jetpack Compose** (size + older-device overhead). No Java. |
| 5 | **Zero heavyweight document libraries** | **Forbidden:** Apache POI, docx4j, PdfBox, MuPDF, Android PdfViewer (barteksc), JSoup, Gson/Moshi/kotlinx-serialization, any DI framework (Hilt/Koin/Dagger), RxJava, Glide/Coil/Picasso, Firebase/Analytics anything. |
| 6 | **Built-in PDF rendering** | Use `android.graphics.pdf.PdfRenderer` (available since API 21). |
| 7 | **Custom lightweight parsers** for DOCX, XLSX, MD | OOXML files are ZIP archives — parse the XML inside with Android's built-in `XmlPullParser`. Markdown gets a small hand-written parser. (Implementation specs in §7.) |
| 8 | **Fully offline** | Manifest must contain **no `INTERNET` permission**. No accounts, no analytics, no ads, no telemetry, no cloud sync. |
| 9 | **No stubs** | Every file you output must be complete and compile-ready. No `// TODO`, no `...`, no "rest of implementation here". |
| 10 | **Pixel-faithful to the mockup** | Match the design tokens in §5 and Appendix A exactly. Adapt iOS-isms to Android sensibly (§5.4). |
| 11 | **Persistence without an ORM** | Use raw `SQLiteOpenHelper` and `SharedPreferences`. **No Room.** |
| 12 | **Coroutines only** | `kotlinx-coroutines-android` for all async work. No AsyncTask, no raw threads leaking into UI code. |

When any choice arises that this document doesn't cover: **pick the option that is smaller in APK size and simpler to maintain**, and note the decision in a `DECISIONS.md` file you create at the project root.

---

## 3. FEATURE MAP (v1.0)

**P0 — must ship:**
- Library tab: auto-discovered documents, filter chips, search by filename, sort, document rows with type/size/date metadata.
- Starred tab: starred documents, star toggle from list rows and from the viewer.
- Viewer: full-screen slide-in viewer for PDF, DOCX, XLSX, TXT, MD with per-format renderers, back navigation, star toggle, error/unsupported states.
- Manual file import via Storage Access Framework (SAF), with persisted URI permissions.
- "Open with Leaf Lite": register as an `ACTION_VIEW` handler for the five supported MIME types.
- Settings tab: Night reading mode, Keep screen on, Compact list, Version, Supported formats.
- Runtime permission flow for API 23–28 (graceful denial → SAF import still works without any permission).

**P1 — ship if clean:**
- Sort bottom sheet (name/date/size, asc/desc) with persisted choice.
- Overflow menu: Rescan device, Import file, Clear cache.
- Multiple XLSX sheets via switcher chips.
- PDF pinch-zoom + double-tap zoom, page indicator "Page X of Y".
- "Open in another app" fallback for unsupported/corrupt files (via FileProvider).

**P2 — stretch (only if everything above is solid):**
- Inline images in DOCX (extract from `word/media/`).
- Monospace toggle in the TXT viewer.
- Last-opened position restore (PDF page, XLSX sheet).

**Out of scope for v1 (do NOT build):** document editing, annotations, PDF text selection/search, legacy binary formats (.doc/.xls/.ppt), EPUB, DRM files, cloud sync, widgets, multi-language UI (English only).

---

## 4. ARCHITECTURE OVERVIEW

- **Single-Activity** app (`MainActivity`) hosting three fragments (`LibraryFragment`, `StarredFragment`, `SettingsFragment`) switched by a custom bottom tab bar.
- The **Viewer** is a separate activity (`ViewerActivity`) so it can receive `ACTION_VIEW` intents directly; it enters with a slide-in-from-right transition and exits back to the list.
- Plain **MVVM-lite**: each screen has a ViewModel (`androidx.lifecycle`, no extra deps) exposing `StateFlow`s. No repository framework ceremony — a single `DocumentRepository` class is enough.
- **Layering:**
  - `data/` — `MediaStoreScanner`, `DocumentRepository`, `FileRegistryDb` (SQLiteOpenHelper), `Prefs` (SharedPreferences wrapper), `DocumentOpener` (content:// → parser input).
  - `parsers/` — `PdfEngine`, `DocxParser`, `XlsxParser`, `MarkdownParser`, `TextLoader`, plus `FormatDetector`.
  - `ui/` — fragments, viewer activity, adapters, custom views (tab bar, chip row, switch row, document row).
  - `util/` — formatting helpers (file size, relative date), insets helpers.

---

## 5. DESIGN SYSTEM (extracted from `leaf lite.html`)

### 5.1 Color tokens (light theme — the default and primary theme)

| Token | Value | Usage |
|---|---|---|
| `bg` | `#F6F8F6` | Screen background |
| `card` | `#FFFFFF` | Cards, rows, search field, chips |
| `text` | `#0E1A12` | Primary text; also the **active chip** fill |
| `muted` | `#78897E` | Secondary brand text ("Lite" in the wordmark) |
| `faint` | `#9DADA3` | Metadata, placeholders, section headers |
| `line` | `#E4E9E5` (mockup: `rgba(14,26,18,.07)` on white) | Hairline borders & dividers |
| `accent` | `#1FA463` | Active tab, links, kickers, switch ON, logo |
| `accent_soft` | `#E4F5EB` | Tag pill background in MD/TXT viewer |
| `star` | `#F0B429` | Starred state |
| `row_pressed` | `#F2F6F3` | Row press state |
| `chip_text` | `#5C6D63` | Inactive chip text |
| `icon_idle` | `#4A5A50` | Header icon buttons |
| `tab_idle` | `#9AA9A0` | Inactive tab color |

**Per-file-type colors** (icon tint; icon tile background = 12% tint of this color over white):

| Type | Color | Label |
|---|---|---|
| PDF | `#E5484D` | "PDF Document" |
| DOCX | `#2563EB` | "Word Document" |
| XLSX | `#16A34A` | "Spreadsheet" |
| TXT | `#64748B` | "Plain Text" |
| MD | `#8B5CF6` | "Markdown" |

**Dark/night theme** (Night reading mode): `bg #0E1210`, `card #171D19`, `text #E8EFE9`, `muted #9FB0A5`, `faint #7C8C82`, `line rgba(255,255,255,.08)`, accent unchanged `#1FA463`. **Sepia reading variant** (applies to the viewer body only when Night reading mode is on and the user is in a document): page background `#F7F1E3`, text `#3D3428`.

### 5.2 Shape, spacing, type

- Corner radii: cards/lists **18dp**, icon buttons & file-type tiles **12–13dp**, search field **15dp**, chips **11dp**, viewer doc pages **16dp**.
- Card borders: 1dp `line`; elevation is subtle — use `0 2dp 10dp -6dp rgba(14,26,18,.18)`-equivalent (elevation 1–2dp max, or a 9-patch-free approach with stroke + small elevation).
- Screen side padding: **16dp** for cards, **20–22dp** for headers/search/chips.
- Typography (system font — Roboto; **do not bundle fonts**):
  - Brand: 23sp, extra-bold, tight letter-spacing (−0.03em equivalent).
  - File name: 14.5sp semibold, single line, ellipsized.
  - File sub-line: 12sp, `faint` color — format: `{Type Label} · {Size} · {Relative date}` (e.g., "PDF Document · 2.4 MB · Today, 9:41 AM").
  - Section header: 13sp, bold, uppercase, 0.08em letter-spacing, `faint`.
  - Chip: 13sp semibold. Search placeholder: 14.5sp `faint`.
  - Tab labels: 10.5sp semibold.
- All icons are **vector drawables** converted from the SVG paths in **Appendix B**. Stroke icons: 1.8–2.2dp stroke, round caps/joins, tinted via `app:tint`.

### 5.3 Signature components

1. **Brand header** — leaf-logo tile (32dp, gradient `#26B672 → #12864F`, rounded 11dp, white leaf icon 18dp) + wordmark "Leaf **Lite**" (Lite in `muted`) + two 38dp icon buttons (Search, More) with 1dp `line` border on white, press scale-down to 0.92.
2. **Search field** — white card, 15dp radius, 1dp border, magnifier icon + placeholder "Search documents", live-filters the list as the user types.
3. **Filter chips row** — horizontally scrollable, no scrollbar: All / PDF / Word / Excel / Text / Markdown. Inactive: white, border, `chip_text`. Active: filled `text` color (#0E1A12), white label. Press scale 0.95.
4. **Document list card** — one card containing all rows; dividers are inset hairlines starting at **62dp from the left** (aligned past the icon). Row = 38dp tinted type-icon tile + name/sub + trailing gold star (if starred) or faint chevron. Row press background `#F2F6F3`.
5. **Bottom tab bar** — 3 tabs (Library, Starred, Settings), white ~92% opaque with subtle top border, height 64dp + navigation-bar inset. Active tab: `accent` color for icon+label; idle: `tab_idle`.
6. **Viewer** — full-screen, slides in from the right (300ms, decelerate-style curve), header with back chevron / centered title + sub-line ("{Type Label} · {Size}") / star toggle button (turns gold + filled when starred). Body scrolls.
7. **Settings switch rows** — label + small `faint` sub-label on the left; custom pill switch on the right (46×28dp track, 22dp knob, ON = `accent` track, knob slides with a spring-like decelerate animation).
8. **Empty state** — centered faint icon (38dp, 50% opacity) + "No documents here" 13.5sp `faint`.

### 5.4 Android adaptations (mockup → real app)

- Ignore the phone frame/status bar/dynamic island — that's mockup chrome. Go **edge-to-edge**: content draws behind the status bar (light status bar icons on API 23+ via `SYSTEM_UI_FLAG_LIGHT_STATUS_BAR` / `WindowInsetsControllerCompat`), header and tab bar consume window insets.
- Add Material ripple feedback on all pressable elements (keep the mockup's scale-down press animation on icon buttons/chips/tabs).
- Hardware Back = viewer close = tab-bar-level up navigation. Support predictive back (targetSdk 33+ behavior) without breaking API 23.
- Touch targets ≥ 48dp even where visual size is smaller (icon buttons get expanded hit areas).
- All icon-only buttons carry `contentDescription`s.

---

## 6. SCREENS & INTERACTIONS

### 6.1 Library tab (default tab)
Layout top→bottom: brand header → search field → chips row → section header (`{Filter label} · {count}`, e.g. "All documents · 8", with a "Sort" text button in accent on the right) → list card.

- Tapping a row opens `ViewerActivity` for that document (slide-in-from-right).
- Tapping the gold star zone of a row toggles starred state instantly (row chevron ⇄ star swaps without reloading the screen).
- Chips filter the list by type; "All" shows everything. Filter choice persists during the session.
- Search matches file **name** (case-insensitive contains), applied live on top of the active chip filter. Section header count updates. An inline ✕ appears to clear the query.
- "Sort" opens a bottom sheet: Name A→Z, Name Z→A, Newest first, Oldest first, Largest first, Smallest first. Choice persists in SharedPreferences and applies to Library + Starred.
- Overflow (⋯) menu: **Rescan device**, **Import file** (SAF `ACTION_OPEN_DOCUMENT`, multi-select, mime-filtered to the 5 types, `takePersistableUriPermission`), **Clear cache**.
- Scanning runs asynchronously; the list populates as results arrive. Never block the UI thread on I/O.

### 6.2 Starred tab
Brand header → section header "Starred · {count}" → list card of starred docs only (same row component, same sort setting). Empty state when nothing is starred. Starring/unstarring from here or from the viewer updates both tabs.

### 6.3 Settings tab
Brand header → profile card (48dp gradient avatar tile with document icon, title **"Document library"**, sub **"{N} documents · scanned {relative time} ago"**) → section "Viewing" with three switch rows → section "About" with two static rows (chevron trailing):

| Setting | Sub-label | Default | Behavior |
|---|---|---|---|
| Night reading mode | "Sepia & dark themes" | OFF | ON → `AppCompatDelegate.setDefaultNightMode(MODE_NIGHT_YES)` (recreate activities smoothly) and viewer doc pages use the sepia palette; OFF → `MODE_NIGHT_FOLLOW_SYSTEM`. |
| Keep screen on | "While viewing documents" | ON | While ON, `ViewerActivity` sets `FLAG_KEEP_SCREEN_ON`; cleared in `onPause`. |
| Compact list | "Show more rows" | OFF | ON → row height ~52dp, icon tile 32dp, tighter padding; OFF → standard 62dp rows. Applies to Library + Starred immediately. |

About rows: **Version** (sub: "Leaf Lite {versionName}"), **Supported formats** (sub: "PDF, DOCX, XLSX, TXT, MD"). Tapping them shows a small info dialog (no extra screens).

### 6.4 Viewer (`ViewerActivity`)
- Header: back button (chevron-left icon button) · centered title block (file name 14.5sp semibold ellipsized + sub "{Type Label} · {Size}" 11sp faint) · star toggle.
- Body per format (§7). All bodies are vertically scrollable; backgrounds use `bg` (or sepia/dark variant per Night reading mode).
- While parsing/rendering a file > ~1 MB, show a lightweight centered progress state; never an ANR.
- Failure states: corrupt/encrypted/unsupported → centered empty-state component: "Preview not available" + (P1) an "Open in another app" button firing `ACTION_VIEW` with a FileProvider URI.
- Rotation preserves scroll/page position (save in instance state). Star state writes through to the DB immediately.

### 6.5 First-run & permissions (critical for API 23–28)
1. On API 29+: **no permission needed** — scan MediaStore directly. Never show a permission dialog.
2. On API 23–28: Library starts with a friendly inline card: "Leaf Lite needs storage access to find your documents" + **Grant access** button → `READ_EXTERNAL_STORAGE` runtime request (with explanation dialog first if `shouldShowRequestPermissionRationale`).
3. On denial: no nagging. The inline card changes to: "No storage access — you can still open files manually" + **Import file** button (SAF works without any permission). Re-request only when the user taps Grant/Import again.

---

## 7. DOCUMENT PIPELINE (the engineering core)

### 7.1 Discovery & sources
- **MediaStore scan** (`MediaStore.Files` content URI, projection: `_id`, `DISPLAY_NAME`, `SIZE`, `DATE_MODIFIED`, `MIME_TYPE`, `RELATIVE_PATH`/`DATA` where available):
  - API 29+: query without permission; selection on `MIME_TYPE` IN the 5 supported types OR `_display_name` LIKE-matching the 5 extensions (mime is often null — always fall back to extension via `FormatDetector`).
  - API 23–28: same query after `READ_EXTERNAL_STORAGE` is granted.
- **SAF imports**: persist URI permission; store the `content://` URI itself (never a raw path) in the registry.
- **ACTION_VIEW intents**: open directly from the incoming URI without requiring it to exist in the registry (but do register/star-enable it after first open).
- **Always open files through `ContentResolver`** (`openInputStream` / `openFileDescriptor`) — raw `File` paths break on scoped storage.

### 7.2 Registry DB — `SQLiteOpenHelper`, one table
```sql
CREATE TABLE documents (
  id            INTEGER PRIMARY KEY AUTOINCREMENT,
  uri           TEXT NOT NULL UNIQUE,      -- content:// URI (MediaStore or SAF)
  display_name  TEXT NOT NULL,
  mime          TEXT,
  type          TEXT NOT NULL,             -- pdf|docx|xlsx|txt|md
  size          INTEGER NOT NULL DEFAULT 0,
  date_modified INTEGER NOT NULL DEFAULT 0,
  starred       INTEGER NOT NULL DEFAULT 0,
  last_opened   INTEGER NOT NULL DEFAULT 0,
  source        TEXT NOT NULL DEFAULT 'scan' -- scan|import|intent
);
```
Rescans upsert by URI (preserve `starred`/`last_opened`), and prune rows whose URIs no longer resolve. Relative dates ("Today, 9:41 AM", "Yesterday", "Monday") computed with `DateUtils`/custom formatter from `date_modified`.

### 7.3 Format engines

**PDF — `android.graphics.pdf.PdfRenderer` (built-in, zero deps)**
- Obtain a `ParcelFileDescriptor` via `contentResolver.openFileDescriptor(uri, "r")` (works for MediaStore & SAF URIs; no cache copy needed). Handle password-protected PDFs (`SecurityException`/IOException) → unsupported state.
- Render pages to `Bitmap`s sized to `viewWidthPx × (pageAspect)`, capped at ~2× screen density to bound memory. Render on `Dispatchers.Default`; recycle bitmaps on view recycle.
- UI: vertical `RecyclerView`, one page per row (16dp side margins, white page card with 16dp radius + subtle shadow — matches the mockup's "doc-page" look), keep ±1 pages rendered around the viewport, placeholder shimmer while a page renders.
- Footer/indicator: "Page X of Y" (mockup-accurate), updates on scroll.
- P1: pinch-zoom (`ScaleGestureDetector`) + double-tap zoom on the page bitmap; zoom resets when scrolling to a new page.

**DOCX — custom parser (ZIP + XmlPullParser)**
- `ZipFile`/`ZipInputStream` over the content stream → read `word/document.xml`.
- Parse with `XmlPullParser`, mapping:
  - `w:p` → paragraph; `w:pPr/w:pStyle` `Heading1..6`/`Title` → type scale (H1 21sp bold … H6 14sp semibold, tight letter-spacing like the mockup); `w:jc` → alignment; `w:numPr` → bulleted/numbered list item with indent (alternate • and 1. by `w:numId` tracking — simple sequential numbering is acceptable).
  - `w:r/w:t` → text runs; `w:rPr`: `w:b`→bold, `w:i`→italic, `w:u`→underline, `w:strike`, `w:sz` (half-points → sp, clamped), `w:color`.
  - `w:br` → newline; `w:tab` → tab stop; `w:hyperlink` → clickable span (opens browser).
  - `w:tbl/w:tr/w:tc` → native table (reuse the XLSX sheet styling: header row `#F1F6F2`, hairline borders, 12sp cells).
- Output: a single `SpannableStringBuilder` (+ table views interleaved in a `LinearLayout` inside a `ScrollView`). Document title block at top like the mockup ("Project Brief" kicker style is optional; content fidelity first).
- P2: inline images — extract `word/media/*` referenced by `w:drawing`/`r:embed` via `word/_rels/document.xml.rels`; decode with bounds; skip gracefully on failure.
- Skip without crashing: headers/footers, footnotes, comments, tracked changes (accept inserted text), equations.

**XLSX — custom parser (ZIP + XmlPullParser)**
- Read `xl/workbook.xml` (sheet names + order via `r:id` → `xl/_rels/workbook.xml.rels`), `xl/sharedStrings.xml` (build the SST string array), then the active sheet's `xl/worksheets/sheetN.xml`.
- Cell handling: `r` attribute ("B7") → column index; `t="s"` → SST index from `<v>`; `t="inlineStr"` → `<is><t>`; `t="b"` → TRUE/FALSE; default numeric `<v>` → format: integers without `.0`, otherwise up to 2 decimals; empty `<v>` → blank. Dates via `styles.xml` numFmt: **best-effort** — if a cell style maps to a built-in date numFmt id (14–22, 45–47), convert the serial (epoch 1899-12-30) to `yyyy-MM-dd`; otherwise show the raw number. Document this limitation in `DECISIONS.md`.
- UI: horizontally + vertically scrolling grid (`RecyclerView` with a custom grid adapter or nested scrolling approach): first row rendered as a sticky-style header (uppercase 10.5sp labels, `#F1F6F2` background — mockup "sheet" styling), numeric cells right-aligned with tabular figures, hairline row dividers, 10–12dp cell padding, card container with 16dp radius + border.
- Footer: "Sheet {i} of {n} · {rows} rows". Multiple sheets → switcher chips above the grid (P1). Parse lazily: first sheet on open, others on demand; cap at first ~5,000 rows for v1 with a "showing first 5,000 rows" note beyond that.

**TXT — trivial loader**
- Charset detection: BOM check (UTF-8/UTF-16LE/BE) → try UTF-8 strict → fallback windows-1252/ISO-8859-1.
- Render in a comfortable reading layout (mockup `.md` styling): 14sp body, 1.65 line-height, 22–26dp padding, `text #3D4B43`-equivalent body color. Word wrap ON. P2: monospace toggle + horizontal scroll mode for wide logs.

**Markdown — small custom line-based parser (~300 lines)**
- Supported: ATX headings `#`–`######`; paragraphs; **bold** `**`/`__`; *italic* `*`/`_`; `inline code`; fenced code blocks ``` (monospace, `#F6F9F7` block background, 10dp radius); blockquotes `>` (accent left bar); unordered lists `-`/`*`/`+` with one level of nesting via indent; ordered lists `1.`; horizontal rules `---`; links `[t](u)` (accent color, browser intent); images `![alt](u)` → skip with alt-text placeholder.
- Rendering style follows the mockup `.md` screen: small uppercase accent tag pill ("Markdown") → title H1 21sp → body 14sp/1.65 → H2 15sp sections.

### 7.4 Format detection
Extension first (`.pdf .docx .xlsx .txt .md .markdown .text`), MIME as corroboration, magic-byte sniff for extension-less files (`%PDF-` → pdf; `PK\x03\x04` → docx/xlsx, disambiguate by probing for `word/document.xml` vs `xl/workbook.xml` in the zip entries). Unknown → unsupported state.

---

## 8. COMPATIBILITY MATRIX & GOTCHAS (API 23 focus)

| Concern | Rule |
|---|---|
| Runtime permissions | `READ_EXTERNAL_STORAGE` requested **only** on API ≤ 28 (`maxSdkVersion="28"` in manifest). Never request `WRITE_EXTERNAL_STORAGE`. Never request `MANAGE_EXTERNAL_STORAGE`. |
| Scoped storage | API 29+: MediaStore queries need no permission; SAF imports use persisted URI permissions. All file access through `ContentResolver`. |
| Status bar | Light icons via `WindowInsetsControllerCompat` (falls back to `SYSTEM_UI_FLAG_LIGHT_STATUS_BAR`, which exists since API 23 — the exact minSdk). |
| Vector drawables | Only via AppCompat (`app:srcCompat`) so they work pre-API 24 rendering paths; no `AnimatedVectorDrawable` requirements. |
| Font weights | `Typeface.create(family, weight, italic)` is API 28 — on API 23–27 use `Typeface.BOLD`/`textStyle` only. Semibold ≈ `sans-serif-medium` family (available since API 16+ via family string). |
| Notifications/Jobs | None needed — the app does no background work. No WorkManager, no services. |
| FileProvider | Configure `androidx.core` FileProvider (zero added size) for the P1 "Open in another app" cache-file sharing. |
| Process death | Viewer restores file URI + scroll/page position from saved instance state. |
| Large files | Guard parsers with try/catch + 30 MB stream-read ceiling; beyond that → unsupported state rather than OOM. No `largeHeap`. |

---

## 9. SIZE ENGINEERING CHECKLIST (enforce before declaring done)

1. `isMinifyEnabled = true`, `isShrinkResources = true`, R8 **full mode** in release; write minimal, correct ProGuard rules (only what reflection/XML parsing genuinely needs).
2. All icons = vector drawables (Appendix B). **No PNG bitmaps** except the launcher icon set (adaptive icon: vector foreground leaf on gradient background; provide `mipmap-anydpi-v26` + legacy fallbacks generated from the same vector).
3. Single language: `resConfigs += "en"`. No bundled fonts. No emoji image sets.
4. Dependency list is exactly §10 — nothing else may be added without a size justification in `DECISIONS.md`.
5. No native code, no NDK, no ABI splits needed.
6. Build both `assembleRelease` (universal APK) and `bundleRelease`; run `apkanalyzer apk size` / `download-size`; **report both numbers** and confirm ≤ 8 MB / ≤ 5 MB. If an emulator/device is available, install and also measure the on-device footprint (`adb shell dumpsys diskstats`, or `du` over `/data/app/<pkg>-*` + `/data/data/<pkg>` after a first scan) and confirm ≤ 15 MB installed.
7. Audit resources: no unused drawables/styles (shrinkResources handles most; double-check `res/` for leftovers).

---

## 10. EXACT TECH STACK

**Toolchain:** latest stable Android Studio project conventions — Gradle 8.x, AGP 8.6+, Kotlin 2.0+, JVM target 17, `compileSdk`/`targetSdk` = latest stable (36+).

**Dependencies (the complete allow-list):**
```kotlin
implementation("androidx.core:core-ktx:1.13.1")            // 1.15+ only if compileSdk demands it
implementation("androidx.appcompat:appcompat:1.7.0")
implementation("androidx.recyclerview:recyclerview:1.3.2")
implementation("com.google.android.material:material:1.12.0")  // bottom sheet, ripple, sheet theming
implementation("androidx.constraintlayout:constraintlayout:2.1.4")
implementation("androidx.fragment:fragment-ktx:1.8.5")
implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.7")
implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
```
`viewBinding = true`. No kapt/ksp. No test-only deps beyond `junit` + `androidx.test` basics for a smoke test.

**Release signing:** wire `signingConfig` placeholders reading from `keystore.properties` (git-ignored) with a documented fallback to debug signing so the project builds out of the box.

---

## 11. PROJECT STRUCTURE (deliver every file)

```
LeafLite/
├── build.gradle.kts, settings.gradle.kts, gradle.properties, keystore.properties.example
├── DECISIONS.md
└── app/
    ├── build.gradle.kts, proguard-rules.pro
    └── src/main/
        ├── AndroidManifest.xml
        ├── res/
        │   ├── values/ (colors.xml, themes.xml, strings.xml, dimens.xml)
        │   ├── values-night/ (colors override)
        │   ├── drawable/ (~20 vector icons from Appendix B, gradients, backgrounds)
        │   ├── layout/ (activity_main, activity_viewer, fragment_library, fragment_starred,
        │   │            fragment_settings, item_document, item_pdf_page, item_sheet_row,
        │   │            sheet_sort, view_empty, view_permission_card)
        │   ├── xml/ (file_paths.xml, backup rules)
        │   ├── anim/ (slide_in_right, slide_out_right)
        │   └── mipmap-anydpi-v26/ (adaptive icon)
        └── java/com/leaflite/viewer/
            ├── MainActivity.kt, ViewerActivity.kt, LeafLiteApp.kt
            ├── ui/library/ (LibraryFragment, LibraryViewModel, DocumentAdapter)
            ├── ui/starred/ (StarredFragment, StarredViewModel)
            ├── ui/settings/ (SettingsFragment, SettingsViewModel)
            ├── ui/viewer/ (ViewerViewModel, PdfPageAdapter, SheetAdapter, ViewerRenderer)
            ├── ui/widgets/ (BottomTabBar, ChipRow, SwitchRow, DocumentRowView)
            ├── data/ (DocumentRepository, MediaStoreScanner, FileRegistryDb, Prefs, Document models)
            ├── parsers/ (FormatDetector, PdfEngine, DocxParser, XlsxParser, MarkdownParser, TextLoader)
            └── util/ (Formatters, InsetsExt, FileSize)
```

Package id: `com.leaflite.viewer`. App label: **Leaf Lite**. Version: `1.0.0` (versionCode 1).

**Manifest essentials:**
- `<uses-permission android:name="android.permission.READ_EXTERNAL_STORAGE" android:maxSdkVersion="28"/>` — and **nothing else** (explicitly: no INTERNET).
- `MainActivity`: MAIN/LAUNCHER, `DayNight`-based theme, edge-to-edge.
- `ViewerActivity`: `exported="true"`, `android:configChanges` minimal, intent-filters for `ACTION_VIEW` (+ `ACTION_SEND` is NOT needed) with `<data android:mimeType="..."/>` for:
  `application/pdf`, `application/vnd.openxmlformats-officedocument.wordprocessingml.document`, `application/vnd.openxmlformats-officedocument.spreadsheetml.sheet`, `text/plain`, `text/markdown`, `text/x-markdown` — schemes `content` and `file`. Category DEFAULT + BROWSABLE not required; add `<category android:name="android.intent.category.DEFAULT"/>`.
- FileProvider declared with `file_paths.xml` (cache dir) for P1 open-elsewhere.

---

## 12. PERFORMANCE REQUIREMENTS

- Cold start → interactive Library ≤ **1.5 s** on a low-end API 23 emulator profile; scanning happens async after first frame.
- List scrolling at 60 fps with 1,000+ documents (RecyclerView + stable IDs; rows contain no bitmaps).
- PDF first visible page ≤ **800 ms** for a typical 2 MB file; ≤ 3 page bitmaps alive beyond the viewport; bitmaps recycled deterministically.
- All parsing off the main thread (`Dispatchers.IO/Default`); cancellation on viewer close; progress UI for > 1 MB non-PDF files.
- Memory: the app must survive a 50 MB XLSX-cell-count stress file by capping rows (§7.3) and never enable `largeHeap`.

---

## 13. BUILD MILESTONES (implement in order; keep the app buildable at every step)

| # | Milestone | Acceptance gate |
|---|---|---|
| M0 | Gradle scaffold, theme tokens, vector icons, MainActivity + tab bar shell + 3 fragment stubs | Builds; tabs switch; visuals match mockup chrome |
| M1 | DB + MediaStore scan + SAF import + Library list (rows, chips, search, sort, star toggle) + Starred tab + permission flow | Real files from the device appear, filter/search/sort/star all persist |
| M2 | ViewerActivity shell (slide-in, header, star) + TXT + Markdown engines | .txt/.md render per mockup styles |
| M3 | PDF engine (PdfRenderer, paged RecyclerView, indicator, zoom) | 12-page PDF scrolls smoothly on API 23 emulator |
| M4 | DOCX engine (headings, runs, lists, tables, links) | Real-world .docx (headings/bold/table/list) renders correctly |
| M5 | XLSX engine (SST, grid, header row, sheets, footer) | Multi-sheet .xlsx with numbers/strings/dates renders |
| M6 | Settings tab wired (night mode + sepia, keep screen on, compact list), overflow actions, ACTION_VIEW registration, open-elsewhere fallback | Every setting demonstrably works |
| M7 | Size & perf pass: R8/shrink, resource audit, apkanalyzer report, API 23 + 34 device/emulator QA, crash pass (corrupt file, denied permission, rotation, process death) | Size budgets met (§9.6); all P0s green |

---

## 14. DEFINITION OF DONE

- [ ] `./gradlew assembleRelease bundleRelease` succeeds from a clean checkout with zero manual edits.
- [ ] Runs on API 23 and API 34+ emulators; no crash in the flows of §13.
- [ ] All five formats render real sample files correctly; unsupported/corrupt files degrade gracefully.
- [ ] Star, filter, sort, search, settings all persist across app restarts.
- [ ] "Open with → Leaf Lite" works from another app (e.g., a file manager) for all five MIME types.
- [ ] No INTERNET permission; no network code paths exist.
- [ ] Universal release APK ≤ 8 MB, AAB download size ≤ 5 MB (apkanalyzer output included in your final message), and installed on-device footprint ≤ 15 MB including app data after a first library scan.
- [ ] Visuals match §5 tokens: colors, radii, spacing, type scale, per-type icon colors, star gold, active-chip inversion.
- [ ] Edge-to-edge with correct insets on API 23 and API 34; light status bar icons in light theme.
- [ ] Content descriptions on all icon-only controls; ≥ 48dp touch targets.
- [ ] `DECISIONS.md` documents every judgment call you made.

---

## 15. OUTPUT CONTRACT (how you must respond)

1. Start with a 10-line implementation plan, then deliver the **complete project file-by-file** (full contents, correct paths), grouped by milestone M0→M7 or by module — but never partial files.
2. After the code: build instructions, the apkanalyzer size numbers, and anything you decided beyond this spec (mirrored into `DECISIONS.md`).
3. If a requirement is impossible or contradictory, implement the closest size-light alternative, ship it, and document it — **do not stop to ask** unless a P0 behavior is genuinely ambiguous.

---

---

# APPENDIX A — Design token quick table (from mockup CSS)

```
--bg:#F6F8F6; --card:#FFFFFF; --text:#0E1A12; --muted:#78897E; --faint:#9DADA3;
--line:rgba(14,26,18,.07); --accent:#1FA463; --accent-soft:#E4F5EB; --radius:18px;
Type colors: pdf #E5484D · doc #2563EB · sheet #16A34A · text #64748B · markdown #8B5CF6
Star #F0B429 · chip text #5C6D63 · tab idle #9AA9A0 · row press #F2F6F3 · body text #3D4B43
Logo gradient: #26B672 → #12864F (145deg) · PDF chart bars: #3ECB83 → #1A9A60
Sheet header bg #F1F6F2 · code/chart block bg #F6F9F7 · skeleton line bg #E9EFEA
Divider inset in list: 62dp (starts after the 38dp icon + 13dp gap + 14dp padding)
Tab bar: ~92% white + blur → Android: #EBFFFFFF solid + 1dp top border
```

# APPENDIX B — Icon source paths (24×24 viewBox; convert to VectorDrawables)

All stroke icons: `fill=none stroke=currentColor stroke-width≈1.9 linecap=round linejoin=round` unless noted.

- **Leaf logo:** `M11 20A7 7 0 0 1 9.8 6.1C15.5 5 17 4.48 19 2c1 2 2 4.18 2 8 0 5.5-4.78 10-10 10Z` + `M2 21c0-3 1.85-5.36 5.08-6C9.5 14.52 12 13 13 12`
- **PDF file:** `M6 2h7l5 5v13a2 2 0 0 1-2 2H6a2 2 0 0 1-2-2V4a2 2 0 0 1 2-2z` + `M13 2v5h5` + `M8 13h6M8 17h4`
- **DOC file:** `M6 2h7l5 5v13a2 2 0 0 1-2 2H6a2 2 0 0 1-2-2V4a2 2 0 0 1 2-2z` + `M13 2v5h5` + `M8 13h8M8 17h5`
- **Sheet (XLSX):** `<rect x="3" y="4" width="18" height="16" rx="2.5"/>` + `M3 10h18M9 10v10M15 10v10`
- **Text (TXT):** `M4 6h16M4 12h16M4 18h9`
- **Markdown (MD):** `<rect x="2.5" y="5" width="19" height="14" rx="2"/>` + `M6 15V9l2.8 3L11.6 9v6` + `M16.5 9v4.5` + `M14.8 12.2l1.7 1.8 1.7-1.8` (M-shape + down arrow)
- **Star (filled/outline):** `m12 3 2.7 5.5 6.1.9-4.4 4.3 1 6-5.4-2.9-5.4 2.9 1-6L3.2 9.4l6.1-.9z`
- **Chevron right:** `m9 5 7 7-7 7` · **Chevron left (back):** `m15 5-7 7 7 7`
- **Search:** `<circle cx="11" cy="11" r="7"/>` + `m20 20-3.5-3.5`
- **More (horizontal dots, filled):** circles r=2 at (5,12), (12,12), (19,12)
- **Tab — Library (two books):** `M4 4.5A1.5 1.5 0 0 1 5.5 3H9a1.5 1.5 0 0 1 1.5 1.5v15A1.5 1.5 0 0 1 9 21H5.5A1.5 1.5 0 0 1 4 19.5z` + `M13.5 4.5A1.5 1.5 0 0 1 15 3h3.5A1.5 1.5 0 0 1 20 4.5v15a1.5 1.5 0 0 1-1.5 1.5H15a1.5 1.5 0 0 1-1.5-1.5z`
- **Tab — Starred:** the star path above
- **Tab — Settings (sliders):** `M4 21v-6M4 11V3M12 21v-9M12 8V3M20 21v-4M20 13V3M1.5 15h5M9.5 8h5M17.5 17h5`
- **X (clear search):** `M6 6l12 12M18 6L6 18`

# APPENDIX C — Canonical copy strings

```
app_name: Leaf Lite
search_hint: Search documents
chips: All · PDF · Word · Excel · Text · Markdown
section_all: All documents · %d      section_starred: Starred · %d
empty_list: No documents here
viewer_unsupported: Preview not available
open_elsewhere: Open in another app
settings_library_title: Document library
settings_library_sub: %1$d documents · scanned %2$s ago
s_night: Night reading mode / Sepia & dark themes
s_screen: Keep screen on / While viewing documents
s_compact: Compact list / Show more rows
s_version: Version / Leaf Lite %s
s_formats: Supported formats / PDF, DOCX, XLSX, TXT, MD
perm_title: Leaf Lite needs storage access to find your documents
perm_denied: No storage access — you can still open files manually
perm_grant: Grant access        import_file: Import file
sort: Sort → Name A→Z · Name Z→A · Newest first · Oldest first · Largest first · Smallest first
menu: Rescan device · Import file · Clear cache
pdf_footer: Page %1$d of %2$d        sheet_footer: Sheet %1$d of %2$d · %3$d rows
```

# APPENDIX D — Sample documents for self-testing

Create (or ask the user for) at minimum: a 12-page PDF, a DOCX with headings/bold/italic/lists/a 4×5 table, an XLSX with 3 sheets/42 rows/numbers+strings+dates, a UTF-8 TXT, and a Markdown file exercising every §7.3-MD feature. Verify each on API 23 and API 34 emulators.
