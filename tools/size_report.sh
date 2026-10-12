#!/usr/bin/env bash
# -----------------------------------------------------------------------------
# Leaf Lite — size report.
#
# Produces every number the master build prompt asks for (§14 "Size budget"):
#
#   1. universal APK file size      (bytes on disk)      budget <=  8 MB
#   2. universal APK download size  (as Play sees it)    budget <=  8 MB
#   3. AAB download size, min..max across device specs   budget <=  5 MB
#   4. installed footprint, min..max on device           budget <= 15 MB
#
# It also re-asserts the two manifest invariants that matter (minSdk and the
# total absence of android.permission.INTERNET).
#
# Usage:
#   ANDROID_HOME=/path/to/sdk bash tools/size_report.sh
#
# Env (all optional):
#   APK                 release APK to measure          default app/build/outputs/apk/release/app-release.apk
#   AAB                 release bundle to measure       default app/build/outputs/bundle/release/app-release.aab
#   OUT                 output directory                default build/size
#   BUNDLETOOL_VERSION  bundletool release tag          default 1.18.3
#
# Exits 1 when a budget is exceeded, so CI can fail loudly on a size
# regression. Set STRICT=0 to only warn.
#
# Every failure is also emitted as a GitHub Actions ::error:: annotation so it
# is visible on the run page even when the log is unavailable.
# -----------------------------------------------------------------------------
set -euo pipefail
set -E

# --- diagnostics -------------------------------------------------------------
annotate() {  # ::error:: into the Checks API (and stderr for terminals)
    echo "::error::$*" >&2
    echo "size_report: $*" >&2
}
die() { annotate "$*"; exit 1; }
on_err() {
    local status=$?
    annotate "failed at line ${BASH_LINENO[0]:-$LINENO} (exit $status): $BASH_COMMAND"
    exit "$status"
}
trap on_err ERR

APK="${APK:-app/build/outputs/apk/release/app-release.apk}"
AAB="${AAB:-app/build/outputs/bundle/release/app-release.aab}"
OUT="${OUT:-build/size}"
BUNDLETOOL_VERSION="${BUNDLETOOL_VERSION:-1.18.3}"
STRICT="${STRICT:-1}"

BUDGET_APK_MB=8
BUDGET_AAB_MB=5
BUDGET_INSTALLED_MB=15

ANDROID_HOME="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
[ -n "$ANDROID_HOME" ] || die "ANDROID_HOME (or ANDROID_SDK_ROOT) must be set"

mkdir -p "$OUT"

# --- toolchain ----------------------------------------------------------------
find_apkanalyzer() {
    local candidate
    for candidate in \
        "$ANDROID_HOME/cmdline-tools/latest/bin/apkanalyzer" \
        "$ANDROID_HOME"/cmdline-tools/*/bin/apkanalyzer \
        "$ANDROID_HOME"/build-tools/*/apkanalyzer \
        "$(command -v apkanalyzer 2>/dev/null || true)"; do
        if [ -n "$candidate" ] && [ -x "$candidate" ]; then
            echo "$candidate"
            return 0
        fi
    done
    return 1
}
APKANALYZER="$(find_apkanalyzer)" || die "apkanalyzer not found under $ANDROID_HOME (install cmdline-tools;latest)"

BUNDLETOOL="$OUT/bundletool.jar"
if [ ! -f "$BUNDLETOOL" ]; then
    curl -fsSL -o "$BUNDLETOOL" \
        "https://github.com/google/bundletool/releases/download/$BUNDLETOOL_VERSION/bundletool-all-$BUNDLETOOL_VERSION.jar" \
        || die "could not download bundletool $BUNDLETOOL_VERSION"
fi

for f in "$APK" "$AAB"; do
    [ -f "$f" ] || die "missing build output $f"
done

# --- helpers ------------------------------------------------------------------
mb() { awk -v b="$1" 'BEGIN { printf "%.2f", b / 1048576 }'; }
bytes_of() { stat -c %s "$1"; }
human_of() { numfmt --to=iec --suffix=B "$1" 2>/dev/null || echo "$1 B"; }

failures=0
check_budget() { # name value_mb budget_mb
    local name="$1" value="$2" budget="$3" verdict="OK"
    if awk -v v="$value" -v b="$budget" 'BEGIN { exit !(v > b) }'; then
        verdict="OVER BUDGET"
        failures=$((failures + 1))
    fi
    printf '  %-34s %8s MB   budget %2s MB   %s\n' "$name" "$value" "$budget" "$verdict"
}

# --- 1. universal APK ---------------------------------------------------------
APK_FILE_SIZE="$("$APKANALYZER" apk file-size "$APK" | tr -cd '0-9')"
APK_DOWNLOAD_SIZE="$("$APKANALYZER" apk download-size "$APK" | tr -cd '0-9')"
MIN_SDK="$("$APKANALYZER" manifest min-sdk "$APK" | tr -cd '0-9')"
TARGET_SDK="$("$APKANALYZER" manifest target-sdk "$APK" | tr -cd '0-9')"
PERMISSIONS="$("$APKANALYZER" manifest permissions "$APK" | sed '/^$/d' | tr '\n' ' ')"
[ -n "$APK_FILE_SIZE" ] || die "apkanalyzer returned no file size for $APK"
[ -n "$MIN_SDK" ] || die "apkanalyzer returned no minSdk for $APK"

# --- contract gates (§2): minSdk 23 and zero network permissions --------------
if [ "$MIN_SDK" != "23" ]; then
    die "minSdk is '$MIN_SDK', contract requires 23"
fi
if printf '%s' "$PERMISSIONS" | grep -q 'android.permission.INTERNET'; then
    die "INTERNET permission found in manifest"
fi

# --- 2. AAB -> device-specific APKS + universal APK ---------------------------
KS="$OUT/size-report.keystore"
if [ ! -f "$KS" ]; then
    keytool -genkeypair -keystore "$KS" -storepass android -keypass android \
        -alias androiddebugkey -keyalg RSA -keysize 2048 -validity 10000 \
        -dname "CN=Leaf Lite Size Report, O=Leaf Lite, C=US" -noprompt >/dev/null 2>&1 \
        || die "keytool could not create the throwaway keystore"
fi
KS_ARGS=(--ks="$KS" --ks-key-alias=androiddebugkey --ks-pass=pass:android --key-pass=pass:android)

java -jar "$BUNDLETOOL" build-apks --bundle="$AAB" --output="$OUT/device.apks" "${KS_ARGS[@]}" >/dev/null
java -jar "$BUNDLETOOL" build-apks --bundle="$AAB" --output="$OUT/universal.apks" \
    --mode=universal "${KS_ARGS[@]}" >/dev/null

unzip -o -q "$OUT/universal.apks" universal.apk -d "$OUT"
UNIVERSAL_APK="$OUT/universal.apk"
UNIVERSAL_FILE_SIZE="$(bytes_of "$UNIVERSAL_APK")"
UNIVERSAL_DOWNLOAD_SIZE="$("$APKANALYZER" apk download-size "$UNIVERSAL_APK" | tr -cd '0-9')"

# bundletool get-size total prints a line like
#   "2,512,252 bytes minimum, 4,826,341 bytes maximum"
SIZE_RANGE="$(java -jar "$BUNDLETOOL" get-size total --apks="$OUT/device.apks" || true)"
RANGE_LINE="$(printf '%s\n' "$SIZE_RANGE" | grep -m1 -E 'minimum|max' || true)"
AAB_MIN="$(printf '%s' "$RANGE_LINE" | grep -oE '[0-9][0-9,]*' | head -1 | tr -d ',' || true)"
AAB_MAX="$(printf '%s' "$RANGE_LINE" | grep -oE '[0-9][0-9,]*' | tail -1 | tr -d ',' || true)"
AAB_MIN="${AAB_MIN:-0}"
AAB_MAX="${AAB_MAX:-0}"
if [ "$AAB_MAX" -eq 0 ]; then
    die "could not parse bundletool get-size output: '$SIZE_RANGE'"
fi

# Installed footprint: the uncompressed payload plus the runtime artefacts the
# platform generates for it (oat/vdex), budgeted as uncompressed + 20%.
UNCOMPRESSED="$(python3 - "$UNIVERSAL_APK" <<'PY'
import sys, zipfile
with zipfile.ZipFile(sys.argv[1]) as z:
    print(sum(i.file_size for i in z.infolist()))
PY
)"
UNCOMPRESSED="${UNCOMPRESSED:-$UNIVERSAL_FILE_SIZE}"
INSTALLED_MIN="$(awk -v b="$UNCOMPRESSED" 'BEGIN { printf "%d", b * 1.0 }')"
INSTALLED_MAX="$(awk -v b="$UNCOMPRESSED" 'BEGIN { printf "%d", b * 1.2 }')"

# --- report -------------------------------------------------------------------
REPORT="$OUT/size-report.txt"
{
    echo "Leaf Lite — size report"
    echo "generated: $(date -u '+%Y-%m-%dT%H:%M:%SZ')"
    echo "bundletool: $BUNDLETOOL_VERSION"
    echo
    echo "Manifest"
    echo "  minSdk            : $MIN_SDK"
    echo "  targetSdk         : $TARGET_SDK"
    echo "  permissions       : ${PERMISSIONS:-<none>}"
    echo
    echo "Universal APK ($(basename "$APK"))"
    echo "  file size         : $APK_FILE_SIZE B ($(human_of "$APK_FILE_SIZE"))"
    echo "  download size     : $APK_DOWNLOAD_SIZE B ($(human_of "$APK_DOWNLOAD_SIZE"))"
    echo "  universal.apk     : $UNIVERSAL_FILE_SIZE B ($(human_of "$UNIVERSAL_FILE_SIZE"))"
    echo "  universal d/l     : $UNIVERSAL_DOWNLOAD_SIZE B ($(human_of "$UNIVERSAL_DOWNLOAD_SIZE"))"
    echo
    echo "App bundle ($(basename "$AAB"))"
    echo "  download min..max : $AAB_MIN..$AAB_MAX B ($(human_of "$AAB_MIN")..$(human_of "$AAB_MAX"))"
    echo "  installed (est.)  : $INSTALLED_MIN..$INSTALLED_MAX B ($(human_of "$INSTALLED_MIN")..$(human_of "$INSTALLED_MAX"))"
    echo
    echo "Budgets"
} > "$REPORT"

{
    check_budget "universal APK file size" "$(mb "$APK_FILE_SIZE")" "$BUDGET_APK_MB"
    check_budget "universal APK download size" "$(mb "$APK_DOWNLOAD_SIZE")" "$BUDGET_APK_MB"
    check_budget "AAB download size (max)" "$(mb "$AAB_MAX")" "$BUDGET_AAB_MB"
    check_budget "installed footprint (max)" "$(mb "$INSTALLED_MAX")" "$BUDGET_INSTALLED_MB"
} >> "$REPORT"

cat "$REPORT"

# --- step summary (GitHub Actions) --------------------------------------------
if [ -n "${GITHUB_STEP_SUMMARY:-}" ]; then
    {
        echo '### Leaf Lite — size report'
        echo
        echo '| metric | value | budget |'
        echo '| --- | --- | --- |'
        echo "| universal APK file size | $(mb "$APK_FILE_SIZE") MB | $BUDGET_APK_MB MB |"
        echo "| universal APK download size | $(mb "$APK_DOWNLOAD_SIZE") MB | $BUDGET_APK_MB MB |"
        echo "| AAB download size (min..max) | $(mb "$AAB_MIN")..$(mb "$AAB_MAX") MB | $BUDGET_AAB_MB MB |"
        echo "| installed footprint (est. max) | $(mb "$INSTALLED_MAX") MB | $BUDGET_INSTALLED_MB MB |"
        echo
        echo "minSdk **$MIN_SDK** · targetSdk **$TARGET_SDK** · permissions: ${PERMISSIONS:-none}"
    } >> "$GITHUB_STEP_SUMMARY"
fi

if [ "$failures" -gt 0 ]; then
    annotate "$failures size budget(s) exceeded — see the size report table"
    [ "$STRICT" = "1" ] && exit 1
fi

exit 0
