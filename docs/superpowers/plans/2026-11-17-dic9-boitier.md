# DIC-9 : état et réglages du boîtier Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Afficher la batterie et le stockage du MECHEN V05 et appliquer avec relecture ses réglages documentés dans Dictée 0.9.0.

**Architecture:** `V05Session` transforme la réponse `0x05` de la poignée de main en `BoxStatus` et expose les trois écritures suivies d’une relecture. `BoxConnection` publie chaque lecture avec l’adresse du boîtier ; `AppModel` la conserve dans `state.json` v8 et n’autorise une écriture que sur une lecture fraîche. Compose affiche cet état sans mise à jour optimiste.

**Tech Stack:** Kotlin, coroutines et Flow, Jetpack Compose Material 3, `org.json`, JUnit 4, Gradle Android.

**Spec:** `docs/superpowers/specs/2026-11-17-dic9-boitier-design.md`

## Global Constraints

- Partir de `origin/main` à `f43a69f`, version 0.8.0, dans le worktree `.worktrees/dic-9` ; ne pas modifier les autres worktrees ni les fichiers non suivis du clone principal.
- N’utiliser que `0x05`, `0x16`, `0x17` et `0x1a` avec les valeurs documentées dans `docs/protocole-v05.md`. Le support USB, le gain, la réinitialisation et le firmware sont exclus.
- Les textes affichés sont en français. Aucune écriture automatique au démarrage, aucune nouvelle dépendance et aucun renvoi après coupure.
- Le code sans dépendance Android est écrit test d’abord : le test échoue avant le code qui le fait passer.
- Les commandes Gradle s’exécutent avec `mise exec -- ./gradlew …` depuis la racine du worktree.
- Chaque tâche se termine par un commit local. Aucun push, fusion, publication ou changement réel du boîtier sans accord explicite séparé.

## Review Focus

- Une lecture retardée venant de l’ancienne adresse ne doit jamais remplacer l’état du nouveau boîtier ; le test `une lecture d ancienne adresse est ignoree` de la tâche 4 le vérifie.
- Une coupure après l’envoi d’un réglage ne doit produire ni nouvel envoi ni intention durable ; les tests de la tâche 2 et `une coupure ne conserve aucune intention a rejouer` de la tâche 4 le vérifient.
- Un acquittement accepté suivi d’une valeur différente doit afficher la valeur relue, pas le choix demandé ; le test `une relecture divergente publie la valeur reelle` de la tâche 4 le vérifie.
- Une valeur inconnue ne doit ni masquer batterie et stockage ni apparaître comme un choix actif ; les tests des tâches 1 et 5 le vérifient.
- Un espace libre supérieur à la capacité ne doit pas produire un stockage négatif ; le test `un stockage incoherent est inconnu` de la tâche 1 le vérifie.
- Deux touchers rapides ne doivent émettre qu’une commande ; le test `deux demandes simultanees n emettent qu une commande` de la tâche 4 le vérifie.

---

### Task 1: Modèle et présentation de l’état du boîtier

**Files:**
- Create: `app/src/main/java/top/supergeoff/dictee/BoxStatus.kt`
- Create: `app/src/test/java/top/supergeoff/dictee/BoxStatusTest.kt`
- Modify: `AGENTS.md`

**Interfaces:**
- Produces: `data class BoxStatus(val batteryPercent: Int, val totalMb: Long, val freeMb: Long, val noise: Int, val segmentDuration: Int, val backlight: Int)`.
- Produces: `fun BoxStatus.Companion.parse(payload: ByteArray): BoxStatus`, qui exige 52 octets et lit les index documentés sans rejeter une valeur de réglage inconnue.
- Produces: `enum class NoiseReduction(val raw: Int, val label: String)`, `enum class SegmentDuration(val raw: Int, val minutes: Int, val label: String)` et `fun boxSettingLabel(raw: Int, labels: Map<Int, String>): String`, avec « Inconnu » hors valeurs documentées.
- Produces: `fun BoxStatus.storageLabel(): String`, sous la forme « 23 Mo utilisés sur 59 934 Mo », ou « Stockage inconnu » si `freeMb > totalMb`.

- [ ] **Step 1: Write the failing parsing and presentation tests**

Créer un payload synthétique de 52 octets avec total 59 934, libre 59 911, batterie 71, bruit `09`, durée `02`, rétro-éclairage `01`. Vérifier tous les champs, les quatre bruits (`00`, `05`, `09`, `0f`), les quatre durées (`00` à `03`), les deux rétro-éclairages, les entiers dont le bit de poids fort vaut 1, les libellés inconnus, le rejet des longueurs 51 et 53, puis le stockage incohérent.

- [ ] **Step 2: Run tests to verify they fail**

Run: `mise exec -- ./gradlew testDebugUnitTest --tests top.supergeoff.dictee.BoxStatusTest`
Expected: FAIL parce que `BoxStatus` n’existe pas.

- [ ] **Step 3: Implement the model and labels**

Créer les interfaces ci-dessus avec les index 0, 4, 9, 39, 41 et 42. Conserver les octets de réglage comme entiers non signés pour pouvoir persister et présenter une valeur inconnue. Ajouter `BoxStatus.kt` à la liste des fichiers purs développés test d’abord dans `AGENTS.md`.

- [ ] **Step 4: Run tests to verify they pass**

Run: `mise exec -- ./gradlew testDebugUnitTest --tests top.supergeoff.dictee.BoxStatusTest`
Expected: PASS, y compris `un stockage incoherent est inconnu`.

- [ ] **Step 5: Commit**

```bash
git add AGENTS.md app/src/main/java/top/supergeoff/dictee/BoxStatus.kt app/src/test/java/top/supergeoff/dictee/BoxStatusTest.kt
git commit -m "feat: décoder l’état du boîtier"
```

### Task 2: Lecture et écriture dans la session V05

**Files:**
- Modify: `app/src/main/java/top/supergeoff/dictee/Frame.kt`
- Modify: `app/src/main/java/top/supergeoff/dictee/V05Session.kt`
- Modify: `app/src/test/java/top/supergeoff/dictee/V05SessionTest.kt`

**Interfaces:**
- Consumes: `BoxStatus` et les énumérations de la tâche 1.
- Produces: constantes `Cmd.SEGMENT_DURATION = 0x16`, `Cmd.NOISE_REDUCTION = 0x17`, `Cmd.BACKLIGHT = 0x1a`.
- Produces: `sealed interface BoxSetting` avec `Noise(value: NoiseReduction)`, `Duration(value: SegmentDuration)`, `Backlight(enabled: Boolean)`, chaque variante exposant `command: Int`, `raw: Int` et `fun isApplied(status: BoxStatus): Boolean`.
- Produces: `data class BoxSettingResult(val accepted: Boolean, val status: BoxStatus)` avec `fun isApplied(setting: BoxSetting): Boolean`.
- Produces: `suspend fun V05Session.handshake(): BoxStatus`, `suspend fun V05Session.readStatus(): BoxStatus` et `suspend fun V05Session.writeSetting(setting: BoxSetting): BoxSettingResult`. L’écriture attend l’acquittement puis envoie `0x05`, y compris après un statut refusé.

- [ ] **Step 1: Write the failing session tests**

Vérifier que `handshake` renvoie l’état de sa trame `0x05`. Pour chaque variante, vérifier la commande et le payload d’un octet. Vérifier `accepted=true` avec relecture concordante, `accepted=false` avec relecture quand même effectuée, relecture divergente détectée par `isApplied`, délai dépassé et coupure. Vérifier dans les deux derniers cas l’absence d’un second envoi de la commande de réglage.

- [ ] **Step 2: Run tests to verify they fail**

Run: `mise exec -- ./gradlew testDebugUnitTest --tests top.supergeoff.dictee.V05SessionTest`
Expected: FAIL sur les nouvelles méthodes et constantes absentes.

- [ ] **Step 3: Implement session reads and writes**

Faire passer la réponse `0x05` de `handshake` par `readStatus`. Construire la trame avec `Frame.build` et un seul octet `raw`. Après `command(setting.command, byteArrayOf(raw))`, appeler `readStatus` une fois. Les exceptions existantes remontent sans relance ni état d’intention.

- [ ] **Step 4: Run tests to verify they pass**

Run: `mise exec -- ./gradlew testDebugUnitTest --tests top.supergeoff.dictee.V05SessionTest --tests top.supergeoff.dictee.FrameTest`
Expected: PASS, anciens et nouveaux tests.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/top/supergeoff/dictee/Frame.kt app/src/main/java/top/supergeoff/dictee/V05Session.kt app/src/test/java/top/supergeoff/dictee/V05SessionTest.kt
git commit -m "feat: relire les réglages écrits sur le boîtier"
```

### Task 3: Persistance de la dernière lecture

**Files:**
- Modify: `app/src/main/java/top/supergeoff/dictee/LocalState.kt`
- Modify: `app/src/test/java/top/supergeoff/dictee/LocalStateTest.kt`

**Interfaces:**
- Consumes: `BoxStatus` de la tâche 1.
- Produces: `LocalState.boxStatus: BoxStatus? = null`.
- Produces: champ JSON `boxStatus`, version d’état `8`, avec les six entiers bruts. Les versions 1 à 7 chargent `boxStatus=null` ; une version 8 sans champ valide échoue sans modifier le fichier.

- [ ] **Step 1: Write the failing persistence tests**

Faire un aller-retour complet avec une valeur inconnue de bruit pour prouver la conservation brute. Vérifier `version=8`, la migration d’un état v7 sans `boxStatus`, puis les formes invalides du champ v8 : absent, `null`, objet incomplet, valeur numérique hors plage d’un octet pour un réglage. Vérifier que le fichier reste intact après échec.

- [ ] **Step 2: Run tests to verify they fail**

Run: `mise exec -- ./gradlew testDebugUnitTest --tests top.supergeoff.dictee.LocalStateTest`
Expected: FAIL sur le champ et la version 8 absents.

- [ ] **Step 3: Implement version 8**

Ajouter la propriété, sa lecture conditionnée par `version >= 8` et sa sérialisation. Valider batterie entre 0 et 100, compteurs entre 0 et `0xffffffff`, et réglages entre 0 et 255. Ne pas convertir les énumérations en noms, afin de préserver l’inconnu.

- [ ] **Step 4: Run tests to verify they pass**

Run: `mise exec -- ./gradlew testDebugUnitTest --tests top.supergeoff.dictee.LocalStateTest`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/top/supergeoff/dictee/LocalState.kt app/src/test/java/top/supergeoff/dictee/LocalStateTest.kt
git commit -m "feat: conserver la dernière lecture du boîtier"
```

### Task 4: Publication et orchestration dans l’application

**Files:**
- Create: `app/src/main/java/top/supergeoff/dictee/BoxSettingsFlow.kt`
- Create: `app/src/test/java/top/supergeoff/dictee/BoxSettingsFlowTest.kt`
- Modify: `app/src/main/java/top/supergeoff/dictee/BoxConnection.kt`
- Modify: `app/src/main/java/top/supergeoff/dictee/AppState.kt`
- Modify: `app/src/test/java/top/supergeoff/dictee/AppStateIntegrationTest.kt`

**Interfaces:**
- Produces: `data class BoxReading(val address: String, val status: BoxStatus)`.
- Produces: `BoxConnection.readings: SharedFlow<BoxReading>` avec `replay=1`, alimenté après chaque `handshake` et chaque `writeSetting` réussi ou refusé avec relecture valide ; `suspend fun BoxConnection.writeSetting(address: String, setting: BoxSetting): BoxSettingResult`.
- Produces: `suspend fun recordBoxReading(repository: LocalStateRepository, reading: BoxReading): LocalState`, qui n’écrit que si `deviceAddress == reading.address` et propage un échec de sauvegarde.
- Produces: `fun isBoxStatusFresh(associatedAddress: String?, readingAddress: String?, link: LinkStatus): Boolean` et `UiState.boxStatusFresh: Boolean`, vraie seulement si une lecture de la liaison courante correspond au boîtier associé et `link == CONNECTED`.
- Produces: `class BoxSettingAdmission` avec `fun tryStart(): Boolean` et `fun finish()`, protégés par un `AtomicBoolean`.
- Produces: `fun boxSettingMessage(setting: BoxSetting, result: BoxSettingResult): String`, qui distingue succès, refus et divergence.
- Produces: `fun AppModel.setBoxSetting(setting: BoxSetting)`, sérialisé sous `recoverySafety.withTransferLock`, sans file durable de demandes.

- [ ] **Step 1: Write the failing orchestration tests**

Dans `BoxSettingsFlowTest`, tester l’enregistrement adressé, `une lecture d ancienne adresse est ignoree`, la fraîcheur perdue hors ligne ou sur nouvelle adresse, et les libellés de résultat accepté, refusé et divergent. Ajouter `une relecture divergente publie la valeur reelle` sur `boxSettingMessage`, `une coupure ne conserve aucune intention a rejouer` sur l’état local après exception de session et `deux demandes simultanees n emettent qu une commande` sur deux coroutines utilisant `BoxSettingAdmission`. Conserver dans `AppStateIntegrationTest` le contrôle que l’état durable ne contient aucune nouvelle tâche ou intention après échec.

- [ ] **Step 2: Run tests to verify they fail**

Run: `mise exec -- ./gradlew testDebugUnitTest --tests top.supergeoff.dictee.BoxSettingsFlowTest --tests top.supergeoff.dictee.AppStateIntegrationTest`
Expected: FAIL sur les interfaces absentes.

- [ ] **Step 3: Publish readings and implement admission**

Ajouter le flux et la méthode à `BoxConnection`. Dans `AppModel`, collecter les lectures et appeler `recordBoxReading`. Effacer `boxStatus` et `Live.boxStatusAddress` lors d’une association. Ajouter `boxStatusAddress` et `settingBox` à `Live`, ainsi que `settingBox` et `boxStatusFresh` à `UiState` ; désactiver toute seconde demande avec `BoxSettingAdmission`, et refuser l’écriture si récupération, suppression, recherche, rafraîchissement ou absence de lecture fraîche. Revalider l’adresse et les occupations sous le verrou de transfert.

- [ ] **Step 4: Publish only reread outcomes and errors**

Après succès concordant, afficher « Réglage appliqué et relu. » ; après refus, « Le boîtier a refusé ce réglage. » ; après divergence, « Réglage non appliqué. La valeur relue est affichée. ». Après exception, conserver l’ancienne valeur et afficher « Réglage non vérifié. Reconnecte le boîtier avant de réessayer. ». Ne créer aucune tâche ou intention de rejeu.

- [ ] **Step 5: Run integration and regression tests**

Run: `mise exec -- ./gradlew testDebugUnitTest --tests top.supergeoff.dictee.BoxSettingsFlowTest --tests top.supergeoff.dictee.AppStateIntegrationTest --tests top.supergeoff.dictee.V05SessionTest`
Expected: PASS, avec un seul envoi pour deux demandes simultanées.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/top/supergeoff/dictee/BoxSettingsFlow.kt app/src/main/java/top/supergeoff/dictee/BoxConnection.kt app/src/main/java/top/supergeoff/dictee/AppState.kt app/src/test/java/top/supergeoff/dictee/BoxSettingsFlowTest.kt app/src/test/java/top/supergeoff/dictee/AppStateIntegrationTest.kt
git commit -m "feat: orchestrer les réglages confirmés du boîtier"
```

### Task 5: Section Boîtier dans Compose

**Files:**
- Create: `app/src/main/java/top/supergeoff/dictee/BoxSettingsPresentation.kt`
- Create: `app/src/test/java/top/supergeoff/dictee/BoxSettingsPresentationTest.kt`
- Modify: `app/src/main/java/top/supergeoff/dictee/ui/SettingsScreen.kt`
- Modify: `AGENTS.md`

**Interfaces:**
- Consumes: `UiState.local.boxStatus`, `UiState.boxStatusFresh`, `UiState.settingBox`, `AppModel.setBoxSetting` et les types des tâches 1 et 2.
- Produces: `fun boxSettingsEditable(link: LinkStatus, fresh: Boolean, busy: Boolean, hasStatus: Boolean): Boolean`.
- Produces: les libellés « Non lu », « Dernières valeurs lues », « Inconnu », et la projection de chaque valeur brute vers son choix ou vers aucun choix actif.

- [ ] **Step 1: Write the failing presentation tests**

Tester l’activation seulement en ligne, fraîche, non occupée et avec état ; l’absence de lecture ; les valeurs inconnues sans choix actif ; les huit choix bruit/durée et les deux choix rétro-éclairage. Vérifier que l’état hors ligne conserve les valeurs affichées mais interdit l’édition.

- [ ] **Step 2: Run tests to verify they fail**

Run: `mise exec -- ./gradlew testDebugUnitTest --tests top.supergeoff.dictee.BoxSettingsPresentationTest`
Expected: FAIL parce que la projection n’existe pas.

- [ ] **Step 3: Implement pure presentation rules**

Créer les règles pures sans dépendance Android et référencer `BoxSettingsPresentation.kt` dans `AGENTS.md`.

- [ ] **Step 4: Extend the Boîtier section**

Dans `BoxSection`, conserver l’association puis afficher batterie, stockage et les trois sélecteurs. Les réglages proposent les libellés de la conception et appellent `setBoxSetting` dès qu’un nouveau choix est touché. Aucun bouton « Enregistrer » ni mise à jour optimiste. Hors ligne ou sans fraîcheur, afficher « Dernières valeurs lues » et griser les choix. Sans lecture, afficher « Non lu ». Griser aussi « Changer » pendant une écriture.

- [ ] **Step 5: Run tests and compilation**

Run: `mise exec -- ./gradlew testDebugUnitTest --tests top.supergeoff.dictee.BoxSettingsPresentationTest && mise exec -- ./gradlew assembleDebug`
Expected: PASS et BUILD SUCCESSFUL.

- [ ] **Step 6: Commit**

```bash
git add AGENTS.md app/src/main/java/top/supergeoff/dictee/BoxSettingsPresentation.kt app/src/main/java/top/supergeoff/dictee/ui/SettingsScreen.kt app/src/test/java/top/supergeoff/dictee/BoxSettingsPresentationTest.kt
git commit -m "feat: afficher et régler le boîtier depuis les réglages"
```

### Task 6: Spécification, version et vérification de livraison

**Files:**
- Modify: `docs/specification.md`
- Modify: `app/build.gradle.kts`
- Modify: `docs/superpowers/specs/2026-11-17-dic9-boitier-design.md` seulement si un écart approuvé est survenu

**Interfaces:**
- Consumes: toutes les tâches précédentes.
- Produces: Dictée `versionName="0.9.0"`, `versionCode=20`, avec la section Boîtier documentée.

- [ ] **Step 1: Document the delivered behavior**

Dans `docs/specification.md`, décrire la lecture `0x05` à chaque connexion, `state.json` v8, les valeurs hors ligne, les choix et commandes, la relecture obligatoire, les états grisés et l’absence de rejeu après coupure. Exclure explicitement USB et gain. Mettre la version à 0.9.0 et le code à 20.

- [ ] **Step 2: Run all automated checks**

Run: `mise exec -- ./gradlew testDebugUnitTest assembleDebug lint assembleRelease`
Expected: BUILD SUCCESSFUL pour chaque tâche. Noter que la release reste non signée si les variables `DICTEE_*` ne sont pas définies ; ne pas demander ni afficher leurs secrets.

- [ ] **Step 3: Inspect the final diff**

Run: `git diff --check origin/main...HEAD && git status --short && git diff --stat origin/main...HEAD`
Expected: aucune erreur d’espaces, aucun fichier étranger au chantier, aucun secret ni donnée réelle.

- [ ] **Step 4: Commit documentation and version**

```bash
git add docs/specification.md app/build.gradle.kts docs/superpowers/specs/2026-11-17-dic9-boitier-design.md
git commit -m "docs: préparer la livraison 0.9.0"
```

Si seuls les deux fichiers de livraison changent, ne pas ajouter le chemin de conception à la commande et ne jamais créer de commit vide.

- [ ] **Step 5: Define hardware acceptance without performing it**

Présenter la recette suivante et demander un accord séparé avant toute modification réelle : comparer batterie et stockage avec DOWAY, régler et relire chaque niveau de bruit, chaque durée et les deux états du rétro-éclairage, vérifier les valeurs après reconnexion, puis mettre le boîtier hors de portée pour contrôler les choix grisés et les dernières valeurs. Ne pas clôturer DIC-9 avant le résultat de cette recette et les accords de publication nécessaires.
