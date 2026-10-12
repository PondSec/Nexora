#!/usr/bin/env python3
"""Add an idempotent 200% crop-growth override for only nexora_farming."""

from __future__ import annotations

import os
import shutil
import sys
import tempfile
from pathlib import Path

import yaml


GROWTH = {
    "cactus-modifier": 200,
    "cane-modifier": 200,
    "melon-modifier": 200,
    "mushroom-modifier": 200,
    "pumpkin-modifier": 200,
    "beetroot-modifier": 200,
    "carrot-modifier": 200,
    "potato-modifier": 200,
    "wheat-modifier": 200,
    "netherwart-modifier": 200,
    "cocoa-modifier": 200,
    "bamboo-modifier": 200,
    "sweetberry-modifier": 200,
    "kelp-modifier": 200,
}


def main() -> int:
    path = Path(sys.argv[1] if len(sys.argv) == 2 else "/opt/nexora/minecraft/spigot.yml")
    original = path.read_text(encoding="utf-8")
    document = yaml.safe_load(original)
    if not isinstance(document, dict) or not isinstance(document.get("world-settings"), dict):
        raise SystemExit("spigot.yml has no valid world-settings mapping")

    world_settings = document["world-settings"]
    existing = world_settings.get("nexora_farming")
    if existing is not None:
        current_growth = existing.get("growth", {}) if isinstance(existing, dict) else {}
        if all(current_growth.get(key) == value for key, value in GROWTH.items()):
            print("nexora_farming growth override already matches the requested values")
            return 0
        raise SystemExit("nexora_farming already has a different override; refusing to replace it")

    lines = original.splitlines(keepends=True)
    start = next((i for i, line in enumerate(lines) if line.strip() == "world-settings:"), None)
    if start is None:
        raise SystemExit("Could not locate world-settings section")
    end = len(lines)
    for i in range(start + 1, len(lines)):
        line = lines[i]
        if line.strip() and not line.lstrip().startswith("#") and not line[0].isspace():
            end = i
            break

    snippet = ["  nexora_farming:\n", "    growth:\n"]
    snippet.extend(f"      {key}: {value}\n" for key, value in GROWTH.items())
    updated = "".join(lines[:end] + snippet + lines[end:])
    parsed = yaml.safe_load(updated)
    actual = parsed["world-settings"]["nexora_farming"]["growth"]
    if any(actual.get(key) != value for key, value in GROWTH.items()):
        raise SystemExit("Growth override did not validate after editing")

    backup = path.with_name(path.name + ".pre-nexora-farm")
    if not backup.exists():
        shutil.copy2(path, backup)
    fd, temporary_name = tempfile.mkstemp(prefix=path.name + ".", dir=path.parent)
    try:
        with os.fdopen(fd, "w", encoding="utf-8") as temporary:
            temporary.write(updated)
            temporary.flush()
            os.fsync(temporary.fileno())
        os.chmod(temporary_name, path.stat().st_mode)
        os.replace(temporary_name, path)
    finally:
        if os.path.exists(temporary_name):
            os.unlink(temporary_name)
    print(f"Installed and validated the farm-only growth override in {path}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
