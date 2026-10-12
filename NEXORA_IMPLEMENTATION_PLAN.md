# Nexora – verbindlicher Umsetzungsplan

## Mining warp hotfix deployed (2026-10-10 19:24 Europe/Berlin)

The live `nexora_mining` spawn is `(0, 97, 0)`. The earlier fallback searched only 24 blocks and missed the playable area. It now checks nearby columns and samples existing generated chunks up to 512 blocks away, with a solid floor and two clear blocks required, and does not generate terrain. All 17 progression tests passed; Paper loaded the add-on, reached `Done`, and Docker reports healthy. JAR SHA-256: `47ee405b017f036f2200c6261720bce7baf6b7b7803451fa62aa8adcaf20c992`. Verified pre-deploy backup: `/opt/nexora/backups/nexora-20261010T172200Z.tar.gz` (SHA-256 `4c59a5109efb7e0b7ec2c84d9a6867e149cb964dba8d56068997e6c1be3f3fcd`). Client click-through remains `NOT TESTED`.

## Latest deployment note (2026-10-10 18:53 Europe/Berlin)

The progression add-on now exposes ten additional Collection-gated gear recipes (Farmer and Miner armor pieces, Ember Blade, Ender Sword); total registered catalog is 118 recipes across 50 Core collections. The package passed 16 module tests, deployment returned Paper to healthy, and the pre-deploy snapshot is `/opt/nexora/backups/nexora-20261010T165218Z.tar.gz` (SHA-256 `1f13e29eaa040bc3789d7280cba83b75fa31a6cc7f752dfa223825da6d231340`). These gear items use vanilla armor/sword behavior only. Item abilities, Minion upgrade recipes and their MinionService integration, and verified Bazaar/AH item trading remain incomplete; the broader plan below stays active.

## Follow-up deployment verified (2026-10-10 19:07 Europe/Berlin)

Collection reward definitions and the `/collections` reward GUI are live in the additive progression module; the existing Mining Navigation button has a safe-spawn fallback. Farm target is one of each configured species per generated chunk; only managed unnamed surplus is removed. Progression tests are 17/17 and Farm tests 8/8. Paper is healthy and registered 118 recipes. Client clicks for the new collection GUI and Mining teleport remain `NOT TESTED`. Farm logs still show generated void/water chunks without safe positions; exact walkable island bounds need in-world verification. Minion upgrade recipes, special gear effects, and item trade integration remain outstanding.

**Ziel:** Den Auftrag „NEXORA NETWORK – COLLECTIONS, CUSTOM ITEMS, CRAFTING, BAZAAR & AUCTION HOUSE“ vollständig und überprüfbar umsetzen. Der zuletzt bestätigte funktionierende Rollback-Stand ist die Produktions-Baseline. Alle Änderungen müssen kompatibel und schrittweise freigabefähig sein.

**Quelle der fachlichen Anforderungen:** `/Users/pond/.codex/attachments/a3697356-6b07-4880-a7d4-8ca8e5dd3f03/Eingefügter Text.txt` (2.566 Zeilen, vollständig gelesen). Früher bestätigte Farm-Aufträge sind ebenfalls Teil des Ziels.

## Unveränderliche Leitplanken

1. Bestehenden Hub, Welten, BentoBox-Inseln, Profile, Spielerdaten, Coins, Minions und den offenen Whitelist-Status erhalten. Keine Hub- oder Weltgestaltung ändern.
2. Nie den produktiven NexoraCore-JAR mit einem Build aus dem aktuell vorhandenen Core-Source ersetzen, solange die Source-/Artefakt-Parität nicht nachgewiesen ist. Das Live-Artefakt enthält Funktionen, die im wiederhergestellten Source nicht enthalten sind.
3. Keine bestehende Datenbankdatei, Playerdatei oder Welt durch Migration/Deploy überschreiben. Migrationen sind versioniert, idempotent, transaktional und vorwärts-/rückwärts dokumentiert.
4. Neue Funktionen zunächst isoliert entwickeln und auf Staging bzw. einer Serverkopie testen. Vor jedem Live-Deploy konsistentes Backup samt SHA-256 erzeugen; nur freigegebene Artefakte kopieren.
5. Coins verwenden genau eine autoritative Profil-Purse. Marketplace-Escrow, Claims und Ledgerbuchungen müssen dieselbe persistente Transaktionsgrenze besitzen. Vaults Double-API allein ist dafür nicht ausreichend.
6. Inhalte gelten nur dann als fertig, wenn Rezept, Effekt, Unlock, Speicherung, GUI und Tests tatsächlich funktionieren. Nicht implementiertes/nicht getestetes Material bleibt `NOT TESTED`.
7. Farm: 32 Tiere je unterstützter Tierart, räumlich über die Farm verteilt; Pflanzen wachsen doppelt so schnell und weiter, wenn kein Spieler anwesend ist; nur reife Nutzpflanzen sind erntbar; übriger Abbau, Platzieren und Trampelschäden sind blockiert.
8. Alpha-Wirtschaft: Ein einzelner Spieler muss handeln und craften können. Begrenzte, als Systemangebote markierte NPC-Liquidität darf echte Bazaar-Orders kreuzen, ist an persistente Bestände und Coin-Budgets gebunden und wird bei steigender Spielerliquidität zurückgefahren. Restocks sind nur über explizite Limits erlaubt. Keine NPC-AH-Gebote, erfundenen Handelsstatistiken oder unbegrenzten Ankäufe.

## Festes Ziel und Akzeptanz

Vollständige Spielerreise aus dem Auftrag: Ressourcen legitim sammeln → Collection-Tiers und Rezept freischalten → echte Custom Items craften → Minion-Upgrade installieren und wirksam nutzen → handelbare Ressourcen sicher im Bazaar einstellen/kaufen → Ausrüstung mit Metadaten im Auction House als BIN/Auktion handeln → nach Neustart identische Profile, Purse, Fortschritte, Items, Orders und Claims vorfinden. Zwei oder mehr Profile müssen isolierte Progression und gemeinsame Marktplätze haben. Parallel müssen alle obigen Farm-Regeln erfüllt sein.

Der Abschlussbericht `NEXORA_CONTENT_ECONOMY_REPORT.md` und eine maschinenlesbare Content-Matrix werden erst nach den zugehörigen Implementierungen mit echten Ergebnissen befüllt. Matrixfelder: Collection, Tier, Item ID, Recipe ID, Effect, Implemented, Tested; Status `PASS`, `FAIL`, `NOT TESTED`.

**Hub-NPC-Standorte vom Nutzer:** Bazaar Master in `nexora_hub` bei F3 `(-85.871, 82, 341.546)`, Auction Master bei `(-80.501, 79, 368.543)`, Nexora Bank bei `(-65.174, 80, 323.641)`. Werte sind in `source/nexora-trading/src/main/resources/npcs.yml` vorgemerkt. Vor Live-Spawn diese exakten Stellen auf freie NPC-Fuß-/Kopfhöhe prüfen; keine Blöcke oder vorhandene Hub-Geometrie verändern. Spawns nach NPC-ID idempotent ausführen und die NPCs auf genau diese Koordinaten setzen. Die Bank akzeptiert Ein-/Auszahlungen zwischen Purse und Bank und soll pro vollständig vergangene 24 Stunden 2% Zinsen gutschreiben. Zinsgutschriften brauchen einen eindeutigen Tages-/Periodenschlüssel und einen transaktionalen Schutz vor mehrfacher Auszahlung.

## Audit-Befund zum Start

- Der aktuelle Core-Source besitzt Profile, Vault-Coins, SQLite, Skills, Sammlungsmengen, konfigurierbare Sammlungs-Meilensteine, Daily Tasks, Menüs und Minions.
- Sammlungs-Meilensteine vergeben derzeit generisches Skill-/SkyBlock-XP. Ein persistentes Item-/Recipe-Registry, individuelle Recipe-Rewards, Custom-Stats/Abilities, Bazaar, Auktionen und deren Escrow/Claims sind in diesem Source nicht vorhanden.
- Vorhandene Sammlungsanzeige ist eine Liste mit begrenzten Kategorien und gemeinsamen konfigurierten Schwellen, keine Tier-Detailansicht mit Reward-Graph.
- Core speichert Profil, Coins, XP, Sammlungen, Fortschrittsansprüche, platzierte Blöcke, Minions und Daily Tasks in einer SQLite-Datei. DB-Zugriffe finden derzeit synchron statt.
- Die existierende Minion-Engine hat bereits 12 Typen, BentoBox-Prüfungen, persistente T1–T11-Produktion und ein GUI. Sie darf nicht ersetzt werden; Upgrades müssen daran anschließen.
- `ssh marc-a2` nutzt die Inventar-Konfiguration für 192.168.30.50 und meldet Hostname `marc-a1`. Zugriff auf Docker benötigt sudo. Produktionsabfragen/Deploys werden separat protokolliert.
- Lesende Live-Prüfung nach Rollback: Core SHA-256 `35060a8af040ab8dcb9849f58b3f80f6b11124b35b90362dc20638a72b08ee39`, Paper-Log meldet Plugin enabled und `Done`, `white-list=false`, Compose- und Servereigenschaft beide offen. SQLite `integrity_check=ok`; 2 Profile, 12 befüllte Collection-Schlüssel, 3 gespeicherte Minions, 37 Economy-Ledger-Zeilen, 76 Progress-Claims und 80 platzierte Ressourcenblöcke. Keine DB-Änderung bei dieser Prüfung.
- Produktions-JAR aus der stabilen Sicherung und lokaler wiederhergestellter Source sind nicht byte-/funktionsgleich. Die frühere farm-/rezept-erweiterte JAR-Kopie wurde durch vollständigen Rollback bewusst entfernt. Sie wird nicht ungeprüft wieder eingespielt.

## Schritte und Freigabegates

### 0. Baseline versiegeln und Inventar abschließen

- Nur lesend die aktuelle Serverkonfiguration, installierten Plugins/JAR-Hashes, Welten, DB-Schema/Counts, Minions, Farm-Schutz, Wachstum, Whitelist und Backup-Ketten erfassen.
- Bestand an Collections, Skills, Reward-Schwellen, Ressourcenquellen und Minion-Typen direkt aus Source und Live-DB inventarisieren; alte Reports gegen den wiederhergestellten Ist-Stand korrigieren.
- Exakte Herkunft und Source zum laufenden Core-Artefakt suchen (Rollback-Ordner, Backups, Build-Metadaten). Bestehende Regeln/Minions/Hubs mit Smoke-Prüfung erfassen.
- **Gate:** dokumentierte Baseline und wiederherstellbarer, gehashter Backup-Nachweis. Noch keine Produktionsänderung.

### 1. Architektur und Datenparität

- Live-Core-/Source-Parität auflösen. Falls originale Source fehlt, Core-Änderungen stoppen, fehlende Funktionen rekonstruieren und gegen beobachtetes Live-Verhalten vergleichen; keine spekulative Überschreibung.
- Öffentliche, versionsfähige Nexora-Services für Profil-ID, integerbasierte Coins/Ledger und Minion-Upgrades definieren. Bazaar/AH müssen Profile und Economy atomic reservieren können. Entscheiden, ob die bestehende SQLite durch eine kontrollierte Core-Erweiterung verwendet wird oder ein unterstützter gemeinsamer Transaktionsdienst entsteht; keine parallelen SQLite-Schreiber.
- Schema-, API- und Modulgrenzen festschreiben. Paper-Main-Thread frei von SQL/Netzwerk-I/O halten; Migration auf begrenzte Async-Repositories planen, ohne Bukkit-Objekte off-thread anzufassen.
- **Gate:** Architekturentscheid mit dokumentierten Invarianten, Source-Parität und Migrations-/Rollback-Probe.

### 2. Fundament: Item-, Content- und Recipe-Registries

- Stabile Namespaced Item-IDs im PersistentDataContainer; versionsfähige Item-Snapshots, Validierung und Kompatibilität zu bestehendem Inventar/Minions.
- Ein RecipeRegistry mit eindeutigem Output, Zutaten, Crafting-Art, Collection-Unlock und Handler; serverseitige Prüfung für 3×3- und fortgeschrittenes Crafting. Shift-Click/Quick-Craft gegen Duplikate absichern.
- Einheitliches Stat-/Rarity-/Accessory-/Armor-/Ability-Modell mit Set- und Stack-Regeln, Cooldowns, Mana, Region/PvP-Prüfung und Feedback.
- Enchanted-Material-Konversionen, Rezepte und Testdaten zunächst als Dateninhalt registrieren. Validator bricht bei unbekannten IDs, fehlenden Handlern, Zyklen oder nicht erreichbaren Recipes ab.
- **Gate:** Validator grün; jedes registrierte Item serialisiert/deserialisiert; Rezepttests decken Zutaten, Output, Unlock, Shift-Click und Metadaten ab.

### 3. Collection-Reward-Engine und Migration

- Bestehende Sammlungen bewahren und Basismaterial-Zuordnung um alle tatsächlich vorhandenen Mining-, Farming-, Combat-, Foraging- und ggf. Fishing-Collections erweitern.
- Collection-Definitionen mit eigenen Mengenanforderungen und 12 Stufen unterstützen. Vorhandene valide Schwellen bleiben erhalten; Standard I–XII aus Auftrag nur dort verwenden, wo es keine konsistente Bestandstabelle gibt.
- Reward-Typen aus Auftrag implementieren (Recipe-/Item-Recipe-/Minion-/Upgrade-/Utility-/Storage-/Ability-/Stat-/Area-Unlock, Skill-/SkyBlock-XP, Coins) als typisierte, idempotente Ansprüche mit Historie.
- Nur bestätigte legitime Sammelereignisse zählen; platzierte Blöcke, gekaufte/gehandelte Items, Minion-Ausgabe, Drops nach Ablegen/Wiederaufnehmen und Crafting-Schleifen dürfen keine Sammel-XP erzeugen.
- Migration schaltet aufgrund bestehenden Tiers rückwirkend nur Rezepte/Unlocks frei. Keine doppelten Coins-/XP-Auszahlungen und keine nachträgliche Notification-Flut.
- **Gate:** Replay-/Restart-/Idempotenztests und Vergleich der Altbestände; Null Differenz an Wallets/Spielerdaten durch reine Unlock-Migration.

### 4. Rezeptbuch, Collection-GUIs und Progression

- `/collections`: echte Kategorien, Tier/Anforderung/Progress, erledigte/gesperrte Stufen, Rewards und anklickbare Rezeptdetails.
- `/recipes`: Kategorien, Anforderungen, Ingredient-Status, Effekte, Vorschau und sicheres Quick Craft. Keine bloßen Namen ohne Handler.
- Discovery-, Level-up-, Rezept- und Crafting-Feedback mit Bündelung und Spam-Schutz.
- **Gate:** GUI-Holder/Viewersicherheit, Klick-/Drag-Sperre, vollständige Navigation und getestete Spielerreise vom Unlock bis Craft.

### 5. Inhalt und Minion-Integration

- Content nach Domänen liefern: Mining (Cobble, Coal, Iron, Gold, Diamond, Emerald, Redstone, Lapis, Quartz, Obsidian, Glowstone, Gravel, Sand, End Stone und vorhandene weitere), Farming inkl. Erntewerkzeuge/Armor/Accessories, Combat Waffen/Armor/Abilities, Foraging Holz/Tools/Utilities; bestehende Fishing-/Tierprodukt-Collections bewahren.
- Rezepte, Werte und Effekte gemäß Spezifikation als konfigurierbare Nexora-Balance ausarbeiten; ausschließlich wirklich implementierte Effekte als freigeschaltet markieren.
- Vorhandene Minion-Typen: Tier-I-Rezept und Material-Tier-Upgrades integrieren. Quick Upgrade im existierenden Minion-GUI; Materialkosten anzeigen und atomar abbuchen. Compactor-/Super-Compactor-Verarbeitung als echte Funktion, ohne Items kostenlos zu vervielfachen.
- Farm-Sidecar separat fertigstellen: Tieranzahl und Verteilung real in der Farm verifizieren, pro-Farmwelt-Wachstum auf 200%, verlässlich offline durch begrenztes Laden der Crop-Chunks; Schutzregeln und Trampelprävention in Eventtests und live mit Testprofil prüfen. Core-/Hub-/Minion-Dateien nicht anfassen.
- **Gate:** pro Inhaltseintrag Rezept+Effekt+Handler+Collection-Matrix+Test; keine Massenfreischaltung defekter Items.

## Deployment-Status 2026-10-10

Das additive Plugin `NexoraTrading` ist auf `marc-a2` live. Das vollständige Backup vor der aktuellen Bazaar-/Schild-GUI-Änderung liegt unter `/opt/nexora/backups/nexora-20261010T145738Z.tar.gz` (SHA-256 `d5823dfa61a30c2c3770776babef433543c0e7c2bba3e204c78bd47218dc1aee`). Die live Trading-JAR hat SHA-256 `41b3bc581a68fbbc12d24b54afca027507146358bf2c413204d359ec180ea5bf`; NexoraCore blieb unverändert bei SHA-256 `35060a8af040ab8d8cb9849f58b3f80f6b11124b35b90362dc20638a72b08ee39`.

- Paper 1.21.11 meldete `NexoraTrading` enabled und danach `Done`; keine Trading-Fehler. Die sicheren Fuß-/Kopfpunkte wurden geprüft, und Bazaar Master, Auction Master sowie Bank NPC wurden in `nexora_hub` an den vom Nutzer gelieferten Koordinaten konfiguriert.
- SQLite-Migrationen 1–3 liefen zunächst auf einer konsistenten Kopie der aktiven Core-Datenbank (Integritätsprüfung `ok`) und anschließend live. Die produktive DB meldet weiter `integrity_check=ok`.
- Vor dem Trading-Start: 2 Profile, 47 Economy-Ledger-Einträge, 15 Collections, 3 Minions und insgesamt 17.610.575 Coins in Purse. Danach: 2 Profile, 49 Ledger-Einträge, 15 Collections, 3 Minions; Purse plus Bank bleibt 17.610.575 Minor-Units. Die zwei zusätzlichen Ledgerzeilen sind die Bank-Einzahlung und 10%-Abhebung des Spielers während des Deployments; beide wurden ausgeglichen und die Coins blieben vollständig erhalten.
- Live registriert: 47 explizite vanilla-stapelbare Rohstoff-IDs und 47 persistente NPC-Liquiditätskonten. NPC-Bestände und Budgets sind begrenzt, Preisabstand/Fees verhindern profitable Rückkauf-Arbitrage; automatische NPC-Restocks sind im Live-Default 0.
- Verifiziert: 32 Maven-Tests mit 0 Fehlern; parallele Wallet- und Idempotenztests, Bazaar-Partial-Fills/Fees/Refunds, NPC-Budget/Bestandsgrenzen, AH-BIN/Biet-Escrow/Outbid-Refund/Expiry sowie Verkäuferabbruch ohne Gebote und exakter Item-Claim. Das shaded JAR lädt SQLite unter Java 21. Live Paper meldete `NexoraTrading enabled` und `Done`; DB-Version 3 und `integrity_check=ok`.
- Auction House wird vollständig über Hub-NPC und Inventar-GUIs bedient. `/ah` ist nicht registriert; der Listingpreis wird über den Minecraft-Schildeditor eingegeben (temporärer markierter Luftblock, Cleanup bei Eingabe, Abbruch/Timeout, Spieler-Logout und Plugin-Neustart) und anschließend bestätigt. Kauf/Gebot verlangen Bestätigung. Erstellung, Laufzeitwahl, Review, eigene Listings, führende Gebote, Claim und Abbruch ungebotener Listings sind enthalten.
- Bazaar-GUI deckt jetzt NPC Instant Buy/Sell, Buy Orders und Sell Offers mit Bestätigungsdialog, Meine Orders, Stornierung und Claim ab. Menge ×1/×16/×64 zeigt die Gesamtkosten inklusive Gebühr bzw. erwartete Nettoerlöse. Neue Trading-JAR wurde nach frischem Backup atomar eingespielt; Paper meldete `NexoraTrading enabled` und danach `Done`. 32 Maven-Tests bestanden, 0 Fehler. Echte Client-Klick-/Mehrspielertests bleiben `NOT TESTED`.
- Noch offen: Anbindung der nicht vorhandenen Core-ItemRegistry (darum sind Enchanted-/Nexora-Custom-IDs noch nicht im Bazaar), persistente NPC-Quotes werden direkt aus ihren finanzierten Konten ausgeführt, sind aber noch keine `bazaar_orders` im gemeinsamen Orderbuch; Bazaar-Preis-History/Statistik; AH-Suche/Sortierung/Historie und echte Multiplayer-/GUI-Klicktests.
- Der vollständige Masterplan ist **nicht abgeschlossen**: Collection-Rewards/Recipe Unlocks, Recipe Book/Crafting, Minion-Upgrades, Farm-Verifikation und die übrigen Akzeptanzgates bleiben aktiv. Es wurden dafür weder Platzhalter freigeschaltet noch NexoraCore, Hub-Blöcke, Farmwelt oder Spielerdaten überschrieben.

### 6. Gemeinsame Wallet-, Ledger- und Escrow-Dienste

- Integer-Minor-Units (100 pro Coin), eindeutige Idempotency Keys, Ledgerreferenz, Sperr-/Versionsstrategie und Audit für jede Buchung.
- Escrow reserviert verfügbare Coins/Items, zeigt korrekte verfügbare Purse im Scoreboard und gibt Refund/Claim genau einmal frei.
- Offline-Profile, Inventarüberlauf, Servercrash zwischen Datenbank und Spielinventar, Recovery/Claims und Vault-Bridge-Rundung ausdrücklich behandeln.
- **Gate:** Property-/Concurrency-Tests beweisen keine negativen Bilanzen, Doppelzahlungen, Doppelitems, doppelten Claims oder Escrow-Verletzungen.

### 7. NexoraBazaar – echte Spielerorders

- Nur explizit registrierte Bazaar-IDs zulassen. Produkte werden über ItemRegistry referenziert.
- Preis-Zeit-Orderbuch; Buy/Sell-Limits, vollständige Instant-Fills oder klare Ablehnung, Cancel, Partial/Full Fills, Preisverbesserung, Gebühren, Claims, Audit, Preis-/Trade-Historie und Persistenz.
- Atomarer Fill sperrt relevante Orders/Wallets, prüft Mengen erneut und überträgt exakt min(Restmenge). Eigene Gegenorders blockieren.
- 54-Slot-Kategorien- und Item-GUIs, Ordermanagement, Confirmation und reale Daten; bei leeren Büchern „No active orders“. Kein Fake-Preis. Die neue Alpha-Anforderung erfordert begrenzte NPC-Liquidität als gekennzeichnete, persistente Orders. Echte Bestands-/Coin-Escrows, Restock-Audit und adaptive Teilnahme sind vor Livebetrieb zwingend.
- **Gate:** zwei Profile, Rennen, Partial-Fills, Restart/Crash-Recovery und Gebührenausgleich automatisiert erfolgreich.

### 8. NexoraAuctionHouse – BIN und Auktionen

- Versionierte validierte Item-Snapshots und Lifecycle DRAFT/ACTIVE/SOLD/ENDED/EXPIRED/CANCELLED/CLAIMED.
- BIN mit atomarer Einmaligkeit, Auktion mit Escrow, Gebotsminimum, Refund, Anti-Sniping, Ende/Abholung, Verkäuferauszahlung, Suche/Sortierung/Filter und Historie.
- 54-Slot-Browser/Detail/Confirm/Create/My Listings/My Bids/Claim GUIs; GUI-Klick löst Kauf nie ohne Bestätigung aus.
- Custom-Item-Metadaten über Verkauf erhalten; Minions nur nach nachgewiesener sicherer portabler Darstellung erlauben.
- **Gate:** parallele Käufer/Gebote, Race Expiration/Cancel, Outbid-Refund und Restart-Persistenz automatisiert erfolgreich.

### 9. Hub-Zugänge und Integration

- `/bazaar` bleibt als alternativer Einstieg. Das Auction House wird ausschließlich über den Hub-NPC und seine GUI geöffnet und bedient; kein `/ah`-Befehl. Hub-NPC-Integration nur additiv und ohne Veränderung vorhandener Hub-Geometrie.
- Economy, Scoreboard, BentoBox/Profile, Collections, Recipes, Minions und ItemRegistry gegen definierte APIs integrieren; genau ein Wallet-/Economy-Provider.
- **Gate:** jede Integration hat isolierte Tests und Hub-Smoke-Check; alter Hub-Stand und Funktion unverändert.

### 10. Gesamtvalidierung und stufenweiser Livebetrieb

- Unit-/Service-, Datenbank-, Migrations-, Multiplayer-, GUI- und Lasttests getrennt ausführen; unverifizierte echte Clientaktionen als `NOT TESTED` markieren.
- Für jeden Build: sauberer reproduzierbarer Java-21/Paper-1.21.11 Build, Content-Validator, Testreports und Artefakt-Hash.
- Staging mit Kopie aktueller Profile/Daten (geschützte UUID-/Kontodaten), mindestens zwei getrennte Spielerprofile, Neustart-/Crash-Szenarien und Restore-Drill.
- Vor jedem Live-Schritt verifizierter frischer Backup-Archiv-Hash. Additive Plugins/Konfiguration zuerst; kontrollierter Neustart; Logs/Health, Whitelist offen, Core-JAR-Hash, Hub, Minions, Farm, Wallet und DB prüfen. Bei Abweichung sofort zum aktuellen gesicherten Stand zurück.
- Abschlussbericht mit tatsächlichem Bestand vorher/nachher, Item-/Recipe-Matrix, Migrationen, GUI, Testresultaten, Performance, Problemen und offenen Punkten erstellen.
- **Abschlussgate:** vollständige oben definierte Spielerreise und Farm-Akzeptanz erfüllt; keine offenen `FAIL`-Einträge in kritischen Transaktionen. Andernfalls Ziel nicht als fertig melden und konkrete Restpunkte samt Blockade ausweisen.

## Laufender Fortschrittsstatus

- Spezifikation: vollständig gelesen.
- Start-Audit: teilweise abgeschlossen; Source zeigt fehlende Systeme und Core-/Artefakt-Abweichung.
- Produktions-Baseline: aus dem letzten Rollback wiederhergestellt; Server war beim letzten bekannten Stand gesund, Whitelist offen und Core-JAR auf stabilem Sicherungs-Hash. Beim Fortsetzen erneut lesend verifizieren.
- Farm-Sidecar: bestehende Schutz-/Tierverteilung um eine echte konfigurierbare 2×-Alterssteigerung bei natürlichen Wachstumsereignissen ergänzt; Crop-Chunks bleiben für Wachstum ohne Spieler force-loaded. Java 21/Paper 1.21.11 gebaut, 5 Tests bestanden, 0 fehlgeschlagen; SHA-256 `7b1b1cabe198a1b7cec425e957f6281316430e1947cb8fcfe133500fef56826d`. Noch nicht live installiert; World-Bounds, config override, vorhandene Tierpositionen/-zahlen, force-loaded crop chunk set, Schutz und echte Offline-Wachstumsrate müssen vor Live-Abnahme verifiziert werden.
- Trading-Fundament begonnen: preis-/zeitpriorisierter Matcher, exakte Minor-Unit-Rechnung, Gebühren/Price-Improvement, AH-Gebots-/Anti-Sniping-State-Machine sowie Bazaar/AH-Schema. SQLite-Wallet nutzt WAL, `BEGIN IMMEDIATE`, 10s Busy-Timeout, eindeutige Idempotency Keys und atomare Core-Purse/Ledger-Änderungen. Lasttest: 1.000 parallele Abbuchungen (100 angewendet, 900 abgelehnt, Endsaldo 0) und 256 gleiche parallele Gutschriften (1 angewendet, 255 Duplikate abgewiesen); Rollback geprüft. Markt-Maker-Policy-Tests decken Spread, Kontogrenzen, Restock-Limits und 1/5/20/100-Spieler-Liquidität ab.
- Bank-Funktion live: SQLite-Konten/Ledger; Purse-Transfers und 2%-Zinsen pro vollständig vergangenem 24h-Zeitraum transaktional/idempotent; Prozent-/Alles-GUI. Bazaar/AH und die drei konfigurierten NPCs sind live, die Core-/Hub-/Spielerdaten bleiben getrennt.
- Farm-Sidecar: lokal für Java 21/Paper 1.21.11 gebaut; 5 Tests bestanden. 2×-Wachstumslogik ist im Quellstand ergänzt. Noch nicht live; Tierverteilung, tatsächliche Offline-Pflanzenregeneration, Schutz und Trampelverhinderung sind nicht live verifiziert.
- Noch nicht abgeschlossen: echte Custom-Item-/Rezept-/Collection-Rewards samt Unlocks, Recipe Book/Crafting, Minion-Upgrades, Farmabnahme, vollständige Bazaar-GUIs/Preishistorie, AH-Suche/Sortierung/Historie, Multiplayer-/GUI-Tests und Gesamt-Restore-Drill.
- Planstatus: aktiv; keine Implementierung oder Livefreigabe wird allein durch diesen Plan als erledigt gezählt.

### Neue Ergänzung: Infinite Rocket & Bazaar-Ausbau (2026-10-10)

- Teilimplementiert im lokalen `NexoraProgression` Add-on: MYTHIC Itemdefinition und per-instance ID, Paper-native Elytra boost gated on active glide + equipped Elytra, cooldown/actionbar/sound/particles/velocity caps, five EPIC components, Enchanted intermediate recipes, 50 resource collection definitions, 13 combined final/component collection gates, recipe dependency links/pages/material counts, retroactive multi-collection unlocks, and arbitrary-precision raw-material cost calculator.
- New collection counters use the existing Core SQLite profile DB through a separate serialized add-on transaction; the production NexoraCore source/JAR was left unchanged. The add-on marks and excludes placed blocks. Normal Core collection IDs are not double-counted.
- Latest add-on Maven build: 15 tests passed, 0 failed; JAR SHA-256 `1d75f9487391d1c0973c80bc8fdaa4e85e52b850c92fc6d4a5902fd0c36e1467`. The build is staged under `/tmp/nexora-rocket-build` on `marc-a2`; not installed in `/opt/nexora/minecraft/plugins`.
- Not complete: standardized-item Bazaar order/snapshot escrow and claim delivery, durable instance reservations shared with AH, Marketplace item-registry integration, hundreds-item catalog/search/favorites/history, NPC explicit classification, inventory/database crash recovery, current-price/time-to-craft balance report, Paper server smoke test, client Elytra test, anti-cheat test, and safe deployment backup. Keep this work behind those validation gates; do not claim the Rocket is live or Bazaar-tradable.
- Root cause for the deploy hold: current `NexoraTrading` accepts Bazaar commodity IDs only and serializes no physical item snapshots for Bazaar SELL escrows/claims; `NpcLiquidityService` configures each listed product as a candidate policy. Advertising an unimplemented custom product would either reject its custom stack or turn it into a vanilla stack claim. Cross-list prevention and the prior backup script's abnormal Paper stop also remain release blockers.

### Live progress update — 2026-10-10 18:20 UTC

The Rocket-specific Trading and Progression slice is now **live**; the previous deployment-hold bullets above describe the earlier state and are superseded only for this slice.

- `NexoraTrading` migration V4 is live. It adds standardized per-instance Bazaar escrow and a unique reservation shared with AH. Rocket Bazaar buy/sell limit orders, full-fill instant actions, cancellation, snapshots/claims and AH cross-list prevention are integrated into GUI flows. NPC policy is disabled for the Rocket.
- `NexoraProgression` is live with Infinite Rocket, 5 advanced components, recipe and collection gates, recipe graph GUI, boost cooldown/velocity bounds and retroactive unlock reconciliation. No production Core JAR replacement occurred.
- Clean Java 21/Paper 1.21.11 builds: Trading 37/37 tests, Progression 15/15 tests. SQLite integrity is `ok`, FK check empty, migration version 4 present, and Paper loaded both plugins and reached `Done`.
- Verified pre-update archive: `/opt/nexora/backups/nexora-pre-trading-ui-20261010T181835Z.tar.gz`, SHA-256 `c23cbc46dc2a0d46533b071731d785b2245c23d1057e0a24c17b93290cda480b`.
- Live artifact hashes: Trading `45105d53da45375a5de5478bda9680c4e2387d9f12631fb02450884383c4893a`; Progression `0084000166f4b15ff4990ebcc7abaf0334a9adb4c9fa06b993f54f956890040b`; Core unchanged `35060a8af040ab8d8cb9849f58b3f80f6b11124b35b90362dc20638a72b08ee39`.
- Bazaar catalogue now has category filtering, sign-based search and pagination per player. The inventory includes all current listed products; the 47-entry vanilla set is still far short of the requested hundreds and has no collection/custom-item registry link yet.
- Latest tested backup before this GUI rollout: `/opt/nexora/backups/nexora-pre-bazaar-catalog-20261010T182300Z.tar.gz`, SHA-256 `bd3e2a65e80b30488e27a3f67cd48b596f04af819922a380bdf80c7d0c2b6b6a`.
- Remaining release/acceptance work: actual client Elytra and GUI tests, anti-cheat validation, load/performance and multi-player economy simulations, expanding Bazaar to all valid custom/enchanted/minion products with search/favorites/history, making funded NPC offers visible through common orderbook semantics, repairing the daily backup script without stopping Paper, and the other masterplan's general collection rewards, crafting/minions and farm verification. Mark all untested game-client actions `NOT TESTED`.
- **Masterplan remains active and incomplete.** This milestone does not satisfy the overall completion gate.
