# Deployment report – 2026-10-10

## Host and storage

`ssh marc-a2` resolves to host `marc-a1` (Debian 13.7), 6 CPU cores, 31 GiB RAM. Dedicated 240 GB SATA SSD was identified by model/serial, wiped with the user's authorization, formatted ext4, mounted by UUID at `/opt/nexora`; current project filesystem has about 219 GiB free. Existing unrelated services were left alone.

## Deployed foundation

- Paper 1.21.11 build 132 / Java 21, Docker image pinned by digest.
- Plugins: BentoBox 3.13.0, BSkyBlock 1.20.0, Level 2.22.0, LuckPerms 5.5.71, PlaceholderAPI 2.12.3, Multiverse-Core 5.5.3, VaultUnlocked 2.20.1, NexoraCore 0.1.0.
- NexoraCore: UUID profile and SQLite persistence, Vault Coins provider, individual sidebar, protected hotbar menu item, owner-bound profile/progression/skills/collections/daily-task menus, nonlinear level curves, one-time progression rewards, and Hub/Island/Mining/Farming navigation. Survival rules disable hunger, keep inventory/XP on death, protect tools from durability loss, and charge half the purse on death except for island owners/team members on their own island. Custom minions support 12 resource types, island build checks, persistent T1–T11 upgrades/storage and capped timestamp production.
- The supplied NEXORA logo is deployed as the 64×64 Minecraft server favicon. A Paper status handshake to the DMZ listener confirmed the favicon is served.
- Worlds: `nexora_hub`, `nexora_mining`, `nexora_farming`, and BentoBox `bskyblock_world`.
- Compose limit: 11 GiB container / 8 GiB Java max. Current observed use during startup was about 4.7 GiB. Paper binds TCP 25565 only on DMZ address `192.168.30.50`; Nginx stream on `192.168.30.3:25565` reaches it. OPNsense has an active WAN TCP 25565 DNAT/pass rule to Nginx. Whitelist enforcement remains on.

## Public endpoint

The user requested public reachability. Nginx stream, Paper, and OPNsense DNAT/pass are configured. A Minecraft status handshake to Paper on the DMZ listener returns version 1.21.11 and the logo favicon. DNS previously resolved the SRV record, but the workstation's current TCP probe to the WAN hostname timed out; recheck from an external network before announcing public access. `MrRichRick` has logged in, created an island, and used basic commands. To keep the web hostname behind Cloudflare's HTTP proxy, use a separate DNS-only `mc.pondsec.com` A target for the SRV record.

## Verification matrix

See `TEST_RESULTS.md`. Automated progression, store and minion tests: 12 passed, 0 failed. The latest NexoraCore jar hash matches the deployed artifact; Paper enabled it and reached `Done` without plugin errors. The server was inside the Docker healthcheck start period during the final check. Icon status response and a real-player island session were verified. Bazaar, Auction House and full launch validation remain incomplete.

## Remaining blockers before launch

Player-driven Bazaar and Auction House, fuel/compactor minion add-ons, collection reward claims, asynchronous SQL, final permission review, second-player tests, full restore drill, and public WAN retest remain incomplete. The user authorized public connectivity, so TCP 25565 is forwarded while whitelist enforcement remains enabled. Paper startup and the BentoBox economy hook are healthy; no fatal startup error was observed.
