# Architecture

- One Paper backend in Docker; no proxy or external database.
- Docker bind mount `/opt/nexora/minecraft:/data`; custom plugin source is under `/opt/nexora/source/nexora-core`.
- NexoraCore owns UUID profiles and the Coins ledger in `plugins/NexoraCore/nexora.sqlite` (SQLite WAL, foreign keys, balance constraints, transaction IDs).
- Coins are stored in integer minor units. The Vault bridge is the single exposed Bukkit economy provider; BentoBox links through it.
- BentoBox owns SkyBlock island data and world protection. Multiverse-Core handles non-island world management. NexoraCore creates the hub and two resource-world folders.
- Collections/skills currently award from eligible uncancelled block-break events only. Bazaar, Auction House, Minions and escrow interfaces are future work; there is no shared market state yet.
- Data reads currently use synchronous SQLite calls from plugin event/command callbacks. This is suitable only for early low-volume validation; move to a bounded async repository before opening to players or scaling.

The default Paper world from the initial bootstrap is retained on disk and is not the configured Hub. No existing unrelated workloads were changed.
