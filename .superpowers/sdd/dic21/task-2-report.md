# DIC-21 — lot 2 : conservation du choix et des preuves

## Périmètre exécuté

Implémentation minimale dans `LocalState.kt` de `driveSendChoice` (défaut `ALL_FILES`), `driveReceipts` (défaut liste vide), de `withDriveReceipt` et du format JSON version 7. Les preuves sont remplacées uniquement pour une paire destination (`account`, `folderId`) et `fileName` identique. Le chargement des versions antérieures à 7 n'introduit aucun choix ni preuve; version 6 conserve ses autres champs. Une valeur de choix ou une preuve JSON invalide propage une erreur de lecture comme les structures historiques invalides; `load()` ne réécrit pas le fichier.

Tests ajoutés à `LocalStateTest.kt` : `drive_choice_and_receipts_round_trip`, `version_6_starts_without_receipts`, `receipt_replaces_only_same_destination_and_name`. Le test aller-retour vérifie la version JSON, les nouveaux champs, l'égalité de l'état et la conservation des réglages DIC-20 (traitement automatique, requêtes, paramètres de transcription et synthèse). Le test version 6 vérifie les défauts et la conservation des réglages déjà présents.

## Preuves TDD

### RED

Commande exécutée après ajout des trois tests et avant implémentation :

```text
mise exec -- ./gradlew testDebugUnitTest --tests '*LocalStateTest'
```

Résultat : `BUILD FAILED` à la compilation des tests, attendu : champs `driveSendChoice`/`driveReceipts` et méthode `withDriveReceipt` inconnus. La sortie liste les références manquantes dans les trois nouveaux cas (ex. « No parameter with name 'driveSendChoice' found », « Unresolved reference 'withDriveReceipt' »).

### GREEN

Après ajout de l'implémentation, la première exécution ciblée a compilé mais détecté trois assertions historiques qui attendaient encore la version 6 à l'écriture (`version6PersistsSubjectsAndAttemptMarkerAndReadsVersion5`, `version4RoundTripKeepsBudgetAndProvenance`, migration v2). Ces assertions de version courante ont été mises à jour vers 7.

Commande ciblée finale (LocalState et DriveSync) :

```text
mise exec -- ./gradlew testDebugUnitTest --tests '*LocalStateTest' --tests '*DriveSync*'
```

Sortie finale : `BUILD SUCCESSFUL in 6s` (26 actionable tasks, 2 exécutées). La suite complète, lancée une fois :

```text
mise exec -- ./gradlew testDebugUnitTest
```

Sortie : `BUILD SUCCESSFUL in 11s`; rapport Gradle : 481 tests, 0 échec, 0 erreur, 0 ignoré.

Auto-relecture : `git diff --check` sans sortie (succès). Aucun envoi Drive, push ou fusion effectué.

## Fichiers

- `app/src/main/java/top/supergeoff/dictee/LocalState.kt` — champs/modèle, remplacement ciblé, sérialisation, lecture/migration v7.
- `app/src/test/java/top/supergeoff/dictee/LocalStateTest.kt` — trois cas lot2 et assertions des versions mises à jour.
- `.superpowers/sdd/dic21/task-2-report.md` — ce rapport.

## Risques résiduels

Aucun risque fonctionnel identifié dans le périmètre vérifié. Les tests vérifient la lecture v6, le comportement JSON existant de conservation des fichiers illisibles est inchangé. Aucun test supplémentaire n'a été ajouté spécifiquement pour une entrée de preuve mal formée; le lecteur utilise les accesseurs JSON stricts existants et propage donc leur exception sans altérer le fichier.

## Commit

Commit local ciblé créé : `feat: persist Drive choices and upload receipts` (aucun push).
