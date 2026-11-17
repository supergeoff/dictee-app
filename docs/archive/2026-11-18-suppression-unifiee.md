# Suppression unifiée et liste par emplacement : plan d'implémentation

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** remplacer les suppressions boîtier et téléphone par « Supprimer partout » et « Libérer l'espace », filtrer la liste par emplacement, réserver la barre du bas à la sélection et ajouter deux gestes de glissement sur les tuiles.

**Architecture:** une règle pure (`UnifiedDeletion`) calcule les niveaux à effacer par enregistrement. Un orchestrateur (`UnifiedDeletionOrchestrator`) exécute Drive, puis téléphone, puis boîtier, en réutilisant `PhoneDeletion.execute`, `BoxDeletionJob` et la garde `RecoveryDeletionGuard`, plus une mise à la corbeille Drive. L'interface consomme un seul plan, une seule confirmation et un message d'échec facultatif.

**Tech Stack:** Kotlin, Jetpack Compose Material 3 (`SwipeToDismissBox`, `SnackbarHost`), JUnit 4, kotlinx.coroutines test.

**Spec:** `docs/superpowers/specs/2026-11-18-suppression-unifiee-design.md`

## Global Constraints

- Textes affichés en français ; code en Kotlin. Pas de tiret cadratin dans les textes.
- Les fichiers sans dépendance Android s'écrivent test d'abord (le test échoue, puis passe).
- Scope Drive inchangé : `https://www.googleapis.com/auth/drive.file`.
- Version : `versionCode = 20`, `versionName = "0.9.0"` dans `app/build.gradle.kts`.
- Filtres dans l'ordre : `Tout`, `Boîtier`, `Téléphone`, `Drive`. « À relire » est retiré.
- Libellés : `Supprimer partout`, `Libérer l'espace`, `Envoyer dans le cloud (n)`.
- Ordre d'exécution : Drive, téléphone, boîtier. Drive = corbeille (`trashed = true`), un 404 compte comme succès.
- Aucune fenêtre de résultat après confirmation. Un snackbar seulement en cas d'échec.
- Fin : `./gradlew testDebugUnitTest assembleDebug lint` passent.

## Review Focus

1. Sélection mêlant un enregistrement occupé et des libres : la confirmation est bloquée et le dit, rien n'est effacé. Test dans Task 1 (`blocked`) et Task 4 (`blockedPlanDeletesNothing`).
2. Boîtier hors de portée pendant « Supprimer partout » : Drive et téléphone sont effacés, le boîtier est signalé en échec. Test Task 4 (`boxUnavailableStillDeletesOtherLevels`).
3. « Libérer l'espace » avec seulement le VTT confirmé sur Drive (MD absent ou sans reçu) : le téléphone est conservé, seul le boîtier est effacé. Test Task 1 (`freeSpaceKeepsPhoneWithoutConfirmedDocuments`).
4. Drive désactivé : « Supprimer partout » n'appelle jamais Drive et la confirmation l'indique. Test Task 1 (`driveDisabledSkipsDrive`) et Task 2 (`confirmationMentionsDriveSkipped`).
5. Fichier Drive déjà supprimé à la main (404) : compté comme succès, reçu retiré. Test Task 3 (`trashTreats404AsSuccess`).

---

### Task 1: Règle de ciblage `UnifiedDeletion`

**Files:**
- Create: `app/src/main/java/top/supergeoff/dictee/UnifiedDeletion.kt`
- Test: `app/src/test/java/top/supergeoff/dictee/UnifiedDeletionTest.kt`
- Modify: `AGENTS.md` (ajouter `UnifiedDeletion.kt`, `RecordingLocation.kt`, `UnifiedDeletionOrchestrator.kt` à la liste des fichiers test d'abord)

**Interfaces:**
- Consumes: `Recording`, `DriveListing`, `LocalState`, `DriveSync.hasConfirmedCopy`, `DriveFile`, `RecordName.parse`, `RecordName.legacyMp3`.
- Produces:
  - `enum class DeletionMode { EVERYWHERE, FREE_SPACE }`
  - `data class DeletionTarget(val name: String, val box: Boolean, val phone: Boolean, val driveFiles: List<DriveFile>)`
  - `data class UnifiedDeletionPlan(val mode: DeletionMode, val targets: List<DeletionTarget>, val blockedNames: Set<String>, val driveSkipped: Boolean, val boxAddress: String?, val folderUri: String?)`
  - `object UnifiedDeletion { fun plan(mode: DeletionMode, recordings: List<Recording>, selected: Set<String>, protectedNames: Set<String>, drive: DriveListing?, local: LocalState, boxAddress: String?, folderUri: String?): UnifiedDeletionPlan }`
  - `fun driveFilesOf(name: String, drive: DriveListing?): List<DriveFile>` (fichiers de `drive.entries` dont `RecordName.parse(it.name)?.name == name` ou `legacyMp3 == name`)

Règles : EVERYWHERE cible `box = onBox`, `phone = phoneFiles.isNotEmpty()`, `driveFiles = driveFilesOf` si `local.driveEnabled` sinon vide et `driveSkipped = true` quand un fichier Drive existait. FREE_SPACE : `driveOk` = VTT et MD ont `DriveSync.hasConfirmedCopy` pour la destination courante ; si `driveOk` alors `box = onBox, phone = phoneFiles.isNotEmpty()`, sinon si téléphone présent alors `box = onBox, phone = false`, sinon rien ; jamais de fichiers Drive. Une cible sans aucun niveau est retirée. `blockedNames = targets ∩ protectedNames`.

- [ ] **Step 1: Écrire les tests** `everywhereTargetsAllLevels`, `everywhereBoxOnly`, `freeSpaceBoxAndPhoneKeepsPhone` (box=true, phone=false), `freeSpaceWithConfirmedDriveFreesBoxAndPhone`, `freeSpaceKeepsPhoneWithoutConfirmedDocuments`, `freeSpaceBoxOnlyHasNoTarget` (`targets` vide), `freeSpaceNeverTouchesDrive`, `driveDisabledSkipsDrive` (`driveFiles` vide, `driveSkipped == true`), `blocked` (`blockedNames == setOf(nom occupé)`), `legacyMp3OnDriveIsTargeted`. Fixtures synthétiques via `TestDoubles.kt`.
- [ ] **Step 2:** `./gradlew testDebugUnitTest --tests '*UnifiedDeletionTest'` → FAIL (classe absente).
- [ ] **Step 3:** Implémenter `UnifiedDeletion.kt`.
- [ ] **Step 4:** même commande → PASS.
- [ ] **Step 5:** `git commit -m "feat: calculer les cibles de la suppression unifiée"`

### Task 2: Présentation et filtres par emplacement

**Files:**
- Create: `app/src/main/java/top/supergeoff/dictee/RecordingLocation.kt`
- Create: `app/src/main/java/top/supergeoff/dictee/UnifiedDeletionPresentation.kt`
- Test: `app/src/test/java/top/supergeoff/dictee/RecordingLocationTest.kt`, `app/src/test/java/top/supergeoff/dictee/UnifiedDeletionPresentationTest.kt`

**Interfaces:**
- Consumes: Task 1 (`UnifiedDeletionPlan`, `driveFilesOf`), `Recordings.toSend`, `DriveListing`.
- Produces:
  - `enum class LocationFilter(val label: String) { ALL("Tout"), BOX("Boîtier"), PHONE("Téléphone"), DRIVE("Drive"); fun matches(r: Recording, drive: DriveListing?): Boolean }` (DRIVE : `driveFilesOf(r.name, drive).isNotEmpty()`)
  - `fun cloudSendCount(recordings: List<Recording>, drive: DriveListing?, driveEnabled: Boolean): Int` : nombre d'enregistrements dont un VTT ou MD du téléphone est absent de Drive (`Recordings.missingInDrive` filtré sur VTT/MD) ; 0 si Drive désactivé ou liste nulle.
  - `fun unifiedDeletionTitle(plan: UnifiedDeletionPlan): String` → `"Supprimer partout ?"` ou `"Libérer l'espace ?"`
  - `fun unifiedDeletionBody(plan: UnifiedDeletionPlan): List<String>`
  - `fun unifiedDeletionCanConfirm(plan: UnifiedDeletionPlan, executing: Boolean): Boolean` (= `!executing && targets.isNotEmpty() && blockedNames.isEmpty()`)
  - `fun unifiedDeletionFailureText(failures: List<String>): String?` (null si vide, sinon `"Suppression incomplète : "` + premier échec + `" (voir le journal)"`)

Textes du corps, exactes : `"N enregistrement(s) sera/seront effacé(s) : X du boîtier, Y du téléphone, Z de Drive."` (niveaux à zéro omis), puis pour FREE_SPACE `"Les copies Drive sont conservées."`, si `driveSkipped` `"Drive est désactivé : ses copies ne sont pas touchées."`, si bloqué `"Enregistrements occupés : a, b. Attends la fin de leur traitement."`.

- [ ] **Step 1: Tests** `filterOrderAndLabels` (`LocationFilter.entries.map { it.label } == listOf("Tout","Boîtier","Téléphone","Drive")`), `recordingInSeveralFilters`, `driveFilterZeroWithoutListing`, `cloudCountIgnoresAudio` (MP3 absent de Drive seul → 0), `cloudCountZeroWhenDriveDisabled`, `everywhereBody`, `freeSpaceBodyMentionsDriveKept`, `confirmationMentionsDriveSkipped`, `blockedCannotConfirm`, `failureTextNullWhenNoFailure`.
- [ ] **Step 2:** `./gradlew testDebugUnitTest --tests '*RecordingLocationTest' --tests '*UnifiedDeletionPresentationTest'` → FAIL.
- [ ] **Step 3:** Implémenter les deux fichiers.
- [ ] **Step 4:** même commande → PASS.
- [ ] **Step 5:** `git commit -m "feat: filtres par emplacement et textes de la suppression unifiée"`

### Task 3: Corbeille Drive

**Files:**
- Modify: `app/src/main/java/top/supergeoff/dictee/DriveClient.kt`
- Modify: `app/src/main/java/top/supergeoff/dictee/LocalState.kt` (méthode de retrait de reçus)
- Test: `app/src/test/java/top/supergeoff/dictee/DriveClientTest.kt`, `app/src/test/java/top/supergeoff/dictee/LocalStateTest.kt`

**Interfaces:**
- Produces:
  - `fun DriveClient.trash(fileId: String)` : `PATCH https://www.googleapis.com/drive/v3/files/{id}?supportsAllDrives=true`, corps JSON `{"trashed":true}`, même chemin d'authentification que `send`. Un 404 retourne sans erreur ; tout autre échec lève comme `ok()`.
  - `fun LocalState.withoutDriveReceipts(fileIds: Set<String>): LocalState` (retire les `driveReceipts` dont `driveFileId` est dans l'ensemble).

- [ ] **Step 1: Tests** `trashSendsPatchWithTrashedTrue` (méthode, URL contenant l'id, corps), `trashTreats404AsSuccess`, `trashFailsOn403`, `withoutDriveReceiptsRemovesOnlyGivenIds`. Suivre le faux `HttpClient` existant de `DriveClientTest`.
- [ ] **Step 2:** lancer ces tests → FAIL.
- [ ] **Step 3:** Implémenter.
- [ ] **Step 4:** → PASS.
- [ ] **Step 5:** `git commit -m "feat: mettre des fichiers Drive à la corbeille"`

### Task 4: Orchestrateur et raccordement à `AppModel`

**Files:**
- Create: `app/src/main/java/top/supergeoff/dictee/UnifiedDeletionOrchestrator.kt`
- Modify: `app/src/main/java/top/supergeoff/dictee/AppState.kt`
- Test: `app/src/test/java/top/supergeoff/dictee/UnifiedDeletionOrchestratorTest.kt`

**Interfaces:**
- Consumes: Task 1, Task 2 (`unifiedDeletionFailureText`), Task 3, `PhoneDeletion.execute`, `BoxDeletionJob`, `runGuardedBoxDeletion`, `PhoneDeletionOrchestrator`, `RecoveryDeletionGuard.withDeletionSnapshot`.
- Produces:
  - `data class UnifiedDeletionOutcome(val deleted: Set<String>, val failures: List<String>)`
  - `class UnifiedDeletionOrchestrator(trashDrive: suspend (DriveFile) -> Unit, deletePhone: suspend (names: Set<String>) -> PhoneDeletionResult?, deleteBox: suspend (names: List<String>) -> BoxDeletionResult, onDriveTrashed: suspend (Set<String>) -> Unit) { suspend fun execute(plan: UnifiedDeletionPlan): UnifiedDeletionOutcome }` : Drive pour chaque fichier (échec = message `"Drive : <fichier> : <raison>"`, continue), puis téléphone sur les cibles `phone`, puis boîtier sur les cibles `box` ; un plan bloqué ne fait rien. Les exceptions autres que l'annulation deviennent des échecs.
  - Dans `UiState` : `deletionRequest: UnifiedDeletionPlan?`, `deleting: Boolean`, `notice: String?`. Retirer `phoneDeletionRequest`, `boxDeletionRequest`, `boxDeletionResult`, `deletingBox`, `deletingPhone` et leurs méthodes publiques quand plus aucun écran ne les utilise (Task 5 et 6).
  - `AppModel.requestDeletion(mode: DeletionMode, names: Set<String>)`, `confirmDeletion()`, `cancelDeletion()`, `dismissNotice()`. Demande du Bluetooth uniquement si un niveau boîtier est ciblé (`requestBoxDeletionWithPermission`). La garde : toute l'exécution passe dans `recoverySafety.withTransferLock` + `recoveryDeletionGuard.withDeletionSnapshot(names)` ; les envois admis pendant la capture rendent le plan bloqué. Après exécution : relecture téléphone, relecture Drive, `withoutDriveReceipts`, puis `notice = unifiedDeletionFailureText(failures)` et chaque échec dans le journal. Un boîtier absent ou une adresse changée produit l'échec `"Boîtier : hors de portée"` sans toucher aux autres niveaux.

- [ ] **Step 1: Tests** de l'orchestrateur avec fakes : `executesDriveThenPhoneThenBox` (ordre des appels), `blockedPlanDeletesNothing`, `boxUnavailableStillDeletesOtherLevels` (deleteBox lève → Drive et téléphone appelés, un échec `Boîtier`), `driveFailureContinues`, `freeSpaceNeverCallsDrive`, `cancellationPropagates`.
- [ ] **Step 2:** → FAIL.
- [ ] **Step 3:** Implémenter l'orchestrateur puis le raccordement dans `AppState.kt`.
- [ ] **Step 4:** `./gradlew testDebugUnitTest` → PASS (tests d'intégration existants adaptés seulement là où l'API publique retirée était appelée).
- [ ] **Step 5:** `git commit -m "feat: orchestrer la suppression unifiée"`

### Task 5: Liste, barre du bas et confirmation

**Files:**
- Create: `app/src/main/java/top/supergeoff/dictee/ui/UnifiedDeletionDialog.kt`
- Modify: `app/src/main/java/top/supergeoff/dictee/ui/RecordingsScreen.kt`
- Delete: `ui/BoxDeletionDialog.kt`, `ui/PhoneDeletionDialog.kt`, et les fonctions de présentation devenues mortes (`boxDeletionResultText`, `selectionAfter*Deletion`, avec leurs tests).

**Interfaces:**
- Consumes: Task 2, Task 4.
- Produces: `@Composable fun UnifiedDeletionDialog(plan: UnifiedDeletionPlan, executing: Boolean, onConfirm: () -> Unit, onDismiss: () -> Unit)` (réutilise `DicteeDialog`, bouton `Supprimer` destructif) ; la fenêtre se ferme à la confirmation.

Comportement : filtres `LocationFilter` avec compteurs ; hors sélection, la barre du bas n'existe que si `cloudSendCount > 0` et ne contient que `Envoyer dans le cloud (n)` → `model.send(null)` ; en sélection : Récupérer, VTT + MD, Envoyer (si Drive activé), puis une ligne `Supprimer partout` / `Libérer l'espace` (désactivés si leur plan n'a aucune cible). Un `SnackbarHost` affiche `ui.notice` puis appelle `dismissNotice()`. La sélection oublie les lignes disparues (logique `retain` existante) et quitte la sélection après confirmation.

- [ ] **Step 1:** Implémenter.
- [ ] **Step 2:** `./gradlew testDebugUnitTest assembleDebug` → PASS.
- [ ] **Step 3:** `git commit -m "feat: liste par emplacement et actions de suppression en sélection"`

### Task 6: Gestes de glissement

**Files:**
- Modify: `app/src/main/java/top/supergeoff/dictee/LocalState.kt` (`beginAutomaticRecovery(name, folderUri, id, force: Boolean = false)` : `force` crée l'intention même si `autoProcessAfterRecovery` est faux)
- Create: `app/src/main/java/top/supergeoff/dictee/SwipeProcessing.kt`
- Modify: `AppState.kt` (`recover(names, processAfter: Boolean = false)`, `processOne(name: String)`), `ui/RecordingsScreen.kt`
- Test: `LocalStateTest.kt`, `app/src/test/java/top/supergeoff/dictee/SwipeProcessingTest.kt`

**Interfaces:**
- Produces: `enum class SwipeProcessing { RECOVER, TRANSCRIBE, DONE, BUSY }` et `fun swipeProcessing(r: Recording, busy: Boolean, hasVtt: Boolean, hasMd: Boolean): SwipeProcessing` (occupé → BUSY ; MP3 absent du téléphone et sur boîtier → RECOVER ; VTT ou MD manquant → TRANSCRIBE ; sinon DONE). `processOne` : RECOVER → `recover(listOf(name), processAfter = true)`, TRANSCRIBE → `transcribe(setOf(name))`, DONE → `notice = "Déjà traité"`, BUSY → `notice = "Traitement en cours"`.
- UI : chaque `RecordingRow` hors sélection est dans un `SwipeToDismissBox` ; `StartToEnd` → `model.requestDeletion(EVERYWHERE, setOf(name))`, `EndToStart` → `model.processOne(name)` ; `confirmValueChange` renvoie `false` pour que la tuile revienne toujours en place. Fond rouge (`colors.destructive`) avec « Supprimer » à gauche, fond `colors.primary` avec « Traiter » à droite. Désactivé en sélection.

- [ ] **Step 1: Tests** `forcedRecoveryCreatesIntentWhenAutoOff`, `swipeBusy`, `swipeRecoverWhenOnlyOnBox`, `swipeTranscribeWhenMdMissing`, `swipeDoneWhenComplete`.
- [ ] **Step 2:** → FAIL.
- [ ] **Step 3:** Implémenter.
- [ ] **Step 4:** `./gradlew testDebugUnitTest assembleDebug` → PASS.
- [ ] **Step 5:** `git commit -m "feat: glisser une tuile pour supprimer ou traiter"`

### Task 7: Fiche, documentation et version

**Files:**
- Modify: `ui/RecordingScreen.kt` (menu ⋮ : `Supprimer partout` et `Libérer l'espace` à la place des deux suppressions ; `UnifiedDeletionDialog` ; retour à la liste si l'enregistrement disparaît, logique existante)
- Modify: `docs/specification.md` (sections suppression, liste, barre du bas, gestes)
- Modify: `app/build.gradle.kts` (0.9.0 / 20)

- [ ] **Step 1:** Implémenter.
- [ ] **Step 2:** `./gradlew testDebugUnitTest assembleDebug lint` → BUILD SUCCESSFUL.
- [ ] **Step 3:** `git commit -m "feat: suppression unifiée dans la fiche, version 0.9.0"`
