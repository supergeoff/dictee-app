# DIC-23 : suppression du boîtier Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Supprimer manuellement un ou plusieurs enregistrements du V05 et confirmer leur disparition par une liste complète, sans toucher aux autres copies.

**Architecture:** Une règle pure prépare la confirmation et ses avertissements. Un exécuteur séquentiel associe chaque suppression à sa relecture et conserve les résultats partiels. AppState utilise les protections de DIC-24 et une seule session BoxConnection pour le lot ; Compose expose les actions sans changer le parcours principal.

**Tech Stack:** Kotlin, coroutines, Jetpack Compose, tests JUnit et doubles Link existants. Aucune dépendance supplémentaire.

**Spec:** `docs/superpowers/specs/2026-11-17-dic23-suppression-boitier-design.md`, approuvée le 17 novembre 2026.

## Global Constraints

- Version cible : `0.8.0`.
- La suppression concerne uniquement les enregistrements présents sur le boîtier, jamais les fichiers du téléphone ou de Drive.
- Aucune suppression automatique après récupération n'est ajoutée.
- Le bouton principal de la fiche reste réservé au parcours existant.
- Pour chaque cible, l'application envoie `0x0d` avec exactement les 14 octets ASCII du nom, sans extension ni terminateur.
- Délai existant de cinq secondes ; relecture par `0x18 00` et `0x0a` après chaque réponse de suppression.
- Une coupure, un délai dépassé ou une liste incomplète arrête le lot, sans renvoi automatique de la suppression.
- Une cible occupée, y compris en attente, bloque la sélection entière avant émission.
- Fixtures synthétiques uniquement. Aucun push, fusion, publication ni suppression réelle sans nouvel accord explicite.

## Review Focus

- Un nom Unicode de longueur 14 ne doit pas être converti silencieusement en ASCII avec des `?` (tâche 1).
- Un acquittement positif suivi d'une liste contenant toujours la cible ne doit jamais être annoncé comme un succès (tâche 2).
- Un refus suivi d'une liste où la cible a disparu doit conserver les deux informations sans attribuer une suppression certaine à la commande (tâche 2).
- Un changement de boîtier pendant l'attente du verrou doit annuler la demande sans viser le nouveau boîtier (tâche 3).
- Une actualisation après échec ne doit pas ressusciter les entrées dont la disparition a déjà été prouvée (tâches 2 et 3).

## Préparation du plan

- [x] Retrouver la spec et sa validation explicite.
- [x] Lire DIC-23 dans Kaneo et les bases locales DIC-22 et DIC-24.
- [x] Définir les interfaces, les tests et les vérifications de chaque tâche.
- [x] Relire le plan par rapport à la spec.

## Base et fichiers

Le clone principal est encore à `52c9e8f`, avant DIC-24. Le worktree DIC-24 contient `b0e8e88` (fusion de la PR #10), avec `PhoneDeletion`, `PhoneDeletionOrchestrator`, `PhoneDeletionRequests` et `RecoveryDeletionGuard`. DIC-22 contient la preuve de suppression indépendante dans `docs/protocole-v05.md` au commit local `05884a6`. Ces références sont des observations de préparation, pas une autorisation de fusion.

Avant exécution, vérifier le HEAD intégré et utiliser un worktree dédié selon `using-git-worktrees`. Il doit contenir DIC-24 et la documentation validée de DIC-22. Si ce n'est pas le cas, arrêter avant code et faire confirmer la base à utiliser ; ne pas modifier leurs worktrees ni intégrer leurs branches implicitement. Les nombreux fichiers non suivis du clone principal sont laissés intacts.

Les chemins Kotlin ci-dessous sont relatifs à `app/src/main/java/top/supergeoff/dictee/`. Les tests sont dans `app/src/test/java/top/supergeoff/dictee/`.

- `Frame.kt` et `V05Session.kt` codent la commande, sans politique de lot.
- Nouveau `BoxDeletion.kt` prépare les cibles et les avertissements, et porte les types de résultat.
- Nouveau `BoxDeletionJob.kt` conduit le lot et transmet les listes complètes.
- Nouveau `BoxDeletionRequests.kt` consomme une confirmation une seule fois.
- `AppState.kt` raccorde l'opération à la session, aux réservations et aux listes visibles.
- Nouveau `ui/BoxDeletionDialog.kt`, `ui/RecordingScreen.kt` et `ui/RecordingsScreen.kt` exposent les actions.
- `docs/specification.md`, `AGENTS.md` et `app/build.gradle.kts` documentent le comportement et la version.

## Tâche 1 : commande individuelle V05

**Files:** Modifier `Frame.kt`, `V05Session.kt` ; tester `FrameTest.kt`, `V05SessionTest.kt`.

**Interfaces:** Produire `Cmd.DELETE = 0x0d`, `Payloads.deleteRequest(name: String): ByteArray` et `suspend fun V05Session.deleteRecord(name: String): Int`. La méthode renvoie le statut brut ; elle ne relit pas la liste et ne réessaie pas. Un délai dépassé, une déconnexion ou une réponse mal formée lève `SessionException`.

- [ ] **Step 1:** Écrire `deletePayloadIsExactAsciiName` : `deleteRequest("20260101010101")` égale les 14 octets ASCII du nom. Tester le rejet de `"20260101010101.mp3"`, d'un nom trop court, d'un nom contenant un NUL et d'un nom de 14 caractères contenant `é`.
- [ ] **Step 2:** Lancer `./gradlew testDebugUnitTest --tests '*FrameTest' --tests '*V05SessionTest'` et observer l'échec des nouveaux tests avant implémentation.
- [ ] **Step 3:** Implémenter le payload avec validation ASCII explicite et la commande via les fonctions privées `command` et `send` existantes. Conserver la journalisation hexadécimale TX/RX.
- [ ] **Step 4:** Ajouter les échanges Link synthétiques pour statuts `00` et `01`, réponse vide, absence de réponse pendant `5_000` ms et fermeture du canal. Vérifier exactement un envoi `0x0d`, sans relecture ni retry dans cette méthode.
- [ ] **Step 5:** Relancer la même commande et obtenir PASS, puis créer le commit local `feat: add V05 individual deletion command` avec uniquement les fichiers de cette tâche.

## Tâche 2 : confirmation et lot vérifié

**Files:** Créer `BoxDeletion.kt`, `BoxDeletionJob.kt`, `BoxDeletionRequests.kt` ; tester `BoxDeletionTest.kt`, `BoxDeletionJobTest.kt`, `BoxDeletionRequestsTest.kt`.

**Interfaces:**

- `BoxDeletionPlan(address: String, names: List<String>, blockedNames: Set<String>, unbackedAudioCount: Int)` capture les cibles distinctes dans l'ordre de la liste affichée.
- `BoxDeletion.plan(address: String, recordings: List<Recording>, selected: Set<String>, protectedNames: Set<String>, drive: DriveListing?, local: LocalState): BoxDeletionPlan` ne retient que `onBox`. Une copie locale MP3 vient de `phoneFiles` ; la preuve MP3 Drive reprend `DriveSync.action(..., FileKind.MP3, null, destination, entries, receipts) == SKIP`, comme DIC-24. Une vérification indisponible ne compte pas comme sauvegarde.
- `BoxDeletionRequests` expose `current`, `executing`, `open(plan)`, `cancel()`, `confirm(): BoxDeletionPlan?` et `finish()`, sur le modèle de DIC-24.
- `BoxDeletionResult(confirmed: Set<String>, refused: Map<String, Int>, unverified: Set<String>, untreated: Set<String>, error: String?)` conserve toutes les cibles. Un refus reste un refus même si la relecture constate une absence.
- `BoxDeletionJob(delete: suspend (String) -> Int, list: suspend () -> List<ListItem.Entry>, onListing: suspend (List<ListItem.Entry>) -> Unit)` expose `suspend fun run(names: List<String>): BoxDeletionResult`. `onListing` reçoit uniquement une liste complète et doit être appliqué avant la cible suivante.

- [ ] **Step 1:** Écrire les tests de plan et de confirmation : sélection mixte avec entrée hors boîtier ignorée, doublons éliminés, cible occupée marquée, absence de MP3 avertie mais non interdite, copie téléphone constatée, preuve Drive valide ou indisponible. Annuler puis confirmer renvoie `null` ; deux confirmations ne renvoient qu'une seule fois le même plan.
- [ ] **Step 2:** Lancer `./gradlew testDebugUnitTest --tests '*BoxDeletion*Test'` et constater RED, puis implémenter ces règles sans dépendance Android.
- [ ] **Step 3:** Écrire les tests de lot avec callbacks enregistrés : `00` puis absence confirme ; `00` puis présence devient non vérifié et arrête le lot ; `01` puis liste complète conserve le refus et autorise la cible suivante. Tester aussi refus puis absence : la liste est appliquée mais `confirmed` reste vide pour cette cible.
- [ ] **Step 4:** Ajouter `partialBatchStopsWithoutRetry` : A confirmé, B sans statut, C non traité donne `confirmed={A}`, `unverified={B}`, `untreated={C}` et aucun second envoi pour B. Décliner avec coupure, erreur d'écriture et liste sans marqueur de fin. Une annulation de coroutine remonte, libère les ressources au raccordement et ne réessaie jamais.
- [ ] **Step 5:** Implémenter `run` : suppression puis relecture complète après chaque statut reçu. Une exception de suppression ou de relecture arrête le lot. Ne jamais appeler `onListing` avec la liste mémorisée ni perdre les listes déjà transmises.
- [ ] **Step 6:** Relancer les tests ciblés jusqu'à PASS, puis créer le commit local `feat: plan and verify box deletion batches`.

## Tâche 3 : intégration, état et concurrence

**Files:** Modifier `AppState.kt`, `RecoveryDeletionGuard.kt` seulement si nécessaire ; tester `BoxDeletionIntegrationTest.kt`, `RecoveryDeletionGuardTest.kt` et les tests DIC-24 existants.

**Interfaces:** Ajouter à UiState/LiveState `boxDeletionRequest: BoxDeletionPlan?`, `deletingBox: Boolean`, `boxDeletionResult: BoxDeletionResult?`. Ajouter `requestBoxDeletion(names: Set<String>)`, `cancelBoxDeletion()` et `confirmBoxDeletion()`. Réutiliser `deletionProtectedNames(local)`, `repository.withTransferLock` et `RecoveryDeletionGuard.withDeletion` ; ne pas créer une deuxième réserve indépendante.

- [ ] **Step 1:** Écrire les tests d'intégration avec session et dépôts doubles : changement d'adresse entre dialogue et acquisition du verrou, cible devenue occupée ou en attente, annulation et double confirmation produisent zéro émission supplémentaire. Une seule cible occupée bloque tout le lot.
- [ ] **Step 2:** Lancer `./gradlew testDebugUnitTest --tests '*BoxDeletionIntegrationTest' --tests '*RecoveryDeletionGuardTest'` et constater l'échec des nouveaux cas.
- [ ] **Step 3:** Raccorder la demande consommable. Sous le verrou de transfert, réserver les noms avec le garde existant, relire l'état protégé puis contrôler l'adresse capturée avant toute émission. Utiliser `BoxConnection.withSession(address)` une seule fois pour le lot. Respecter l'ordre de verrous de DIC-24 ; ne jamais réacquérir un verrou non réentrant dans les callbacks.
- [ ] **Step 4:** Appliquer chaque liste complète au seul état boîtier, sans effacer `phoneFiles`, les entrées Drive, les sujets ni les métadonnées locales. Ne pas déclencher le chemin d'actualisation qui lance le traitement automatique. En échec de relecture, conserver les succès précédents et signaler que l'état restant n'est pas vérifié. Réinitialiser `deletingBox` et libérer la réservation dans `finally`.
- [ ] **Step 5:** Ajouter les tests de concurrence par barrières de coroutine : récupération déjà capturée avant réservation invalidée ; nouvelle récupération des mêmes noms refusée ; autre suppression sans chevauchement ne provoque ni exception non gérée ni interblocage ; traitement/envoi en attente bloque avant émission. Vérifier après chaque issue la libération des réservations.
- [ ] **Step 6:** Tester une liste complète A absent/B présent puis un échec sur B : A reste absent après une actualisation ultérieure complète cohérente. Avec une copie téléphone ou Drive, A reste visible ; sans aucune copie connue, A disparaît. Vérifier zéro appel de suppression téléphone/Drive et zéro création de tâche de traitement.
- [ ] **Step 7:** Lancer `./gradlew testDebugUnitTest`, obtenir PASS y compris pour DIC-24, puis créer le commit local `feat: integrate guarded box deletion`.

## Tâche 4 : actions Compose et livraison vérifiable

**Files:** Créer `ui/BoxDeletionDialog.kt` ; modifier `ui/RecordingScreen.kt`, `ui/RecordingsScreen.kt`, `docs/specification.md`, `AGENTS.md`, `app/build.gradle.kts` ; tester `BoxDeletionPresentationTest.kt` et les tests de présentation existants. Créer `docs/superpowers/specs/2026-11-17-dic23-recette.md` pour les étapes de recette, sans affirmer leur réussite.

**Interfaces:** `BoxDeletionDialog(plan: BoxDeletionPlan, executing: Boolean, onConfirm: () -> Unit, onDismiss: () -> Unit)` est partagé par fiche et liste. Les écrans consomment les méthodes et l'état de la tâche 3. Les règles de sélection et le texte de résultat testables restent hors des composables.

- [ ] **Step 1:** Charger le skill `impeccable` avant de modifier l'interface. Écrire les tests de présentation pour les nombres 1 et 2, l'avertissement avec `unbackedAudioCount=1`, le blocage de toute la sélection et le bilan partiel sans mot « supprimé » pour une cible non vérifiée. Exécuter les tests ciblés et constater RED.
- [ ] **Step 2:** Ajouter « Supprimer du boîtier » au menu ⋮ de la fiche seulement si `onBox`, et aux actions de sélection pour les seules cibles du boîtier. Garder « Supprimer du téléphone » distinct. Reprendre les composants et les jetons existants sans refaire la mise en page du parcours.
- [ ] **Step 3:** Afficher le dialogue de la spec avec le nombre de cibles, la conservation des copies téléphone/Drive et le nombre d'audios sans autre copie confirmée. L'avertissement ne désactive pas la suppression. Une cible occupée affiche la raison du blocage et interdit la confirmation. Annuler ferme seulement le dialogue.
- [ ] **Step 4:** Afficher le résultat distinguant confirmés, refusés, non vérifiés et non traités. Mettre à jour la sélection selon la liste relue ; quitter la fiche si l'enregistrement n'a plus aucune copie connue. Préserver le bouton principal et ne déclencher aucune récupération.
- [ ] **Step 5:** Mettre `versionName = "0.8.0"` et `versionCode` à la valeur intégrée précédente plus un (la base DIC-24 observée vaut 18, donc 19 si elle reste la dernière). Mettre à jour la règle de non-modification du boîtier dans `docs/specification.md` avec la seule exception manuelle DIC-23 ; ajouter les nouveaux fichiers purs à la convention test d'abord d'`AGENTS.md`.
- [ ] **Step 6:** Lancer `./gradlew testDebugUnitTest assembleDebug lint`, exiger un code de sortie 0 pour chaque tâche et inspecter `git diff --check` ainsi que le diff pour l'absence de données réelles. Créer le commit local `feat: expose manual box deletion in version 0.8.0` après PASS.
- [ ] **Step 7:** Préparer la recette : suppression unique puis multiple sur fichiers d'essai sauvegardés, confirmation du nombre, annulation sans émission, avertissement sans autre copie, disparition après relecture, conservation MP3/VTT/MD sur téléphone et Drive, retour de fiche, bilan partiel sur coupure. Noter les scénarios non exécutés. Attendre un accord explicite sur les fichiers d'essai avant toute suppression réelle.
- [ ] **Step 8:** Faire la revue finale selon `requesting-code-review` puis `finishing-a-development-branch`. La compilation ne remplace pas la recette. La release signée 0.8.0, le push, la fusion et la clôture du ticket attendent leurs accords et preuves propres. Archiver ce plan dans `docs/archive/` seulement après réalisation.

## Auto-revue

Chaque exigence de la spec est rattachée à une tâche : payload et délai (1), sauvegardes et confirmation consommable (2), relecture et lot partiel (2), protection de concurrence et conservation des copies (3), actions et retour de fiche (4), version et recette (4). Les cinq cas de Review Focus ont chacun un test nommé ou décrit. Les signatures communes sont définies une seule fois et aucune modification d'un espace de travail voisin n'est nécessaire.

Ce plan est proposé pour revue. L'approbation de la conception ne vaut pas approbation de son exécution.
