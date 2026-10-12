# Configuration

- Compose pins the itzg image by digest and Paper to Minecraft 1.21.11 build 132.
- Heap: 4 GiB initial, 8 GiB maximum; container limit 11 GiB; CPU quota 4 cores.
- Graceful Minecraft shutdown timeout is 240 seconds within a five-minute Docker grace period.
- Online mode, whitelist and whitelist enforcement are enabled; RCON, query and command blocks are disabled.
- TCP 25565 binds only to the Minecraft host's DMZ interface `192.168.30.50`; Nginx stream on `192.168.30.3:25565` relays it to the Paper server.
- NexoraCore defaults to 100 Coins (stored as 10,000 minor units), hub world `nexora_hub`, and 100 XP per skill level.
- NexoraCore SQLite database: `minecraft/plugins/NexoraCore/nexora.sqlite`.
- LuckPerms defaults to its embedded H2 data store. Regular permissions are not yet reviewed against a final player command matrix.
- No database password or paid plugin is used.

The hostname requires a Cloudflare DNS-only A record for `mc.pondsec.com` pointing to the webserver's public IPv4 and an SRV record `_minecraft._tcp.nexora.pondsec.com` targeting `mc.pondsec.com` on TCP 25565. Current proxied `nexora.pondsec.com` records can stay proxied for web traffic. See `CLOUDFLARE_DNS.md`.
