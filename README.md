# NEXORA NETWORK – SkyBlock

Minecraft Paper 1.21.11 / Java 21 SkyBlock RPG with four existing plugins: Core, Progression, Trading and Farm.

`main` preserves the verified server baseline. `develop` contains the active progression update, and `codex/*` branches hold feature work. See [development workflow](docs/DEVELOPMENT.md).

The active branch integrates shared versioned item identity, 58 collections, 152 custom item definitions and 152 advanced recipes, eighteen minion types, protected crafting/claim transfers, real player markets, and asynchronous collection/recipe/Bazaar overview loading. The existing shared Barn is preserved. Major gameplay expansion and some asynchronous transaction work remain unfinished; the production server has not been replaced.

The latest integrated checkpoint passed 101 automated tests, 526 real-Paper integration checks and a 30-client workload with 300 menu opens. Dedicated client checks cover trading, crafting, minion ownership/restarts, legacy market claims, crash recovery and restored inventories. These counts do not mean the complete requested progression update is finished. The new asynchronous inventory engine is an unintegrated draft.

Build with a Java 21 JDK:

```bash
export JAVA_HOME=/path/to/jdk21
scripts/build_progression.sh
```

Build outputs: `~/.cache/nexora-build/`. Exact tested source/artifact hashes: [build evidence](docs/evidence/PROGRESSION_BUILD.json).

- [Audit and baseline provenance](docs/PROGRESSION_AUDIT.md)
- [Gameplay milestones and unfinished acceptance](docs/GAMEPLAY_ROADMAP.md)
- [Test results and limitations](docs/TEST_REPORT.md)
- [Item catalog](docs/ITEM_CATALOG.md) · [Recipe catalog](docs/RECIPE_CATALOG.md)
- [Economy design](docs/ECONOMY_DESIGN.md)
- [Backup and restore](docs/BACKUP_RESTORE.md)

The [historical README](docs/HISTORICAL_README.md) records earlier project claims; the audit and current evidence supersede it.
