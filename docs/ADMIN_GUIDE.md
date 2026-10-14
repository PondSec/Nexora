# Administrator guide

- Use SSH alias `marc-a2`, then `cd /opt/nexora`.
- Status/logs: `docker compose ps` and `docker compose logs -f minecraft`.
- Restart: `docker compose restart minecraft` (Paper performs a graceful stop).
- `/nexora status` is the NexoraCore diagnostic command and requires `nexora.admin` (default operator only).
- Island support commands are managed by BentoBox/BSkyBlock. Review BentoBox's generated config before changing island protection.
- Keep whitelist enabled. RCON is disabled. Add player UUIDs using the server console only after test identity and account are confirmed.
- Economy balances are persisted in NexoraCore SQLite and should be changed only through the registered economy API; never edit database rows while the server is running.
