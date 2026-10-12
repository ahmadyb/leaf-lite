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

**Library** — MediaStore discovery, six filter chips, live search by file name,
sort sheet (name/date/size, both directions, persisted), rows showing
`{Type} · {Size} · {Relative date}`, star toggle straight from the row, overflow
menu with *Rescan device · Import file · Clear cache*.

**Starred** — the same rows, filtered to starred documents; starring from the
viewer updates both tabs instantly.

**Viewer** — full screen, slides in from the right, star toggle, title +
`{Type} · {Size}` sub-line:

| Format | Engine |
| --- | --- |
| PDF | `android.graphics.pdf.PdfRenderer` — paged list, pinch/double-tap zoom, *Page X of Y* |
| DOCX | ZIP + `XmlPullParser` — headings, bold/italic/underline/strike, sizes, colours, alignment, lists, tables, links, inline images |
| XLSX | ZIP + `XmlPullParser` — shared strings, styles, multiple sheets with switcher chips, numbers/strings/dates, *Sheet i of n · N rows* |
| TXT | BOM + strict-UTF-8 → windows-1252 charset detection, comfortable reading layout, monospace toggle |
| MD | ~300-line hand-written Markdown parser — headings, emphasis, inline code, fenced code, quotes, lists, rules, links |

**Settings** — Night reading mode (dark chrome + sepia reading page), Keep screen
on, Compact list, Version and Supported formats info dialogs, plus a library
summary card (`N documents · scanned 2 min ago`).

**Share target** — "Open with → Leaf Lite" for all five MIME types
(`application/pdf`, the two OOXML types, `text/plain`, `text/markdown`,
`text/x-markdown`). Corrupt or unsupported files degrade to
*Preview not available* + **Open in another app** (via FileProvider).

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

```bash
# universal APK
$ANDROID_HOME/build-tools/36.0.0/apkanalyzer apk size app/build/outputs/apk/release/app-release.apk
$ANDROID_HOME/build-tools/36.0.0/apkanalyzer apk download-size app/build/outputs/apk/release/app-release.apk

# Play bundle
bundletool build-apks --bundle=app/build/outputs/bundle/release/app-release.aab \
    --output=/tmp/leaflite.apks --ks=<keystore> ...
bundletool get-size total --apks=/tmp/leaflite.apks

# on-device footprint after a first scan
adb install app/build/outputs/apk/release/app-release.apk
adb shell du -sh /data/app/*com.leaflite.viewer* /data/data/com.leaflite.viewer
```

Expected (see `DECISIONS.md` §0/§7 for why these are estimates, not measurements
from CI): **~2.5–3.5 MB** universal APK, **~1.8–2.8 MB** AAB download,
**~7–10 MB** installed.

### Tests

```bash
./gradlew :app:testDebugUnitTest          # JVM: format detection, file sizes
./gradlew :app:connectedDebugAndroidTest  # device: install + no-INTERNET smoke test
python3 tools/verify_project.py           # static consistency check (no SDK needed)
```

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
