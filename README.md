# Leaf Lite

*A tiny viewer that opens almost any document without the weight.*

Leaf Lite is an **offline** Android document viewer for PDF, DOCX, XLSX, TXT and
Markdown. It discovers the documents already on your device, imports anything you
pick by hand, and renders them with hand-written parsers — no Apache POI, no
PdfBox, no MuPDF, no Gson, no DI framework, no native libraries.

| | |
| --- | --- |
| Package | `com.leaflite.viewer` |
| minSdk | **23** (Android 6.0) |
| targetSdk / compileSdk | **36** |
| Language / UI | Kotlin + XML views + ViewBinding (no Compose) |
| Permissions | `READ_EXTERNAL_STORAGE` (API ≤ 28 only) — **no INTERNET** |
| Size budget | ≤ 8 MB APK · ≤ 5 MB AAB download · ≤ 15 MB installed |

---

## Features

**Library** — automatic discovery: MediaStore scan populated on first launch
(storage permission is asked once and silently on Android 6–12; Android 13+
needs no permission and scans best-effort), six filter chips, live search by
file name, sort sheet (name/date/size, both directions, persisted), rows
showing `{Type} · {Size} · {Relative date}`, star toggle straight from the row,
long-press to share, overflow menu with *Add folder · Manage folders ·
Rescan device · Import file · Clear cache* ("Add folder" supplements the scan
for locations Android keeps hidden).

**Starred** — the same rows, filtered to starred documents; starring from the
viewer updates both tabs instantly.

**Viewer** — full screen, slides in from the right, star + share, find in
document, title + `{Type} · {Size}` sub-line:

| Format | Engine |
| --- | --- |
| PDF | `android.graphics.pdf.PdfRenderer` — paged list, pinch/double-tap zoom with settled-quality re-render, *Page X of Y*, page-text find |
| DOCX | ZIP + `XmlPullParser` — measured pagination onto page sheets, headings, bold/italic/underline/strike, sizes, colours, alignment, lists, tables, links, inline images, pinch text-zoom, *Page X of Y* |
| PPTX | ZIP + `XmlPullParser` — positioned text boxes, bullets, run colours, pictures, slide-size scaling, *Slide X of Y* |
| XLSX | ZIP + `XmlPullParser` — shared strings, styles, multiple sheets with switcher chips, numbers/strings/dates, cell find, *Sheet i of n · N rows* |
| TXT | BOM + strict-UTF-8 → windows-1252 charset detection, comfortable reading layout, monospace toggle, pinch text-zoom |
| MD | ~300-line hand-written Markdown parser — headings, emphasis, inline code, fenced code, quotes, lists, rules, links, pinch text-zoom |

**Settings** — Night reading mode (dark chrome + sepia reading page), Keep screen
on, Compact list, Version and Supported formats info dialogs, plus a library
summary card (`N documents · scanned 2 min ago`).

**Share target** — "Open with → Leaf Lite" for all supported MIME types
(`application/pdf`, the three OOXML types, `text/plain`, `text/markdown`,
`text/x-markdown`). Corrupt or unsupported files degrade to
*Preview not available* + **Open in another app**. Every document can also be
shared **out** (viewer share button, row long-press) through the
FileProvider cache.

---

## Building

```bash
# 1. Point the SDK at your installation
echo "sdk.dir=$HOME/Android/Sdk" > local.properties

# 2. Debug build (fallback debug signing)
./gradlew :app:assembleDebug

# 3. Release — universal APK + Play bundle
cp keystore.properties.example keystore.properties   # optional; edit it
./gradlew clean assembleRelease bundleRelease
```

Without `keystore.properties` the release build signs with the debug key, so a
fresh clone always builds. With it, fill in your own `storeFile`,
`storePassword`, `keyAlias` and `keyPassword`; the file is git-ignored.

Requirements: JDK 17 + Android SDK with platform 36 and build-tools 35+ (AGP
downloads what it needs).

### Size budget

One command produces every §14 number and **fails the build on a budget
breach** (it also asserts minSdk 23 and the absence of the INTERNET
permission):

```bash
ANDROID_HOME=$HOME/Android/Sdk bash tools/size_report.sh
# writes build/size/size-report.txt and prints the table
```

The same script runs in CI on every push (see *Continuous integration*), so the
numbers in the `leaf-lite-size-report` artefact are always current. Under the
hood it runs exactly these measurements:

```bash
# universal APK
apkanalyzer apk file-size     app/build/outputs/apk/release/app-release.apk
apkanalyzer apk download-size app/build/outputs/apk/release/app-release.apk

# Play bundle (per-device download size range)
bundletool build-apks --bundle=app/build/outputs/bundle/release/app-release.aab \
    --output=build/size/device.apks --ks=<keystore> ...
bundletool get-size total --apks=build/size/device.apks

# on-device footprint after a first scan
adb install app/build/outputs/apk/release/app-release.apk
adb shell du -sh /data/app/*com.leaflite.viewer* /data/data/com.leaflite.viewer
```

Measured on CI (GitHub Actions, 2026-10-12, run `38295712156`):

| metric | budget | measured | verdict |
| --- | --- | --- | --- |
| universal APK file size | ≤ 8 MB | **1.65 MB** | 21% of budget |
| universal APK download size | ≤ 8 MB | **1.26 MB** | 16% of budget |
| AAB download size (min..max) | ≤ 5 MB | **1.21–1.22 MB** | 24% of budget |
| installed footprint (est.) | ≤ 15 MB | **2.88–3.46 MB** | 23% of budget |

Every number comes from `tools/size_report.sh` and is re-measured on each push;
the script fails the build if any budget is ever exceeded.

### Tests

```bash
./gradlew :app:testDebugUnitTest          # JVM: format detection, file sizes
./gradlew :app:connectedDebugAndroidTest  # device: install + no-INTERNET smoke test
python3 tools/verify_project.py           # static consistency check (no SDK needed)
```

### Continuous integration

`.github/workflows/android.yml` builds the real artefacts on every push and
pull request (GitHub-hosted `ubuntu-latest`, Temurin JDK 17):

| job | what it does |
| --- | --- |
| `build` | `tools/verify_project.py`, JVM unit tests, lint, `assembleDebug` + `assembleRelease` + `bundleRelease`, `tools/size_report.sh` (fails over budget), uploads artefacts |
| `smoke-test` | API 23 emulator (the minSdk floor): install + no-INTERNET + document-registry smoke test |
| `release` | on a `v*` tag: publishes a GitHub Release with the APK, AAB, size report, checksums and R8 mapping |

Download the artefacts from the workflow run page:

| artefact | contents |
| --- | --- |
| `leaf-lite-release-apk` | `leaf-lite-1.0.0-release.apk` — installable universal APK |
| `leaf-lite-release-aab` | `leaf-lite-1.0.0-release.aab` — Play upload bundle |
| `leaf-lite-debug-apk` | `leaf-lite-1.0.0-debug.apk` — `.debug` applicationId |
| `leaf-lite-size-report` | `size-report.txt`, `SHA256SUMS.txt`, R8 `mapping.txt` |

To cut a release: `git tag v1.0.0 && git push origin v1.0.0` — CI attaches the
signed bits to the GitHub Release once the build and smoke test pass.

---

## Project layout

```
LeafLite/
├── DECISIONS.md                 # every judgment call, documented
├── tools/
│   ├── make_samples.py          # regenerates samples/ (Appendix D fixtures)
│   ├── make_launcher_icons.py   # regenerates the launcher PNGs
│   └── verify_project.py        # static project checker
├── samples/                     # 12-page PDF, DOCX, 3-sheet XLSX, TXT, MD
└── app/src/main/java/com/leaflite/viewer/
    ├── MainActivity.kt  ViewerActivity.kt  LeafLiteApp.kt
    ├── data/       DocumentRepository · MediaStoreScanner · FileRegistryDb · Prefs · models
    ├── parsers/    FormatDetector · PdfEngine · DocxParser · XlsxParser · MarkdownParser · TextLoader · Zipx
    ├── ui/library/ · ui/starred/ · ui/settings/ · ui/viewer/ · ui/widgets/
    └── util/       Formatters · FileSize · InsetsExt · ViewExt
```

Data flow: `MediaStoreScanner` / SAF / `ACTION_VIEW` → `DocumentRepository`
(SQLite registry + `StateFlow`) → ViewModels → fragments. Parsing always runs on
`Dispatchers.IO`/`Default`; the UI thread never touches a stream.

---

## Regenerating assets

```bash
pip install pillow          # only needed for these two scripts
python3 tools/make_samples.py
python3 tools/make_launcher_icons.py
```

---

## Manual QA checklist

- [ ] API 23: permission card → grant → documents appear; deny → card swaps to *Import file*.
- [ ] API 29+: no permission dialog is ever shown.
- [ ] All five sample files open from the Library and from a file manager ("Open with").
- [ ] Rotate the device inside each format: PDF keeps the page, XLSX keeps the sheet, TXT/MD/DOCX keep the scroll offset.
- [ ] Star from the row, from the viewer and from the Starred tab — all three stay in sync.
- [ ] Filter chips + search + sort survive a rotation and a restart.
- [ ] Night reading mode: chrome turns dark, the document page turns sepia.
- [ ] Compact list: rows shrink in both Library and Starred.
- [ ] Corrupt a sample (truncate the file): *Preview not available* + *Open in another app*.
- [ ] Airplane mode: everything still works.
