# Branches and development

- `main`: stable source baseline verified against the running server. Promote tested work through a pull request. A Git push does not deploy Minecraft.
- `develop`: active integration branch. May contain explicitly documented work in progress; it is not a deployment promise.
- `codex/*`: focused feature branches, starting with `codex/progression-update`. Open feature pull requests against `develop`.
- `server-baseline-2026-10-12`: annotated tag for the preserved and bytecode-verified production baseline.

The original local commits and GitHub initial commit are retained. Do not force-push or rewrite shared history. Real credentials, Minecraft player/world data, private SQLite backups and generated build outputs remain outside Git.

GitHub Actions builds and tests Java 21 code on `main`, `develop`, feature pushes and pull requests. Actions are pinned to commit hashes. CI tests do not replace the isolated Paper/gameplay/recovery tests required before deployment.

## Build the active branch

```bash
export JAVA_HOME=/path/to/jdk21
scripts/build_progression.sh
```

Outputs are under `~/.cache/nexora-build`; source/artifact checksums and Surefire totals are written to `docs/evidence/PROGRESSION_BUILD.json`. Do not deploy the old per-module `target` directories.

## Current continuation point

The last fully integrated checkpoint has 101 passing unit tests, 526 Paper integration checks, real-client crafting/trading/minion/legacy-claim checks, a full staging restore and a 30-client/300-menu workload. Read `TEST_REPORT.md` and `GAMEPLAY_ROADMAP.md` for limits and missing systems. Production was not updated.

The new `InventoryTransferEngine` is a draft of the asynchronous inventory journal protocol. It compiles but is not connected to Bukkit or gameplay callers and has no dedicated acceptance yet. Next: test its state machine, add a main-thread Bukkit adapter and interaction guards, then migrate actual crafting and market transfers to it. Keep world/inventory access on the Minecraft thread and journal/receipt SQL on the worker. Test disconnect, duplicate-click, transaction-failure and hard-crash boundaries before replacing the proven synchronous path.
