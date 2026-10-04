#!/usr/bin/env python3
"""Record the exact CI-signed release asset digest, after successful publication."""
import hashlib
import json
import sys
from pathlib import Path

root = Path(__file__).resolve().parents[1]
apk = Path(sys.argv[1]).resolve()
manifest_path = root / "update.json"
manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
assert apk.name == f"Zhangxinchuang-public-v{manifest['latest_version_name']}.apk"
assert apk.parent == root / "android"
manifest["sha256"] = hashlib.sha256(apk.read_bytes()).hexdigest()
manifest_path.write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
print(f"APK SHA-256: {manifest['sha256']}")
