# Backup and restore

Daily backups run at 04:30 local server time through the `nexora-backup.timer` systemd timer. The script gracefully stops only the Nexora Minecraft container, archives the world/plugin/source/configuration data, writes a SHA-256 checksum, then starts the container again. Seven days are retained under `/opt/nexora/backups`.

The backup lives on the same SSD as the live data; it protects against accidental deletion or bad updates but not SSD failure. Copy verified archives off-host for disaster recovery.

To verify an archive:

```sh
cd /opt/nexora/backups
sha256sum -c nexora-YYYYMMDDTHHMMSSZ.tar.gz.sha256
tar -tzf nexora-YYYYMMDDTHHMMSSZ.tar.gz >/dev/null
```

For restore, stop the Minecraft container, verify the archive, extract it into a staging directory, inspect its paths and database integrity, then restore only `/opt/nexora` project files. Keep the failed/current directory until the restored service has started and data is checked. Do not extract an unverified archive over a running server.
