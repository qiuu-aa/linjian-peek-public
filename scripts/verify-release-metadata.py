#!/usr/bin/env python3
"""Verify the single Android update manifest and release naming contract."""

from __future__ import annotations

import argparse
import json
import re
import sys
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
ANDROID_MANIFEST = ROOT / "android/app/src/main/AndroidManifest.xml"
UPDATE_MANIFEST = ROOT / "update.json"
LEGACY_UPDATE_MANIFEST = ROOT / "server/update.json"
BUILD_SCRIPT = ROOT / "android/build.sh"
MAIN_ACTIVITY = ROOT / "android/app/src/main/java/dev/linjian/peek/MainActivity.java"
REPOSITORY = "qiuu-aa/linjian-peek-public"


def fail(message: str) -> None:
    print(f"ERROR: {message}", file=sys.stderr)
    raise SystemExit(1)


def extract(pattern: str, text: str, label: str) -> str:
    match = re.search(pattern, text)
    if not match:
        fail(f"cannot find {label}")
    return match.group(1)


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--tag", help="Expected release tag, for example v0.3.9.0")
    args = parser.parse_args()

    if LEGACY_UPDATE_MANIFEST.exists():
        fail("server/update.json must not exist; update.json is the only canonical manifest")

    android_xml = ANDROID_MANIFEST.read_text(encoding="utf-8")
    version_name = extract(r'android:versionName="([^"]+)"', android_xml, "versionName")
    version_code = int(extract(r'android:versionCode="(\d+)"', android_xml, "versionCode"))
    tag = args.tag or f"v{version_name}"

    if tag != f"v{version_name}":
        fail(f"tag {tag!r} does not match Android version v{version_name}")

    update = json.loads(UPDATE_MANIFEST.read_text(encoding="utf-8"))
    if update.get("latest_version_name") != version_name:
        fail("update.json latest_version_name does not match AndroidManifest.xml")
    if update.get("latest_version_code") != version_code:
        fail("update.json latest_version_code does not match AndroidManifest.xml")

    apk_name = f"Zhangxinchuang-public-v{version_name}.apk"
    expected_url = f"https://github.com/{REPOSITORY}/releases/download/{tag}/{apk_name}"
    if update.get("apk_url") != expected_url:
        fail(f"apk_url must be {expected_url}")

    main_activity = MAIN_ACTIVITY.read_text(encoding="utf-8")
    expected_update_url = (
        f"https://raw.githubusercontent.com/{REPOSITORY}/main/update.json"
    )
    if expected_update_url not in main_activity:
        fail("MainActivity does not use the canonical qiuu-aa update.json URL")
    if "linzhi-524/linjian-peek-public" in main_activity:
        fail("MainActivity still depends on the upstream update source")

    build_script = BUILD_SCRIPT.read_text(encoding="utf-8")
    if apk_name not in build_script:
        fail(f"android/build.sh does not produce {apk_name}")

    print(f"OK: version={version_name} code={version_code} tag={tag}")
    print(f"OK: update={expected_update_url}")
    print(f"OK: apk={expected_url}")


if __name__ == "__main__":
    main()
