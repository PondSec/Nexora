# Progression implementation status

This branch starts from the verified live JAR behavior. The downloaded server source directory is stale; do not replace the workspace with it. The existing shared Barn, island management, markets and Infinite Rocket were retained.

| Milestone | Implemented in current branch | Acceptance still required |
| --- | --- | --- |
| M1 Foundation | Provenance evidence; unified reactor/Java 21 artifacts; shared item API and version checks; SQLite immediate writer locking; owner-scoped inventory journal; migrations V5 asset identity, V6 replay/resale and V7 legacy partial-claim receipts; CRAFT hard-crash acceptance; 445-file staging restore; 30-client menu workload | All gameplay SQL asynchronous; additional domain crash points; production restore rehearsal; mixed 30-player gameplay load |
| M2 Progression | Shared preview/crafting rules; material/version validation; skill/collection gates; 58 collections; fish/wood compression; direct gathering shared by Core; seven skill XP event paths | Equipment stats and skill benefits; full custom shaped/shapeless grid; end-to-end unlock/craft/anti-farm gameplay |
| M3 Automation & Economy | Eighteen minion types; portable tiers; offhand handling; protected drag/equipment; durable minion inventory receipts; custom Bazaar commodities; bounded claim portions | Fuel/upgrades/compactors/smelter/hoppers/storage; material tier upgrades; unique-type slots; market search/history/UI gaps |
| M4 Gameplay Expansion | Existing armor and weapons remain usable with vanilla behavior; resource worlds retained | Stats, mana/abilities, PurseSword, expanded equipment, three-zone mining realm, combat realm and boss |
| M5 Player Experience | Bank sidebar, limited unlock feedback, existing recipe/collection/market/minion menus and Rocket | Twelve quest chain; balancing file/simulations; reachable late-game sources; complete gameplay/load regression and guarded deployment |

No milestone is marked complete merely because a subset compiles. Deployment is authorized by the user after testing; that gate remains unmet while critical acceptance above is open. Production has not been replaced. Continue with recovery/async Foundation work before expanding the gameplay surface.
