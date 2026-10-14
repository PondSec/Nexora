# Nexora plugin and runtime manifest

Target runtime: Paper 1.21.11, Java 21, x86_64. Artifacts are pinned by version and SHA-256.

| Component | Version | Official source | SHA-256 |
|---|---:|---|---|
| Paper | 1.21.11 build 132 (stable) | [PaperMC Fill API](https://fill.papermc.io/v3/projects/paper/versions/1.21.11/builds/latest) | `5ffef465eeeb5f2a3c23a24419d97c51afd7dbb4923ff42df9a3f58bba1ccfba` |
| itzg Minecraft Server image | Java 21 variant, digest pinned | [itzg/docker-minecraft-server](https://github.com/itzg/docker-minecraft-server) | `sha256:3bbbbe72f4ea0c988416c9f7833c2bdd6bd35446f3d1f7f1516fb83d09f45de9` |
| BentoBox | 3.13.0 | [Hangar](https://hangar.papermc.io/BentoboxWorld/bentobox/versions/3.13.0) | `d580f56ef7f7508c8c5984702f76a5ced7849660ff00a3c293346bd611ade490` |
| BSkyBlock | 1.20.0 | [Hangar](https://hangar.papermc.io/BentoboxWorld/SkyBlock-BSkyBlock/versions/1.20.0) | `01b54da80210d0323491087794834638bca8dbcc8ea9432dbb9e28a218e524da` |
| BentoBox Level addon | 2.22.0 | [Hangar](https://hangar.papermc.io/BentoboxWorld/Level/versions/2.22.0) | `97c25b30e2d1663dd18a6dbf36f7db6d9367e4a057fe2b181d30f86504ff0142` |
| Multiverse-Core | 5.5.3 | [Hangar](https://hangar.papermc.io/Multiverse/Multiverse-Core/versions/5.5.3) | `4528e3bb08b5ebf255ed9dd5698b286abf4b84f6c13c33b8bf753a40d9adcafb` |
| LuckPerms Bukkit | 5.5.71 | [Modrinth](https://modrinth.com/plugin/luckperms) | `49cecb66fa1fd22a133039a490e9c1e5095a238e7cd66eb9d2a16fe6c897550d` |
| PlaceholderAPI | 2.12.3 | [GitHub Releases](https://github.com/PlaceholderAPI/PlaceholderAPI/releases/tag/2.12.3) | `fde03259f5af6938f3c33eeb4d814000a1adabf1d2304ce14970be81f609a437` |
| VaultUnlocked (Vault API compatible) | 2.20.1 | [GitHub Releases](https://github.com/TheNewEconomy/VaultUnlocked/releases/tag/2.20.1) | `4ec21910767a68b2defacb121cffee9840ded8b01abcbff03779fb9ab637296a` |
| NexoraCore | 0.1.0 | Local source in `source/nexora-core` | `8ae964b6e62778679e910c58b10523f7a47f92221286bead16720d8e716d85fe` |

Supplied Minecraft server-list logo: `assets/server-icon.png`, 64×64 PNG, SHA-256 `1ca5f85bd15de0d2c03b3b673abfb7b116d6685308a06cbd04acd315c8b9a0a8`. The logo is deployed at `/opt/nexora/minecraft/server-icon.png`.

The suggested third-party Bazaar, Minions and Collections plugins were not installed. Island-aware minions and collection tracking are implemented in NexoraCore; Bazaar order books and collection unlock/reward claims remain pending. No paid purchase was made.

