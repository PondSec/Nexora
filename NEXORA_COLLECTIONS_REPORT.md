# Nexora collections and crafting implementation report

Date: 2026-10-10

## Live status update (2026-10-10 18:53 Europe/Berlin)

The rollback-era status below is superseded by this later verified deployment. `NexoraProgression.jar` is live on `marc-a2`, Paper reports healthy, and the startup log registers 50 existing Core collections and **118 recipes**. The package contains the existing resource-compression and Infinite Rocket recipes plus ten new collection-unlocked equipment recipes: four Farmer armor pieces, four Miner armor pieces, the Ember Blade, and the Ender Sword. New unlocks reconcile against each player's already-recorded collection totals on join, so eligible players do not need to grind those totals again. The new JAR SHA-256 is `ea84f081db09a6e9332d1561127c3ad4ef01b0902a22e88651362927f5c1961f`; NexoraCore is unchanged at `35060a8af040ab8d8cb9849f58b3f80f6b11124b35b90362dc20638a72b08ee39`. The pre-deploy backup is `/opt/nexora/backups/nexora-20261010T165218Z.tar.gz`, SHA-256 `1f13e29eaa040bc3789d7280cba83b75fa31a6cc7f752dfa223825da6d231340`.

The progression module passed **16 tests, 0 failures**. The equipment uses tagged vanilla armor/sword bases and therefore retains vanilla equipment behavior. Special armor bonuses, sword abilities, Minion upgrade recipes and the actual application of item-based upgrades to Nexora Minions are **not implemented**. Minion tiers still use the existing Coin upgrade flow. The new equipment's Bazaar/Auction listing path is also not yet verified; registry classification alone does not prove Trading serialization support. No live client click-through was performed.

## Collection reward visibility and farm target update (2026-10-10 19:07 Europe/Berlin)

Live on `marc-a2`: typed recipe-reward entries now appear in the `/collections` GUI; it lists the 50 collections with paging, shows tier thresholds and rewards, and opens a reward's recipe with all collection requirements. The existing Mining Navigation button has a safe-spawn fallback in the additive progression plugin. Farm target is one chicken, cow, pig, and sheep per generated island chunk. The sidecar removes only its own surplus, unnamed animals, preserving legacy/player-named animals. Paper returned healthy and logged 118 recipes. Progression tests: 17/17; Farm tests: 8/8. Progression JAR SHA-256 `090db2ebbecea042a0ff3a6499ea452ddb80d827f8f9d1823c62c0d03ac5c688`; Farm JAR SHA-256 `6aa745b2b33911a0755ea6c8ceb3380d951c34765ce42938fbc967eac97d0841`; Core unchanged at `35060a8af040ab8d8cb9849f58b3f80f6b11124b35b90362dc20638a72b08ee39`. Backup `/opt/nexora/backups/nexora-20261010T170514Z.tar.gz`, SHA-256 `0e11b23a3337a6e872ba4d4229756ae74be62b2cab60e7aa92b72be3747e31a4`.

Client verification of the new `/collections` GUI and the Mining button is `NOT TESTED`; logs confirm that `nexora_mining` loads, but no player click-through was performed. Farm scan warns about many generated chunks with no safe animal positions (for example, void/water areas); those chunks cannot meet the per-chunk target until the intended farm land bounds are narrowed or suitable platforms are identified. Special gear abilities and Minion upgrade recipes remain open.

## Current-state correction (2026-10-10, after verified rollback)

This document records a prior deployment and is **not** a statement of the current live server. The later rollback restored the NexoraCore baseline; the current live plugin inventory on `marc-a2` contains NexoraCore and NexoraTrading, but no NexoraFarm or progression/recipe plugin. Therefore the `/recipes` book and the 32-animal population described below are not present on the current server. Do not treat the historical `NexoraCore.jar` hash or deployment claims below as current.

Current local farm-sidecar status: the `NexoraFarm` source remains additive and has not been installed live. Its crop-growth handler now advances ageable farm crops at a configured 2× rate on natural grow events, while loaded crop chunks support growth without nearby players. Java 21/Paper 1.21.11 build completed with **5 tests passing, 0 failing**; current staging JAR SHA-256 is `7b1b1cabe198a1b7cec425e957f6281316430e1947cb8fcfe133500fef56826d`. Live deployment remains unverified because farm bounds, existing livestock positions, and real crop behavior have not been audited in-world after rollback.

## Historical deployment claims (superseded; not current live state)

- Added a persistent, per-player recipe book available from `/recipes`.
- Added 18 quick-craft compression recipes spanning basic mining drops, crops, and three log types. Recipes unlock permanently when the player reaches the configured collection count (default 1,000); each craft consumes the configured base cost (default 160).
- Stored recipe unlocks in SQLite with a unique player/recipe key and transactionally gated them against the player’s collection count.
- Bound recipe menus to the viewer, cancelled inventory movement, checked ingredients and output space before consumption, and rejected Nexora-tagged/minion-tagged ingredients.
- Added a FarmWorldService that adopts the existing livestock, targets 32 each for cows, pigs, sheep, chickens, and rabbits, spreads them around the existing farm, persists them, and replenishes losses. The server log reports 54 adopted and 106 spawned, reaching 32 per species.
- Set farming-world-only crop growth modifiers to 200% and force-loaded the 30 chunks that contain farm crops. Those crop chunks keep random-tick growth active when no player is nearby.
- Built for Java 21 / Paper 1.21.11. Deployed artifact SHA-256: `aece276ba6b10f675e8013f075171d3e67e189e1b0cd6cc83a9c69ca8f1dfc53`.
- Passed `RecipeUnlockTest` and `FarmWorldMathTest`: threshold gating, one-time unlock, player isolation, SQLite reopen persistence, exact population deficits, and deterministic spread bounds (3 tests, 0 failures). Main source compiled and packaged with Maven 3.9.9 / Temurin 21.
- Paper enabled NexoraCore and reached `Done`; its log confirms the farm population and crop chunks. No Hub world or Hub menu change was included.

## Remaining from the supplied full assignment

This deployment is a tested vertical slice, not the complete requested progression engine. The supplied target of 30+ collections, roughly 200 milestones, 75 recipes, 25 gameplay items, four armor sets, abilities, and full minion/Bazaar/Auction House integrations has not been reached. The current recipe book has 18 compression recipes; it does not yet implement the larger recipe catalog, custom item/armor/ability systems, all collection tiers/rewards, or Bazaar/Auction House.

The checked-in broader tests do not currently compile against the server source baseline: `MinionMathTest` and `ProgressStoreTest` call minion and progression APIs absent from that source. Those tests were preserved. The three passing targeted tests were run after isolating those incompatible test sources in the temporary build staging directory; they do not establish that the full suite passes. Full API reconciliation and comprehensive live-client testing remain open work.

## Server access and player reset

- Server whitelist enforcement is off in both Compose and `server.properties`; `whitelist.json` is empty. The server restarted with the new plugin and anyone may join, including Banane20TV.
- Banane20TV’s former island and Nexora/Bukkit/BentoBox personal progress files and rows were removed while the server was stopped. The complete pre-reset server backup is `/opt/nexora/backups/nexora-20261010T123359Z.tar.gz` with a matching SHA-256 sidecar.
