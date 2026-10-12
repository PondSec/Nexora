# Progression audit — 2026-10-12

## Verified baseline and provenance

The active server is reached through `ssh marc-a2`. Minecraft is Paper 1.21.11 build 132; the host has Java 21. Four own plugins are installed. Before editing, downloaded all active Nexora JARs, `/opt/nexora/source`, active plugin configuration and an online SQLite-backup-API snapshot. Snapshot integrity check: `ok`; foreign-key check: empty. Private database snapshot is outside Git.

**Important:** `/opt/nexora/source` and its build targets are stale. Do not deploy their artifacts. The preserved workspace source was rebuilt; all **112 own class files** match the downloaded active JARs byte for byte (Core 21, Progression 20, Trading 64, Farm 7). Evidence: `evidence/SERVER_BASELINE_PARITY.json`. Initial matching compilation used Java 27 with release 21; Java 21 also builds Core/Farm, with compiler-generated lambda-name differences only. Active configs are in `config/server-baseline` as reference, never automatic deployment overrides. Existing local files were preserved in Git commit `5e66b3d` before changes.

## Working in source / present on server

- Core: UUID profiles, minor-unit purse and ledger, four skills, nonlinear levels, gathering collections, one-time milestone claims, daily quests, sidebar/navigation, hub and resource-world management; BentoBox owns islands and permissions.
- Farm: shared Barn harvesting/regrowth, farming progression through Core, bounded managed livestock, farm protection and configured chunk loading. Preserve this plugin and its world.
- Progression: 50 collection definitions, 118 advanced recipes, 100 enchanted/double-enchanted resource variants, ten tagged armor/weapons, five rocket components plus Infinite Rocket. Recipe book performs inventory simulation before committing changes. Collection unlocks reconcile existing progress.
- Trading: durable price/time Bazaar orders, partial fills, fees, cancellation/refunds, coin/item claims, a separate funded NPC pool, bank and interest, snapshot-based BIN/auction/bid/outbid/expiry and unique Rocket reservations shared across AH/Bazaar. Thus older “market not implemented” reports are obsolete.

## Partial / missing

- Registry is only used inside Progression; Core minions and Trading maintain independent identification. Item schema lacks stats/version/requirements/value metadata.
- Equipment retains vanilla behavior; RPG stats, mana, generic abilities and PurseSword missing.
- Fishing, enchanting and alchemy skill integrations missing. No fishing collections in baseline.
- Minions: twelve types and T1–XI timestamp production, but no functional upgrade/fuel slots, compressed mixed output, hoppers or material tier recipes. Slots tied to SkyBlock level rather than unique types.
- Mining world exists but is not a finished three-zone gated regenerating realm; Combat Realm/boss missing.
- Daily tasks exist, but no twelve-quest onboarding chain.
- Bazaar custom commodity integration, search/history completeness, AH filters, durable general inventory-operation recovery and full async SQL remain incomplete.
- Infinite Rocket already boosts Elytra with cooldown and speed limits; late-game component resources are not all available through designed public zones.

## Confirmed source defects / risks

1. Core uses deferred JDBC transactions on a connection alongside independent Trading/Progression writers. Read-then-write transactions can fail with SQLite busy snapshot despite busy timeout; use immediate writer acquisition, not swallowed retries.
2. Picking up a minion always yields T1. Upgraded tiers are lost.
3. Minion consumption clears main hand even for an offhand action. Entity equipment manipulation and inventory drag need explicit guards.
4. Database/inventory updates span two storage systems. Standard resource sells save removal before escrow; claims save tagged inventory before acknowledging. Crash/SQL failure and transferred tags can cause item loss or replay. Unique Rocket reservations cover one narrow path only.
5. Collection unlock calculation ignores required skill fields; requirements must be checked both in preview and actual craft.
6. Advanced crafting accepts a matching custom ID without validating material/version; vanilla workbench recipes can consume custom items by vanilla material.
7. Core, menus, Trading and regeneration issue synchronous SQL from the server thread. No 30-player performance guarantee can be made from unit tests.
8. Backup script pauses the entire live JVM while compressing the full installation; pause duration scales with world size. Full restore drill remains unverified.
9. Documentation contradicts active state. No root reactor, artifact/source provenance or coherent release manifest existed.

## Verification and order of work

Baseline: Core 12, Progression 17, Trading 39, Farm 8 automated tests pass (76 total). These are unit/database tests, not real-client gameplay tests. One overlapping Core build caused a test-class loading failure; reran isolated clean build successfully. No invented gameplay/load result.

Priority: M1 reconcile provenance, shared registry API, immediate transactions and recovery safety; M2 requirement/crafting validation and functional stats/skills; M3 existing minion extension and trading integration; M4 playable realms/equipment; M5 onboarding, UI, balance simulation, regression/staging and guarded release. Each milestone is complete only when all its required gates are satisfied. Document incomplete gates explicitly.

## Current implementation checkpoint (not full milestone acceptance)

The shared registry now validates IDs, material, version and stack size and mints all advanced outputs and minion tokens. Trading and minions consume the same API. Before/after inventory journaling covers commodity/NPC sells, AH listings, claims, crafting and minion placement/pickup/collection. Standardized Rocket sells now use this journal too; migration V6 records replay-safe order requests and allows the same delivered physical Rocket to be resold while retaining historical snapshots/claims. The original instance-only primary/unique keys prevented the second trade.

Immediate Core writer transactions, owner-scoped minion receipts, offhand consumption and portable tier preservation have regression tests. Centralized direct gathering replaces duplicate monster-drop counting; progression reconciliation runs after real gathering. XP listeners observe final event cancellation. The catalog now contains 152 custom definitions, 152 recipes and 58 collections, including fish and additional woods, and eighteen usable minion tokens. All seven skill XP event paths are present. Equipment still uses vanilla combat behavior; new definitions do not imply completed RPG mechanics.

Sidebar SQL snapshots run asynchronously with one outstanding request per player. Many other gameplay/market SQL calls remain synchronous. Fuel/upgrades, functional RPG stats, realms, onboarding quests, all-player crash/load testing and deployment acceptance remain open. Farm gameplay code was preserved. Build output lives outside the cloud-synchronized Documents folder after conflicting ` 2.class` paths were reproduced and rejected by Paper's remapper. Artifact verification now rejects these conflicts, duplicate entries and bytecode above Java 21.

Additional startup correction: queued minion chunk restoration waits for BentoBox readiness, processes at most four chunks per second and never deletes saved production because an island context is unavailable. The startup path formerly deleted minion rows if island lookup was empty. A genuine-client placed-minion restart test remains required.
