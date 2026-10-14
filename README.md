# NEXORA NETWORK – stable server baseline

Paper 1.21.11 build 132 / Java 21. This branch preserves the existing NexoraCore, NexoraProgression, NexoraTrading and NexoraFarm source verified against the live server on 12 October 2026. The production JAR hashes were checked again on 14 October and remain unchanged.

The four original plugin modules, existing island/farming systems, SQLite profiles, player markets, bank, Auction House and Infinite Rocket are retained. Source provenance is recorded in [baseline bytecode evidence](docs/evidence/SERVER_BASELINE_PARITY.json).

The progression update lives on [develop](https://github.com/PondSec/Nexora/tree/develop). It contains additional features and unfinished work and has not been deployed to production. See the [branch workflow](docs/DEVELOPMENT.md).

Build and test this baseline using a Java 21 JDK and Maven:

```bash
for module in source/*/pom.xml; do
  mvn -B -ntp -f "$module" clean verify
done
```

GitHub Actions verifies each branch; pushing code does not deploy Minecraft. Credentials, live player/world data and private backups remain outside Git.
