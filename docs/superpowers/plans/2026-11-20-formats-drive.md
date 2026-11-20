# Formats Drive Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Appliquer les formats globaux à tous les envois et autoriser une exception par appui long.

**Architecture:** `LocalState` conserve un `Set<FileKind>` de formats globaux. Chaque demande capture les formats de l’envoi ; `DriveSync` utilise ce même ensemble pour les compteurs, la confirmation et le travail effectif.

**Tech Stack:** Kotlin, Jetpack Compose, JUnit.

**Spec:** `docs/superpowers/specs/2026-11-20-formats-drive-design.md`.

## Global Constraints

L’application conserve Kotlin et Jetpack Compose sans ajouter de dépendance ; les textes restent en français. La génération locale, la suppression et les règles Drive de conflit et de copie existante restent inchangées.

## Review Focus

Les tests couvrent la mise à jour depuis les anciens choix, la persistance d’une sélection vide, un format non activé seul disponible, un VTT déjà à jour et l’absence d’effet de l’exception sur l’envoi suivant.

### Tâche 1 : modèle, stockage et envoi

Modifier `LocalState.kt`, `DriveSync.kt`, `AppState.kt`, `SendJob.kt`, `RecordingLocation.kt`, `RecordingPipeline.kt` et les appels concernés sous `app/src/main/java/top/supergeoff/dictee/`. Les fichiers de test correspondants sont sous `app/src/test/java/top/supergeoff/dictee/`.

- [ ] Dans `LocalStateTest`, vérifier que les anciens choix donnent `setOf(FileKind.VTT)` et qu’un aller-retour conserve `emptySet<FileKind>()` ainsi qu’un choix mixte. Dans `DriveSyncTest` et `SendJobTest`, vérifier que `{VTT}` ignore un MD local même si le VTT est déjà à jour, que `{MD}` ne programme que le MD et qu’un ensemble vide n’est ni candidat ni complet. Dans `DriveSendRequestTest`, vérifier qu’une exception `{MD}` ne modifie pas les formats `{VTT}` d’une nouvelle demande.
- [ ] Lancer `./gradlew testDebugUnitTest` et constater l’échec avant l’implémentation.
- [ ] Ajouter `LocalState.driveSendFormats: Set<FileKind>` (VTT seul par défaut), versionner le schéma et enregistrer la liste des formats. Remplacer l’ancien choix par `DriveSendRequest.formats: Set<FileKind>` et `DriveSendRequest.customizeFormats: Boolean`. Adapter `DriveSync` et `SendJob` à ces ensembles, retirer le choix VTT+MD forcé du compteur global et retirer la sauvegarde du dernier choix dans `AppState.sendNow`. Exposer `AppState.setDriveSendFormat(kind: FileKind, enabled: Boolean)` et un paramètre `customizeFormats: Boolean = false` sur `AppState.send`. L’appui court lance l’envoi sans ouvrir de dialogue : calculer les cibles admissibles avec les formats capturés, puis réutiliser le même contrôle d’admission et le même lancement que pour l’envoi personnalisé. L’appui long ouvre le menu et sa confirmation utilise les formats cochés. Dans les deux cas, conserver le contrôle des cibles et des conflits.
- [ ] Relancer `./gradlew testDebugUnitTest` et vérifier que tous les tests passent. Faire un commit local du modèle et des adaptations nécessaires à la compilation.

### Tâche 2 : réglages et actions Compose

Modifier `ui/SettingsScreen.kt`, `ui/DriveSendDialog.kt`, `ui/RecordingScreen.kt`, `ui/RecordingsScreen.kt`, `MainActivity.kt` et `docs/specification.md`.

- [ ] Dans `RecordingListPresentationTest`, vérifier qu’un enregistrement MP3 seul donne un compteur normal de zéro avec `{VTT}` mais reste éligible au choix personnalisé, dans sa portée. Lancer `./gradlew testDebugUnitTest` et constater l’échec avant de modifier la présentation.
- [ ] Afficher les interrupteurs MP3, VTT et MD dans les réglages Drive. Brancher le toucher et l’appui long de chaque entrée d’envoi sur `AppState.send`, sans modifier la portée de l’action. Déterminer la possibilité d’un appui long en évaluant les trois formats avec les mêmes règles de disponibilité que l’envoi normal. Ajouter un libellé accessible à l’appui long et un indice visible. Si aucun format par défaut n’est à envoyer, le toucher explique comment ouvrir le choix sans déclencher d’envoi.
- [ ] Dans `DriveSendDialog`, montrer le compte, le dossier et les formats ; ouvrir ce dialogue uniquement à l’appui long et rendre les formats modifiables. Recalculer les cibles au changement des cases, désactiver « Envoyer » sans cible et ne signaler comme manquants que les documents cochés. Mettre à jour la spécification produit.
- [ ] Lancer `./gradlew testDebugUnitTest assembleDebug lint` et vérifier la réussite. Sur émulateur ou téléphone si disponible, contrôler le toucher et l’appui long sur la fiche, la sélection et le bouton global, ainsi que le retour au VTT seul après une exception. Ne pas envoyer de fichier vers un compte Drive réel sans accord. Faire un commit local.
