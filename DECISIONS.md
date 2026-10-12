# Leaf Lite — engineering decisions

Every judgment call made while building Leaf Lite, including the ones the master
prompt did not cover. Where the prompt offered a choice, the smaller and simpler
option won.

---

## 0. Environment / verification honesty

The sandbox this project was authored in has **no JDK and no Android SDK**, and
only `github.com`, `codeload.github.com`, `api.github.com`, `registry.npmjs.org`,
`pypi.org` and `files.pythonhosted.org` are reachable — Google Maven and Maven
Central are not. So:

* `./gradlew assembleRelease bundleRelease` could **not** be executed in the
  authoring sandbox. It **is** executed on every push by GitHub Actions
  (`.github/workflows/android.yml`), which produced the real artefacts and the
  real size numbers reported in `README.md` → *Size budget* (universal APK
  **1.65 MB** file / **1.26 MB** download; AAB download **1.21–1.22 MB**;
  installed estimate **2.88–3.46 MB** — all well inside the §14 budgets).
* To compensate, the repository ships `tools/verify_project.py`, a static
  consistency checker (Kotlin delimiter balance, package/folder agreement,
  every `R.*` and `@drawable/@string/@color/@dimen/@style` reference,
  ViewBinding field ↔ layout-id resolution, manifest sanity, no INTERNET
  permission, no imports outside the allow-list, unused resources). It reports
  **0 errors** on this tree.
* The Gradle **wrapper jar and scripts are real** (taken from the Gradle 8.13.0
  distribution), so `./gradlew` works on a machine with a JDK without any
  generation step.

---

## 1. Toolchain

| Choice | Value | Why |
| --- | --- | --- |
| Android Gradle Plugin | **8.13.2** | Last AGP 8.x line; supports `compileSdk 36` (max API 36.1), stable DSL, JDK 17. AGP 9.x was rejected: it removes `minSdkVersion`/`targetSdkVersion`, changes the Kotlin wiring and adds risk for zero size benefit. |
| Gradle | **8.13** | Minimum required by AGP 8.13. |
| Kotlin | **2.1.20** | Latest Kotlin with a mature `kotlin-android` plugin on Gradle 8.13; K2 is stable, no kapt/KSP needed anywhere. |
| `compileSdk` / `targetSdk` | **36 / 36** | "Latest stable" per the prompt and the Play requirement for new apps/updates (target 36 since Aug 2026). |
| `minSdk` | **23** | Hard requirement. Every API used is API 23-safe or guarded. |
| JVM target | 17 | Required by AGP 8.13. |

`keystore.properties` is optional: without it the release build type falls back
to the debug signing config, so a clean checkout builds `assembleRelease` with
zero manual edits.

`applicationIdSuffix ".debug"` is applied to debug builds so a debug install and
a release install can coexist on a test device.

---

## 2. Manifest shape

* **One permission**: `READ_EXTERNAL_STORAGE` with `maxSdkVersion="28"`. No
  `WRITE_EXTERNAL_STORAGE`, no `MANAGE_EXTERNAL_STORAGE`, no `INTERNET`.
* **`<queries>`** with an `ACTION_VIEW` intent is *not* a permission — it only
  lets the "Open in another app" fallback resolve viewers under Android 11+
  package visibility. Nothing is fetched from the network.
* **Five `activity-alias` entries** (`ViewerActivityPdf`, …) instead of five
  `<intent-filter>` blocks on `ViewerActivity`. Reason: a single `<intent-filter>`
  merges all of its `<data>` elements into a cross-product of scheme × type, and
  Android's MIME matching for `content:` URIs is unreliable when several types
  live in one filter. One filter per MIME type (with `content` + `file`
  schemes) is the pattern the platform documents and the one the framework
  matches most predictably. The aliases cost ~200 bytes of manifest.
* **Predictive back**: `android:enableOnBackInvokedCallback="true"` +
  `OnBackPressedDispatcher` callbacks (never `onBackPressed()` overrides), so
  API 33+ gets the predictive animation and API 23-32 keeps the old path.
* **`ViewerActivity` declares no `configChanges`.** The prompt asks both for
  "configChanges minimal" and for rotation to restore page/scroll/sheet from
  instance state; those conflict. State restoration (`onSaveInstanceState`) won,
  because a recreated activity also re-applies the sepia/dark palette and
  re-measures the PDF page widths — no manual re-layout code, no drift.

---

## 3. Architecture

* Single `MainActivity` + three fragments, **created once and hidden/shown**
  (not recreated) so scroll position and list state survive tab switches.
* ViewModels are plain `AndroidViewModel`s with `StateFlow`. The Library
  ViewModel is **activity-scoped** (`activityViewModels()`), because
  `MainActivity` must forward SAF import results and runtime-permission results
  into it.
* `DocumentRepository` is the only source of truth; it exposes
  `StateFlow<List<Doc>>` and re-emits after every mutation, which is what keeps
  Library, Starred and the viewer star button in sync without event buses.
* No DI: `LeafLiteApp` constructs one repository and one `Prefs`; ViewModels pull
  them from the `Application`.

---

## 4. Discovery, registry and permissions

* **MediaStore query** uses `MIME_TYPE IN (…)` **OR** `DISPLAY_NAME LIKE '%.ext'`
  **AND** `SIZE > 0` **AND** `MEDIA_TYPE = MEDIA_TYPE_NONE`, capped at 5,000 rows
  ordered by `DATE_MODIFIED DESC`. MIME is frequently NULL in MediaStore, so the
  extension clause is the reliable one; `MEDIA_TYPE_NONE` keeps music/video out
  of the result set.
* **Pruning only touches `source='scan'` rows.** Imported and intent-opened
  documents live behind persisted URI permissions outside MediaStore, so a
  rescan must never delete them.
* **Rescans upsert by URI** and deliberately preserve `starred` and
  `last_opened`.
* **API 29+ never shows a permission dialog.** On 23-28 the inline card appears
  only when the permission is actually missing; after a denial the card swaps to
  the "Import file" variant and never nags by itself.
* Everything is opened through `ContentResolver`; the raw `_data` column is never
  read, so scoped storage is a non-issue.

---

## 5. Parsers

### Shared
* `Zipx` streams entries straight from the `ContentResolver` (no cache copy, no
  `ZipFile`, which needs random access) and skips payloads it does not need.
* `LimitInputStream` caps a single ZIP entry at 32 MB, so a hostile archive
  cannot exhaust the heap (`largeHeap` stays off).

### PDF (`android.graphics.pdf.PdfRenderer`)
* Opened with `openFileDescriptor(uri, "r")` — works for MediaStore, SAF and
  `file:` URIs with no copy. `SecurityException`/`IOException` map to the
  "Preview not available" state (encrypted or truncated PDFs).
* Bitmaps: render width = page width capped at **1440 px**, longest edge capped
  at **4096 px**, `ARGB_8888` (the format `PdfRenderer` requires).
* **At most 3 page bitmaps alive.** The LRU *drops* evicted bitmaps instead of
  calling `recycle()` on them: a recycled bitmap that is still attached to a
  visible `ImageView` throws, and on API 26+ pixel data is native and freed by
  GC anyway. Bitmaps are only explicitly recycled when the activity is
  destroyed.
* Rendering runs on a `Dispatchers.Default.limitedParallelism(1)` dispatcher —
  `PdfRenderer` is not thread-safe — and every job is tied to the
  activity's `lifecycleScope`.
* **No shimmer placeholder.** A page card is sized from the real page aspect
  ratio before the bitmap exists, so there is nothing to shimmer; an animation
  there would burn CPU on exactly the low-end devices this app targets.

### DOCX
* Headings come from `w:pStyle`, alignment from `w:jc`, sizes from `w:sz`
  (half-points → sp, clamped to 6–72), colour from `w:color`; runs support
  bold/italic/underline/strike.
* **Numbering without `numbering.xml`**: the first `w:numId` seen becomes a
  bullet list, the next a numbered one, alternating from then on, with a
  per-list counter. Reading `numbering.xml` + `numFmt` inheritance would cost
  another full ZIP pass and ~200 lines for a feature v1 does not need.
* Tables, hyperlinks (via `word/_rels/document.xml.rels`, external targets only)
  and inline images (`word/media/*`, resolved in **one** extra ZIP pass and
  down-sampled to 1600 px) are supported.
* Skipped without crashing: headers/footers, footnotes, comments, equations,
  fields, VML shapes. Tracked insertions are shown, deletions dropped
  (`w:delText` is not `w:t`).
* Block count is capped at 4,000 so a pathological document cannot freeze the
  UI while views are built.

### XLSX
* Structure pass reads `workbook.xml`, `workbook.xml.rels`, `sharedStrings.xml`
  and `styles.xml` in **one** ZIP walk; sheets are read on demand.
* **Dates are best-effort**: only built-in number formats (`numFmtId` 14–22 and
  45–47) are recognised; custom `numFmtId ≥ 164` strings are not parsed and such
  cells render as raw serials. Documented limitation, as the prompt allows.
* Numbers: integral values print without `.0`, everything else is capped at two
  decimals, |value| ≥ 1e15 switches to scientific notation.
* Caps: **5,000 rows**, **64 columns**, **120,000 cells**. The footer switches to
  "showing first N rows" when a cap bites.

### TXT / Markdown
* TXT is read with a BOM check (UTF-8/UTF-16LE/BE) → strict UTF-8 decode →
  `windows-1252` → `ISO-8859-1`, capped at **2 MB** of source bytes.
* Markdown supports: ATX headings, paragraphs, `**bold**`, `*italic*`,
  `` `inline code` ``, fenced code blocks, block quotes, unordered/ordered lists
  with **one** nesting level, horizontal rules, links (opened with `ACTION_VIEW`)
  and images (rendered as an `[alt]` placeholder).
* Not supported (out of scope for ~300 lines): nested block quotes, tables,
  reference links, inline HTML, setext headings, task list checkboxes.

### Format detection
Extension first, MIME as corroboration, magic bytes last: `%PDF-` → PDF,
`PK\x03\x04` → probe the ZIP for `word/document.xml` vs `xl/workbook.xml`,
printable-only bytes → TXT, anything else → unsupported.

---

## 6. UI / design decisions

* **The screen header does not collapse.** The mockup scrolls the header with the
  list; on Android that forces a `RecyclerView` header item (extra complexity) or
  `nestedScrollingEnabled=false` (which inflates every row and destroys the
  60 fps / 1,000+ document requirement). The header stays pinned and the list
  owns the remaining height, so rows keep recycling.
* **Press feedback**: rows, chips, icon buttons and tabs get a material ripple;
  icon buttons, chips and tabs additionally keep the mockup's scale-down
  (0.92–0.95). Document rows use the mockup's `#F2F6F3` as the *ripple colour*
  rather than a two-state selector, which keeps the single-file `ripple_row.xml`
  and still reads as the mockup.
* **Per-type tiles** are one `bg_type_tile.xml` tinted at runtime with
  `ColorUtils.blendARGB(card, type, 0.12f)` — the mockup's 12 % tint, correct in
  both light and night themes, instead of ten hardcoded colour resources.
* **Night reading mode** forces `MODE_NIGHT_YES` (chrome = dark palette) and, per
  §5.1, the **viewer body switches to the sepia palette** (`#F7F1E3` /
  `#3D3428`). That is intentional: dark chrome, warm page.
* **Material is used for exactly one thing** — `BottomSheetDialog` for the sort
  sheet, which the prompt's allow-list mandates. The app theme stays
  `AppCompatActivity`/AppCompat-based (not `Theme.MaterialComponents`), so every
  button is an `AppCompatButton` and every switch is hand-drawn; that avoids
  Material theme enforcement crashes and keeps the dependency's footprint to
  what R8 can shake.
* **Insets** are applied with `WindowInsetsControllerCompat` /
  `WindowInsetsCompat` (works on API 23) and `WindowCompat.setDecorFitsSystemWindows`,
  so the app is edge-to-edge on API 23 and API 34+ alike, with light status-bar
  icons in the light theme.
* The XLSX grid is a `RecyclerView` (vertical, recycling) inside a
  `HorizontalScrollView` (horizontal), with a separate sticky header row synced
  via `OnScrollChangedListener`. Far less code than a custom 2D scroller, and it
  recycles.
* PDF zoom is a hand-written `ZoomImageView` (matrix + `ScaleGestureDetector` +
  double-tap). At 1× it never intercepts touches, so paging keeps working; once
  zoomed it calls `requestDisallowInterceptTouchEvent`.

---

## 7. Size engineering

* `isMinifyEnabled = true`, `isShrinkResources = true`, **R8 full mode**
  (`android.enableR8.fullMode=true`), `resConfigs("en")`, no bundled fonts, no
  density/ABI splits (there is no native code).
* Every icon is a vector drawable; the only rasters are the five launcher PNG
  buckets (API < 26 fallback), generated from the same artwork by
  `tools/make_launcher_icons.py`.
* `proguard-rules.pro` is deliberately tiny: no reflection, no serializers, so
  nothing but XML-pull and coroutine entry points needs keeping; `Log.d/v/i` are
  compiled out of release.
* `tools/verify_project.py` fails the build review if any import outside the
  allow-list appears, which is how "nothing may be added without a size
  justification" is enforced mechanically.

**Estimated** (not measured — see §0) release size:

| Artefact | Estimate | Budget |
| --- | --- | --- |
| Universal release APK | ~2.5–3.5 MB | ≤ 8 MB |
| AAB download size | ~1.8–2.8 MB | ≤ 5 MB |
| Installed on device (APK + ART + first-scan data) | ~7–10 MB | ≤ 15 MB |

The biggest contributors are `kotlin-stdlib` + `kotlinx-coroutines` (≈1.2 MB
together after R8), `appcompat` (≈0.5 MB) and `material` (only
`BottomSheetDialog` survives shaking, ≈0.3 MB).

---

## 8. Things deliberately not built

v1 scope, all listed as out of scope by the prompt: editing, annotations, PDF
text selection/search, `.doc`/`.xls`/`.ppt`, EPUB, DRM, cloud sync, widgets,
non-English UI. In addition:

* No shimmer/skeleton animation for PDF pages (see §5).
* No "word wrap off / horizontal scroll" for wide logs — the monospace toggle
  covers the common case and a second scroll axis doubles the touch handling.
* No `WorkManager`, services, notifications or jobs: the app does no background
  work at all.

---

## 9. Assets generated by tooling

| Path | Produced by | Why a script rather than a binary |
| --- | --- | --- |
| `samples/*.pdf/.docx/.xlsx/.txt/.md` | `tools/make_samples.py` | The five Appendix D fixtures are reproducible from source (stdlib + Pillow only) and stay tiny in git. |
| `app/src/main/res/mipmap-*/ic_launcher*.png` | `tools/make_launcher_icons.py` | Same artwork as the adaptive vector, rasterised with a self-contained path renderer (arcs → cubic béziers → polylines). |

---

## 10. Build automation (CI) and size reporting

The authoring sandbox has no Android SDK (§0), so the repository is built for
real on GitHub Actions — `.github/workflows/android.yml`, `ubuntu-latest`,
Temurin JDK 17 (AGP 8.13 requires JDK 17).

**Jobs and order.** `build` runs `tools/verify_project.py`, then one Gradle
invocation for `testReleaseUnitTest lintRelease assembleDebug assembleRelease
bundleRelease` (single configuration pass, shared build cache), then
`tools/size_report.sh`, then uploads artefacts. `smoke-test` (needs `build`)
runs `connectedDebugAndroidTest` on an **API 23 emulator** — the minSdk floor is
where compatibility breaks would appear, and the smoke test is device-agnostic.
`release` runs only on `v*` tags and publishes the bits with `gh release
create`.

**Wrapper integrity.** `gradle/actions/wrapper-validation@v6` is enabled. The
wrapper jar committed at `gradle/wrapper/gradle-wrapper.jar` has sha256
`81a82aaea5abcc8ff68b3dfcb58b3c3c429378efd98e7433460610fecd7ae45f`, which is
the **official** Gradle 8.13 wrapper checksum in the action's allow-list —
verified against `gradle/actions`' `wrapper-checksums.json` before wiring the
step in (the jar is byte-identical to `gradle/wrapper/gradle-wrapper.jar` at
`gradle/gradle` tag `v8.13.0`).

**Size reporting — `tools/size_report.sh`.** One script produces every number
§14 asks for and enforces the budgets (exit 1 on breach, `STRICT=0` to warn
only):

* universal APK **file size** and **download size** via `apkanalyzer` (budget
  8 MB each);
* AAB **download size min..max** via `bundletool build-apks` +
  `get-size total` across all device configurations (budget 5 MB max);
* **installed footprint** estimated as the uncompressed APK payload
  ×1.0–1.2 (the 20 % upper bound is the oat/vdex allowance). Rationale: a true
  on-device `du` measurement needs a device; the estimate is deterministic and
  conservative, and the emulator smoke job proves the app installs on the
  floor API. The `adb` commands for a manual measurement are in `README.md`;
* **hard contract gates**: minSdk must be exactly 23 and the manifest must
  contain zero `android.permission.INTERNET`, regardless of sizes.

**Signing on CI.** No signing secrets exist on the runner, so
`app/build.gradle.kts`' existing fallback applies: the release APK is signed
with the Gradle **debug keystore** (AGP generates `~/.android/debug.keystore`
on the runner). That makes CI APKs installable for QA but **not Play-eligible**;
production signing is the documented `keystore.properties` route. This is
deliberate: no key material is ever stored in the repository or workflow.

**Action versions.** Pinned to the current majors at the time of writing
(checkout v7, setup-java v6, upload-artifact v7, download-artifact v8,
gradle/actions v6, android-emulator-runner v2).
