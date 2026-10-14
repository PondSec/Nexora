# Test results

| Area | Result | Evidence / limit |
|---|---|---|
| Paper / Java version | PASS | Startup log reports Paper 1.21.11 build 132; Java 21.0.12. |
| Docker server startup | PASS | Paper reached `Done` after plugin startup; container runs on SSD. |
| Container health / TCP port | PASS | Docker health reports `healthy`; Paper binds only to DMZ `192.168.30.50:25565`. Nginx stream on `192.168.30.3:25565` can connect to Paper. |
| Expected plugins enabled | PASS | BentoBox, BSkyBlock, Level, LuckPerms, VaultUnlocked, PlaceholderAPI, Multiverse-Core and NexoraCore appear in startup logs. |
| Vault provider / BentoBox hook | PASS | NexoraCore registered provider and BentoBox no longer logs “no plugin supporting economy”. End-to-end Bazaar/economy plugin use remains unavailable. |
| UUID profile/wallet isolation | PASS (unit) | Two UUIDs remain isolated in SQLite tests; the authenticated player `MrRichRick` joined the deployed server. No two-account live privacy/concurrency test. |
| SQLite restart persistence | PASS (unit) | Test closes and reopens DB; balance persists. No forced host reboot test. |
| Negative/overflow economy operations | PASS (unit) | Negative and overflowing deposits and overspend are rejected. No multiplayer concurrency load test. |
| Progression/collections/skills | PASS (unit/build), PARTIAL (live) | 12 automated tests pass. Persisted nonlinear level curves (100 SkyBlock / 50 skill caps), milestone claims, skills, collection counters, daily quest transactions and player-owned GUI views compile. Live client verification for multiple simultaneous players remains. |
| Sidebar and hotbar menu | PASS (build), PARTIAL (live) | Per-player board uses stable entries and in-place line updates; profile, progression, skills, collection categories, minions, daily tasks, and Hub/Island/Mining/Farming links are in the root menu. The protected compass occupies hotbar slot 9 and opens from hand or inventory click. Requires live client verification. |
| Progression feedback and anti-farm | PASS (unit/build), PARTIAL (live) | One-time claims are transactionally persisted; placed resource blocks do not count when broken; actionbar feedback is throttled. Live exploit review and economy balance remain. |
| Survival rules | PASS (unit/build), PARTIAL (live) | Food-level loss is canceled, join state is reset to full food/saturation, death keeps inventory and XP, and durability events for tools/weapons are canceled. A transactional purse charge removes half of current Coins on death except for island owners/team members dying on their own BentoBox island. Live client confirmation remains. |
| Hub / resource worlds | PARTIAL | Worlds and hub generation run at startup; `/hub` was issued in a live player session. Visual/client navigation test pending. |
| BentoBox island creation/protection | PARTIAL | `MrRichRick` joined, created an island, and used `/island`, `/collections`, `/coins`, and `/hub`; second-player isolation and container/break protection remain. |
| Minions | PARTIAL | Custom 12-type minion engine is deployed: island build validation, T1–T11, storage, timestamp/offline production, owner checks and chunk recovery. Unit tests pass. Live craft/place/collect/upgrade and crash testing remain; fuel, compactor, autosell and collect-all are not implemented. |
| Bazaar / Auction House | NOT IMPLEMENTED | No order books, escrow or auction delivery yet. |
| Whitelist / public reachability | PARTIAL | Whitelist enforcement is on; `MrRichRick` and `Banane20TV` are present in the server whitelist. Paper status returns 1.21.11 plus the configured favicon over the DMZ path. The workstation's current public TCP probe timed out; public DNS and WAN reachability should be rechecked from an external network. |
| Server-list favicon | PASS | Paper's Minecraft status response from `192.168.30.50:25565` contains the 64×64 supplied logo as a PNG favicon. |
| Backup/restore | PASS (archive checks) | Daily systemd timer enabled; fresh application-consistent archive created. SHA-256, tar integrity and SQLite `PRAGMA integrity_check` verified. Full service restore drill remains untested. |
| Shutdown/restart | PASS | Container restarted after icon and NexoraCore deployment and reached `Done`; live whitelist login succeeded. |
