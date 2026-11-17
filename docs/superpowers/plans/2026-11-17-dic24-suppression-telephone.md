# DIC-24 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Supprimer explicitement les fichiers locaux d'enregistrements sans perdre la visibilité des copies restantes.

**Architecture:** Une logique Kotlin pure prépare les cibles et avertissements, puis un exécuteur supprime les fichiers via PhoneStore. AppModel protège les opérations concurrentes et publie une relecture réelle ; les écrans partagent un dialogue de confirmation.

**Tech Stack:** Kotlin, coroutines, Jetpack Compose, Storage Access Framework, JUnit, tests Android existants.

**Spec:** `docs/superpowers/specs/2026-11-17-dic24-suppression-telephone-design.md`

## Global Constraints

- Version visée : 0.7.0 ; aucune nouvelle dépendance.
- Seuls MP3, VTT et MD du dossier choisi sont supprimés, jamais Drive ni le boîtier.
- Confirmation explicite pour chaque opération ; annulation sans effet.
- Les avertissements de sauvegarde ne bloquent pas ; les opérations actives ou en attente protègent leurs fichiers.
- Test d'abord pour toute logique sans dépendance Android.
- Aucun push, aucune fusion, aucune publication ni suppression réelle sans accord explicite.
- Exécution dans un worktree isolé après approbation du plan. Ne pas toucher aux fichiers non suivis préexistants.

## Review Focus

- Drive contient seulement VTT/MD : conserver la ligne et afficher les étapes distantes.
- Le dossier change pendant le dialogue : refuser la demande sans supprimer dans aucun dossier.
- Une tâche démarre entre ouverture et confirmation : revalider sous protection et refuser la sélection entière.
- Le fournisseur supprime un fichier puis refuse le suivant : publier le résultat partiel réel.
- Un reçu concerne une autre destination ou une ancienne version : ne pas promettre une sauvegarde courante.

## Carte des fichiers

Créer `PhoneDeletion.kt` pour la politique et l'exécution indépendantes d'Android, et `ui/PhoneDeletionDialog.kt` pour la confirmation partagée. Étendre `PhoneStore.kt` et `PhoneFolder.kt` pour la suppression SAF. Modifier `RecordingState.kt`, `RecordingPipeline.kt` et `DriveSync.kt` pour les copies restantes ; étendre aussi `DriveSyncTest.kt`. Intégrer dans `AppState.kt`, `ui/RecordingScreen.kt` et `ui/RecordingsScreen.kt`. Ajouter les tests correspondants aux répertoires de tests existants, puis mettre à jour `app/build.gradle.kts` et `docs/specification.md`.

### Task 1: Préparer et exécuter une suppression locale

**Files:** créer `app/src/main/java/top/supergeoff/dictee/PhoneDeletion.kt` et `app/src/test/java/top/supergeoff/dictee/PhoneDeletionTest.kt` ; modifier `PhoneStore.kt`, `PhoneFolder.kt`, `TestDoubles.kt` ; étendre `app/src/androidTest/java/top/supergeoff/dictee/PhoneFolderGuardTest.kt` et son `FakeDocumentsProvider.kt`.

**Interfaces:** `PhoneStore.delete(fileName: String): Unit` est idempotente si le fichier manque et lève PhoneStoreException en cas d'accès refusé. Définir `PhoneDeletionPlan(folderUri: String, recordings: List<Recording>, blockedNames: Set<String>, documentsUnverified: Boolean, audioAtRisk: Boolean)` et `PhoneDeletionResult(deleted: Set<String>, failures: Map<String, String>)`. `PhoneDeletion.plan(folderUri: String, recordings: List<Recording>, selected: Set<String>, protectedNames: Set<String>, drive: DriveListing?, local: LocalState, hashes: Map<String, String>): PhoneDeletionPlan` prépare les fichiers présents. `PhoneDeletion.execute(plan: PhoneDeletionPlan, store: PhoneStore): PhoneDeletionResult` ne traite jamais un plan bloqué.

- [x] Écrire les tests en échec : sélection de deux enregistrements sans doublon, omission des éléments sans fichiers locaux, blocage total si une cible est protégée, Drive inconnu avec documentsUnverified=true, MP3 uniquement local avec audioAtRisk=true, MP3 sur boîtier avec audioAtRisk=false. Ajouter les cas reçu périmé et mauvaise destination : documentsUnverified=true.
- [x] Lancer `./gradlew testDebugUnitTest --tests '*PhoneDeletionTest'` et constater les échecs attendus.
- [x] Implémenter les interfaces ; réutiliser les règles de reçus de DriveSync plutôt que les noms seuls. L'exécuteur continue après une erreur par fichier, enregistre chaque résultat et laisse remonter une annulation de coroutine.
- [x] Tester le faux store : un succès suivi d'un refus donne exactement un nom dans deleted et un nom dans failures ; un fichier absent est sans erreur ; aucun nom hors du plan n'est passé à delete.
- [x] Ajouter les tests Android du fournisseur : suppression réelle du document ciblé, absent sans erreur, refus et perte de permission sans faux succès. Implémenter `PhoneFolder.delete` avec DocumentsContract.deleteDocument et les protections d'erreur existantes. Adapter chaque implémentation de PhoneStore, y compris les doubles.
- [x] Relancer les tests unitaires et `./gradlew assembleDebug assembleDebugAndroidTest`. Exécuter PhoneFolderGuardTest sur appareil disponible ; sinon consigner le non-vérifié dans le compte rendu, pas dans un fichier d'état.
- [x] Créer un commit local limité aux fichiers de cette tâche.

### Task 2: Conserver et présenter les copies restantes

**Files:** modifier `RecordingState.kt`, `RecordingPipeline.kt`, `RecordingStateTest.kt`, `RecordingPipelineTest.kt`, `RecordingListPresentationTest.kt`, `DriveSync.kt` et `DriveSyncTest.kt` dans les chemins main/test du paquet existant.

**Interfaces:** conserver `Recordings.merge(...)` et `RecordingPipeline.calculate(input: PipelineInput): PipelinePresentation`. Étendre la fusion aux noms reconnus de VTT et MD distants, avec les règles de type déjà utilisées pour les noms préfixés. Aucun téléchargement distant n'est ajouté.

- [x] Écrire les tests en échec : Drive avec VTT et MD sans MP3 conserve une Recording ; téléphone et boîtier vides avec Drive vide donnent une liste vide ; fichier local prioritaire sur distant ; fichier local absent et copie confirmée donnent label « Sur Drive » ; absence de copie donne « Absent » après suppression ; audio boîtier seul conserve l'action RECOVER.
- [x] Lancer `./gradlew testDebugUnitTest --tests '*RecordingStateTest' --tests '*RecordingPipelineTest' --tests '*RecordingListPresentationTest'` et observer les échecs.
- [x] Adapter la fusion et la présentation. Garder les actions de production quand l'audio local existe ; ne pas produire de documents ni lancer de récupération à cause d'une suppression. Adapter `DriveSync.isComplete` pour conserver le calcul Drive selon le choix DIC-21 après disparition des fichiers locaux : sans empreinte locale, la preuve reste conservatrice et liée aux reçus et à la destination courante.
- [x] Ajouter dans `DriveSyncTest.kt` les cas sans empreinte locale avec reçu et destination courante, reçu absent ou destination différente ; ajouter les cas DOCUMENTS_ONLY, ALL_FILES incomplet et destination différente ; vérifier que la ligne distante existe sans déclarer à tort la dernière version sauvegardée.
- [x] Relancer les tests ciblés puis toute la suite unitaire et créer un commit local.

### Task 3: Intégrer la confirmation et protéger les travaux

**Files:** modifier `AppState.kt`, `ui/RecordingScreen.kt`, `ui/RecordingsScreen.kt` ; créer `ui/PhoneDeletionDialog.kt`, `PhoneDeletionIntegrationTest.kt` dans les tests unitaires et `PhoneDeletionDialogUiTest.kt` dans les tests Android. Étendre les tests d'intégration existants si leurs doubles sont nécessaires.

**Interfaces:** ajouter à AppModel `requestPhoneDeletion(names: Set<String>)`, `cancelPhoneDeletion()` et `confirmPhoneDeletion()` ; exposer la demande nullable dans UiState, sans conserver de callback périmé. La demande capture le dossier et les noms exacts ; la confirmation est consommée une seule fois.

Avancement partiel : composant de dialogue partagé et tests Android compilés, non raccordés aux écrans ni au modèle. Aucun test sur appareil ; toutes les étapes ci-dessous restent à terminer et valider.

- [ ] Écrire les tests en échec : annulation sans appel store ; double confirmation avec une seule exécution ; dossier changé sans suppression ; cible devenue active sans suppression pour toute la sélection ; une erreur partielle entraîne une relecture et un message qui nomme le fichier refusé.
- [ ] Lancer les tests ciblés et constater les échecs avant implémentation.
- [ ] Implémenter la demande et sa consommation. Revalider sous les protections existantes (RecoverySafety.withTransferLock et phoneMutex, sans inversion d'ordre) le dossier, les tâches non terminales, les demandes automatiques et les transferts. Réserver les cibles pendant la suppression ; toutes les entrées de récupération, programmation, synthèse et envoi doivent respecter cette réservation. Tester une tentative concurrente de démarrage : elle ne doit pas recréer les fichiers ni partir pendant la suppression.
- [ ] Après exécution, relire le dossier, invalider les caches locaux et actualiser phoneRevision. Garder les reçus Drive et le sujet, éliminer les erreurs devenues sans objet. Une relecture impossible publie une erreur, pas une liste vide présentée comme certaine. Libérer les réservations même en cas d'erreur.
- [ ] Ajouter « Supprimer du téléphone » au menu ⋮ et à la sélection, sans remplacer l'action principale ni les trois boutons habituels hors sélection. Partager le dialogue : titre « Supprimer du téléphone ? », nombre de cibles, avertissements de documents et d'audio, boutons « Annuler » et « Supprimer ». Désactiver pendant exécution ; expliquer les cibles protégées.
- [ ] Ajouter les tests UI : fiche et sélection ouvrent le même dialogue, bon nombre, avertissements visibles, annuler sans effet, confirmer une seule fois. Arrêter la lecture audio avant suppression et revenir à la liste si la fiche n'a plus de Recording. Vérifier aussi la sélection après suppression partielle.
- [ ] Relancer les tests unitaires et compiler l'APK et les tests Android. Exécuter les tests UI sur appareil disponible, puis créer un commit local.

### Task 4: Vérifier et préparer la livraison

**Files:** modifier `app/build.gradle.kts`, `docs/specification.md` et `AGENTS.md` (ajouter PhoneDeletion.kt aux fichiers test d'abord).

**Interfaces:** versionName devient `0.7.0` et versionCode augmente par rapport à la valeur de la branche d'exécution.

- [ ] Mettre à jour la spécification produit avec les actions, avertissements et étapes distantes ; vérifier que le périmètre exclut toujours les suppressions Drive et boîtier.
- [ ] Lancer `./gradlew testDebugUnitTest assembleDebug lint assembleDebugAndroidTest` et lire les résultats. Toute erreur est traitée avant de conclure.
- [ ] Faire relire le diff complet contre la spec et corriger les défauts avec tests de régression. Si une revue par sous-agent est choisie, le lancer au premier plan et attendre son résultat.
- [ ] Demander la recette sur téléphone : suppression unique puis multiple sur fichiers d'essai sauvegardés, avertissement sans sauvegarde, refus d'une cible occupée, maintien de VTT/MD distants et disparition effective des fichiers locaux. Toute suppression déclenchée par l'agent exige un accord précis séparé.
- [ ] Créer le commit local de documentation et de version. Après exécution complète, déplacer ce plan dans `docs/archive/`. Consigner les étapes franchies et ce qui reste non vérifié sur la carte Kaneo, sans la clôturer prématurément.
- [ ] Présenter les vérifications et demander séparément les accords de push, fusion et release signée. `scripts/release.sh --dry-run` vérifie l'APK signé si les secrets requis sont configurés ; ne jamais afficher ces secrets. La publication et la clôture ne sont pas autorisées par ce plan.

## Auto-relecture du plan

Chaque exigence de la spec relève d'une tâche : politique et SAF (1), copies et étapes (2), confirmation et concurrence (3), version et recette (4). Les cinq risques de Review Focus ont chacun un test explicite. Les signatures partagées sont définies dans les blocs Interfaces ; les fonctions existantes sont conservées. Aucun code produit n'a été modifié lors de cette préparation.
