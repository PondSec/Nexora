# Installation and operations

Project directory: `/opt/nexora` on the dedicated SSD mounted at `/opt/nexora`.

```sh
cd /opt/nexora
docker compose ps
docker compose logs -f minecraft
docker compose restart minecraft
```

The deployment uses Paper 1.21.11 build 132 with Java 21, online-mode and whitelist enabled. RCON/query are disabled. Compose currently publishes TCP 25565 only on `127.0.0.1` for restricted testing. Do not switch the bind address to a public interface before completing `TEST_RESULTS.md` requirements.

Build NexoraCore from source on a Java 21 Maven runtime:

```sh
cd /opt/nexora/source/nexora-core
mvn test package
```

Copy `target/nexora-core-0.1.0.jar` to `minecraft/plugins/NexoraCore.jar`, then gracefully restart. The existing build has three passing store-level tests.
