# Correctifs des tests Android — DIC21

## Preuves RED antérieures

`android-full-results.txt` contient les deux échecs instrumentés attendus :

1. `AutomaticProcessingSettingsUiTest` est rejeté par JUnit avec `Method onlySuccessfulSettingsSaveReexaminesWaitingRequests() should be void`. Dans Kotlin, le corps-expression du test a pour dernier résultat `file.delete()` (un `Boolean`), ce qui expose une signature non-void. La correction ajoute le type de retour explicite `: Unit` et conserve le corps complet, y compris l'appel final à `file.delete()` et toutes les assertions.
2. `ProcessingNotificationsTest.deniedPermissionLeavesDurableResultAvailable` échoue à l'enregistrement dans `LocalStateStore.prepareJson`, avec `InvalidTranscriptionAddressException`. La tâche de fixture utilisait `serverAddress = "url"`, qui ne passe pas `isValidTranscriptionBaseUrl` : le code n'accepte que le défaut `/v1` ou une adresse HTTP(S) avec hôte et chemin `/v1`. La fixture utilise désormais `https://example.invalid/v1`, adresse synthétique conforme, sans trafic réseau réel : le client du test ne fait aucun appel réseau et le scénario s'arrête à la réponse `BackgroundUnsupported`.

## Modifications

- `AutomaticProcessingSettingsUiTest.kt` : type de retour `Unit` explicite sur le test fautif, sans changement ni retrait d'assertion.
- `ProcessingNotificationsTest.kt` : adresse du serveur de fixture remplacée par `https://example.invalid/v1`.
- Aucun fichier de production modifié.

## Validation

Construction de l'APK applicatif et de l'APK androidTest avec la recette isolée et les limites mémoire décrites dans `android-recipe-report.md` :

```sh
mise exec -- ./gradlew -I .superpowers/sdd/dic21/recipe.init.gradle --no-daemon --max-workers=1 -Dorg.gradle.jvmargs='-Xmx768m -XX:MaxMetaspaceSize=512m' assembleDebug assembleDebugAndroidTest
```

Résultat : `BUILD SUCCESSFUL in 41s` ; 70 tâches, 4 exécutées et 66 à jour. Le chemin compilé est androidTest seulement; aucun APK n'a été installé et aucun appareil n'a été piloté. Les tests instrumentés ne sont donc pas re-exécutés ici. Un avertissement SDK XML (outil actuel reconnaissant jusqu'à la version 3, SDK XML version 4 rencontré) n'a pas empêché la construction.
