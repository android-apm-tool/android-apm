"""Local tool-bundle sealing. Run during preparation, without a customer project mounted."""
import argparse
import hashlib
import json
from pathlib import Path
import stat

MANIFEST = "bundle-manifest.json"


def digest_file(path):
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def inventory(root):
    result = []
    for path in sorted(root.rglob("*")):
        mode = path.lstat().st_mode
        if path.name == MANIFEST and path.parent == root:
            continue
        if stat.S_ISDIR(mode):
            continue
        if not stat.S_ISREG(mode):
            raise ValueError("BUNDLE_UNSUPPORTED_FILE")
        result.append({"path": path.relative_to(root).as_posix(), "sha256": digest_file(path), "size": path.stat().st_size})
    return result


def seal(root):
    root = Path(root)
    manifest = {"schemaVersion": 1, "kind": "PREPARED_LOCAL_TOOLS", "files": inventory(root)}
    (root / MANIFEST).write_text(json.dumps(manifest, sort_keys=True, indent=2) + "\n")
    return digest_file(root / MANIFEST)


def verify(root):
    root = Path(root)
    path = root / MANIFEST
    if not path.is_file() or path.is_symlink():
        raise ValueError("BUNDLE_MISMATCH")
    manifest = json.loads(path.read_text())
    if manifest.get("schemaVersion") != 1 or manifest.get("files") != inventory(root):
        raise ValueError("BUNDLE_MISMATCH")
    return digest_file(path)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("bundle")
    args = parser.parse_args()
    print(json.dumps({"bundleDigest": seal(args.bundle)}))
