#!/usr/bin/env bash
# Run on marc-a2, pointing at an already uploaded release directory. No live mutations.
set -Eeuo pipefail
RELEASE=${1:?release directory required}
STAGE=${2:?new staging directory required}
[[ ! -e "$STAGE" ]] || { echo 'Stage already exists; choose a new directory'; exit 1; }
mkdir -p "$STAGE/plugins/NexoraCore" "$STAGE/plugins/NexoraFarm"
chmod 700 "$STAGE"
cp /opt/nexora/minecraft/paper-1.21.11-132.jar "$STAGE/paper.jar"
cp /opt/nexora/minecraft/eula.txt "$STAGE/eula.txt"
cp -r /opt/nexora/minecraft/libraries /opt/nexora/minecraft/versions "$STAGE/"
if [[ -d /opt/nexora/minecraft/cache ]]; then cp -r /opt/nexora/minecraft/cache "$STAGE/"; fi
cp /opt/nexora/minecraft/plugins/BentoBox-3.13.0.jar /opt/nexora/minecraft/plugins/VaultUnlocked-2.20.1.jar "$STAGE/plugins/"
cp "$RELEASE"/*.jar "$STAGE/plugins/"
# SQLite online backup snapshot, not a raw copy of a live WAL database.
cp "$RELEASE/baseline.sqlite" "$STAGE/plugins/NexoraCore/nexora.sqlite"
cat > "$STAGE/server.properties" <<'PROPERTIES'
server-ip=127.0.0.1
server-port=25575
online-mode=true
white-list=true
enforce-whitelist=true
enable-rcon=false
enable-query=false
level-name=nexora_hub
level-type=minecraft:flat
generate-structures=false
spawn-protection=0
view-distance=2
simulation-distance=2
max-players=4
allow-nether=false
sync-chunk-writes=true
PROPERTIES
cat > "$STAGE/plugins/NexoraFarm/config.yml" <<'YAML'
farm:
  world: nexora_farming
  animals:
    min-x: 0
    max-x: 0
    min-z: 0
    max-z: 0
    target-per-chunk-per-species: 0
YAML
printf '%s\n' "$STAGE"
