#!/usr/bin/env python3
"""Check indexed paths for private/generated publication artifacts; never print file contents."""

from pathlib import PurePosixPath
import re
import subprocess
import sys


SYNTHETIC_FIXTURE = "src/test/resources/batch-synthetic.csv"
PRIVATE_DIRECTORIES = {
    ".idea", ".vscode", "target", "private", "local-data", "captures", "logs",
    "graphify-out", "__pycache__",
}
PRIVATE_EXTENSIONS = {
    ".csv", ".tsv", ".txt", ".properties", ".pem", ".key", ".p12", ".pfx",
    ".jks", ".keystore", ".pcap", ".pcapng", ".class", ".jar", ".so",
    ".dll", ".dylib", ".pyc",
}


def forbidden_reason(path, mode):
    normalized = PurePosixPath(path)
    name = normalized.name.lower()
    if mode not in {"100644", "100755"}:
        return "non-regular file (symlink or submodule)"
    if any(part.lower() in PRIVATE_DIRECTORIES for part in normalized.parts):
        return "private or generated directory"
    if normalized.parts[:2] == ("research", "output"):
        return "generated research output"
    if path.startswith("src/main/resources/") and path != "src/main/resources/logback.xml":
        return "unreviewed runtime resource"
    if path == SYNTHETIC_FIXTURE:
        return None
    if normalized.suffix.lower() in PRIVATE_EXTENSIONS:
        return "private input, key material, or generated artifact"
    if name == ".env" or name.startswith(".env."):
        return "environment file"
    if re.search(r"\.log(?:\.|$)", name):
        return "diagnostic log"
    if "credentials" in name and name.endswith(".json"):
        return "account credentials"
    if name == ".ds_store" or name.endswith(".iml"):
        return "local IDE or filesystem metadata"
    return None


def main():
    result = subprocess.run(
        ["git", "ls-files", "--stage", "-z"], capture_output=True, check=True,
    )
    violations = []
    checked = 0
    for entry in result.stdout.split(b"\0"):
        if not entry:
            continue
        metadata, raw_path = entry.split(b"\t", 1)
        mode, _, stage = metadata.decode("ascii").split()
        path = raw_path.decode("utf-8", errors="surrogateescape")
        reason = "unresolved merge entry" if stage != "0" else forbidden_reason(path, mode)
        if reason:
            violations.append((path, reason))
        checked += 1
    if violations:
        print("Publication blocked: remove these paths from Git's index (keep private files outside the checkout).", file=sys.stderr)
        for path, reason in violations:
            print(f"  {path!r}: {reason}", file=sys.stderr)
        return 1
    print(f"Public-file guard passed: {checked} indexed files. Content still requires privacy review.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
