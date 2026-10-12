#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
: "${JAVA_HOME:?Set JAVA_HOME to a Java 21 JDK}"
"$JAVA_HOME/bin/java" -version
build_root="${NEXORA_BUILD_ROOT:-$HOME/.cache/nexora-build}"
mvn -B -ntp -Ppaper-smoke "-Dnexora.build.root=$build_root" clean verify
python3 scripts/verify_artifacts.py "$build_root/nexora-core/nexora-core-0.1.0.jar" "$build_root/nexora-progression/nexora-progression-0.1.0.jar" "$build_root/nexora-trading/nexora-trading-0.1.0.jar" "$build_root/nexora-farm/nexora-farm-0.1.0.jar"
"$JAVA_HOME/bin/java" -cp "$build_root/nexora-progression/classes" network.nexora.progression.CatalogExport docs
python3 scripts/release_manifest.py "$build_root" > docs/evidence/PROGRESSION_BUILD.json
