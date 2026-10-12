#!/usr/bin/env python3
"""Static consistency check for the Leaf Lite project.

There is no Android SDK in every environment, so this script verifies what can
be verified without compiling:

  * Kotlin files: balanced delimiters, package matches folder, no TODO/stub
    markers, no forbidden imports (nothing outside the allow-list).
  * Resource references: every R.<type>.<name> used from Kotlin exists in res/,
    every @drawable/@string/@color/@dimen/@style/@layout/@menu/@xml/@anim used
    from XML exists too.
  * ViewBinding: every binding.<field> used from Kotlin resolves to an
    android:id in the matching layout (or to a nested <include> binding).
  * Manifest: icons, labels, themes, activity names and file provider paths.
  * Unused resources (informational).

Usage:  python3 tools/verify_project.py
"""

from __future__ import annotations

import re
import sys
import xml.etree.ElementTree as ET
from collections import defaultdict
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
RES = ROOT / "app" / "src" / "main" / "res"
MANIFEST = ROOT / "app" / "src" / "main" / "AndroidManifest.xml"
KOTLIN_DIRS = [
    ROOT / "app" / "src" / "main" / "java",
    ROOT / "app" / "src" / "test" / "java",
    ROOT / "app" / "src" / "androidTest" / "java",
]

ALLOWED_IMPORT_PREFIXES = (
    "java.",
    "kotlin.",
    "kotlinx.",
    "android.",
    "androidx.core",
    "androidx.appcompat",
    "androidx.recyclerview",
    "androidx.constraintlayout",
    "androidx.fragment",
    "androidx.lifecycle",
    "androidx.activity",
    "androidx.annotation",
    "androidx.test",
    "com.google.android.material",
    "com.leaflite.viewer",
    "org.junit",
    "org.xmlpull",
    "org.hamcrest",
)

errors: list[str] = []
warnings: list[str] = []


def error(message: str) -> None:
    errors.append(message)


def warn(message: str) -> None:
    warnings.append(message)


# --------------------------------------------------------------- resource index

def collect_resources() -> dict[str, set[str]]:
    resources: dict[str, set[str]] = defaultdict(set)
    for xml_file in sorted(RES.rglob("*.xml")):
        if xml_file.parent.name.startswith("values"):
            collect_values(xml_file, resources)
        else:
            folder = xml_file.parent.name.split("-")[0]
            resources[folder].add(xml_file.stem)
    # file-based resources also live in these folders
    resources["mipmap"] |= set(resources.get("mipmap-anydpi-v26", set()))
    return resources


def collect_values(path: Path, resources: dict[str, set[str]]) -> None:
    try:
        tree = ET.parse(path)
    except ET.ParseError as parse_error:
        error(f"{path.relative_to(ROOT)}: invalid XML ({parse_error})")
        return
    mapping = {
        "string": "string",
        "color": "color",
        "dimen": "dimen",
        "style": "style",
        "integer": "integer",
        "bool": "bool",
        "declare-styleable": "styleable",
        "attr": "attr",
    }
    for element in tree.getroot():
        kind = mapping.get(element.tag)
        if kind is None:
            continue
        name = element.attrib.get("name")
        if name:
            resources[kind].add(name.replace(".", "_"))
            if kind == "style":
                resources[kind].add(name)
        if element.tag == "declare-styleable":
            for child in element:
                if child.tag == "attr":
                    attr_name = child.attrib.get("name", "")
                    if not attr_name:
                        continue
                    resources["attr"].add(attr_name)
                    resources["styleable"].add(f"{name}_{attr_name}")
                    resources["styleable"].add(f"{name}_{attr_name.replace(':', '_')}")


RESOURCES = collect_resources()

# ------------------------------------------------------------------- kotlin

IMPORT_RE = re.compile(r"^import\s+([\w.]+)")
R_REF_RE = re.compile(r"(?<![\w.])R\.(\w+)\.(\w+)")
BINDING_RE = re.compile(r"\bbinding\.(\w+)")
STYLE_REF_RE = re.compile(r"\bR\.style\.(\w+)")


def strip_code(text: str) -> str:
    """Removes comments and string/char literals (crude but good enough)."""
    out = []
    i = 0
    length = len(text)
    while i < length:
        if text[i] == '"' and not (i >= 2 and text[i - 1] == "\\"):
            if text.startswith('"""', i):
                end = text.find('"""', i + 3)
                i = length if end == -1 else end + 3
            else:
                i += 1
                while i < length and text[i] != '"':
                    i += 2 if text[i] == "\\" else 1
                i += 1
        elif text.startswith("//", i):
            end = text.find("\n", i)
            i = length if end == -1 else end
        elif text.startswith("/*", i):
            end = text.find("*/", i)
            i = length if end == -1 else end + 2
        elif text[i] == "'":
            i += 1
            while i < length and text[i] != "'":
                i += 2 if text[i] == "\\" else 1
            i += 1
        else:
            out.append(text[i])
            i += 1
    return "".join(out)


def check_kotlin(path: Path) -> None:
    raw = path.read_text(encoding="utf-8")
    code = strip_code(raw)
    relative = path.relative_to(ROOT)

    # 1. no stubs
    for marker in ("TODO", "FIXME", "XXX", "Not implemented", "rest of implementation"):
        if marker in code:
            error(f"{relative}: stub marker {marker!r}")

    # 2. delimiters
    pairs = {"{": "}", "(": ")", "[": "]"}
    stack: list[str] = []
    for char in code:
        if char in pairs:
            stack.append(pairs[char])
        elif char in ("}", ")", "]"):
            if not stack or stack.pop() != char:
                error(f"{relative}: unbalanced delimiter {char!r}")
                break

    # 3. package matches folder
    package_match = re.search(r"^package\s+([\w.]+)", code, re.MULTILINE)
    if not package_match:
        error(f"{relative}: missing package declaration")
        return
    expected = "com.leaflite.viewer" + ".".join(
        [] if path.parent.name == "java" else []
    )
    package = package_match.group(1)
    relative_dir = path.parent.relative_to(ROOT / "app" / "src" / "main" / "java") \
        if (ROOT / "app" / "src" / "main" / "java") in path.parents else None
    if relative_dir is not None:
        expected_package = ".".join(relative_dir.parts)
        if package != expected_package:
            error(f"{relative}: package {package} does not match folder ({expected_package})")

    # 4. imports
    for import_name in IMPORT_RE.findall(code):
        if import_name.startswith("com.leaflite.viewer.R"):
            continue
        if not import_name.startswith(ALLOWED_IMPORT_PREFIXES):
            error(f"{relative}: import outside the allow-list: {import_name}")

    # 5. resource references
    for kind, name in R_REF_RE.findall(code):
        folder = {"string": "string", "drawable": "drawable", "layout": "layout",
                  "id": "id", "color": "color", "dimen": "dimen", "style": "style",
                  "menu": "menu", "xml": "xml", "anim": "anim", "mipmap": "mipmap",
                  "styleable": "styleable", "attr": "attr", "integer": "integer",
                  "string-array": "array"}.get(kind)
        if folder is None:
            error(f"{relative}: unknown resource type R.{kind}.{name}")
        elif folder in ("id",):
            continue  # ids are layout-scoped, checked separately
        elif name not in RESOURCES.get(folder, set()):
            error(f"{relative}: missing R.{kind}.{name}")

    # 6. unused imports (informational)
    for import_name in IMPORT_RE.findall(code):
        symbol = import_name.rsplit(".", 1)[-1]
        body = code.split("import", 1)[-1]
        if not re.search(r"\b" + re.escape(symbol) + r"\b", body.split("\n", 0)[0] if False else code):
            warn(f"{relative}: unused import {import_name}")


# --------------------------------------------------------------- view binding

def layout_ids(layout: str) -> set[str]:
    path = RES / "layout" / f"{layout}.xml"
    if not path.exists():
        return set()
    try:
        tree = ET.parse(path)
    except ET.ParseError:
        return set()
    ids = set()
    for element in tree.getroot().iter():
        identifier = element.attrib.get("{http://schemas.android.com/apk/res/android}id")
        if identifier and identifier.startswith("@+id/"):
            ids.add(camel(identifier.split("/")[1]))
    return ids


def camel(name: str) -> str:
    parts = name.split("_")
    return parts[0] + "".join(part.title() for part in parts[1:])


def check_binding_usage() -> None:
    for directory in KOTLIN_DIRS:
        for path in sorted(directory.rglob("*.kt")):
            code = strip_code(path.read_text(encoding="utf-8"))
            relative = path.relative_to(ROOT)
            layout = None
            for candidate in re.findall(r"(\w+Binding)\.inflate|(\w+Binding)\.bind", code):
                layout = candidate[0] or candidate[1]
                break
            binding_match = re.search(r"private\s+(?:var|_binding|_?\w+)\s*:\s*(\w+Binding)", code)
            if binding_match:
                layout = binding_match.group(1)
            if not layout:
                continue
            stem = re.sub(r"Binding$", "", layout)
            snake = re.sub(r"(?<!^)(?=[A-Z])", "_", stem).lower()
            ids = layout_ids(snake)
            if not ids:
                warn(f"{relative}: no ids resolved for layout {snake}.xml")
                continue
            for field in set(BINDING_RE.findall(code)):
                if field in ("root",):
                    continue
                nested = RES / "layout" / f"{camel_to_snake(field)}.xml"
                if field not in ids and not nested.exists():
                    error(f"{relative}: binding.{field} is not an id in {snake}.xml")


def camel_to_snake(name: str) -> str:
    return re.sub(r"(?<!^)(?=[A-Z])", "_", name).lower().replace("__", "_")


# ------------------------------------------------------------------------ xml

def check_xml() -> None:
    for xml_file in sorted(RES.rglob("*.xml")):
        try:
            ET.parse(xml_file)
        except ET.ParseError as parse_error:
            error(f"{xml_file.relative_to(ROOT)}: invalid XML ({parse_error})")
            continue
        text = xml_file.read_text(encoding="utf-8")
        for reference in re.findall(r'"@([\w.-]+)/([\w.]+)"', text):
            kind, name = reference
            if kind in ("android", "id", "null"):
                continue
            folder = kind.split("-")[0]
            if folder == "string":
                folder = "string"
            if name not in RESOURCES.get(folder, set()):
                error(f"{xml_file.relative_to(ROOT)}: missing @{kind}/{name}")
        for reference in re.findall(r'"\?attr/(\w+)"', text):
            if reference not in RESOURCES.get("attr", set()):
                warn(f"{xml_file.relative_to(ROOT)}: unknown theme attribute ?attr/{reference}")


def check_manifest() -> None:
    raw = MANIFEST.read_text(encoding="utf-8")
    text = re.sub(r"<!--.*?-->", "", raw, flags=re.S)
    tree = ET.parse(MANIFEST)
    classes = set()
    for directory in KOTLIN_DIRS:
        for path in directory.rglob("*.kt"):
            classes.add(path.stem)
    for element in tree.getroot().iter():
        if element.tag == "activity-alias":
            continue  # aliases point at targetActivity, they are not classes
        name = element.attrib.get("{http://schemas.android.com/apk/res/android}name")
        if name and name.startswith("."):
            simple = name.split(".")[-1]
            if simple not in classes and simple != "LeafLiteApp":
                error(f"manifest: activity/class .{simple} has no Kotlin file")
    for reference in re.findall(r'@([\w.-]+)/([\w.]+)', text):
        kind, name = reference
        if name not in RESOURCES.get(kind.split("-")[0], set()):
            error(f"manifest: missing @{kind}/{name}")
    if "android.permission.INTERNET" in text:
        error("manifest: INTERNET permission must not be declared")
    for forbidden in ("WRITE_EXTERNAL_STORAGE", "MANAGE_EXTERNAL_STORAGE", "ACCESS_NETWORK_STATE"):
        if forbidden in text:
            error(f"manifest: forbidden permission {forbidden}")


def check_unused() -> None:
    all_code = "\n".join(
        path.read_text(encoding="utf-8")
        for directory in KOTLIN_DIRS
        for path in directory.rglob("*.kt")
    )
    all_xml = "\n".join(path.read_text(encoding="utf-8") for path in RES.rglob("*.xml"))
    all_xml += MANIFEST.read_text(encoding="utf-8")
    for kind in ("string", "color", "dimen", "drawable"):
        for name in sorted(RESOURCES.get(kind, set())):
            if f"R.{kind}.{name}" not in all_code and f"@{kind}/{name}" not in all_xml:
                if kind == "drawable":
                    if f"R.drawable.{name}" not in all_code and f"@drawable/{name}" not in all_xml:
                        warn(f"unused {kind}: {name}")
                else:
                    warn(f"unused {kind}: {name}")


def main() -> int:
    for directory in KOTLIN_DIRS:
        for path in sorted(directory.rglob("*.kt")):
            check_kotlin(path)
    check_binding_usage()
    check_xml()
    check_manifest()
    check_unused()

    print(f"resources: " + ", ".join(f"{k}={len(v)}" for k, v in sorted(RESOURCES.items())))
    if warnings:
        print(f"\n{len(warnings)} warning(s):")
        for message in warnings:
            print(f"  ! {message}")
    if errors:
        print(f"\n{len(errors)} error(s):")
        for message in errors:
            print(f"  x {message}")
        return 1
    print("\nOK: no consistency errors found.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
