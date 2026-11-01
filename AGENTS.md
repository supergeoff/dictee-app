# AGENTS.md

Dictée est une application Android en Kotlin et Jetpack Compose, avec un seul module `app` et le paquet `top.supergeoff.dictee`. Ce fichier donne aux agents de code les commandes et les conventions du dépôt.

## Commandes

- `mise install` installe le JDK 17 déclaré dans `mise.toml`. Le SDK Android 35 doit être installé, et `ANDROID_HOME` ou `local.properties` doit le désigner.
- `./gradlew testDebugUnitTest` lance les tests unitaires.
- `./gradlew assembleDebug` compile l'APK de la variante debug.
- `./gradlew lint` lance le lint Android, qui échoue sur une erreur.

## Conventions

- Le code est en Kotlin, les textes affichés par l'application sont en français.
- Le code sans dépendance Android (`Frame.kt`, `V05Session.kt`, `RecordName.kt`, `RecordingState.kt`, `SendJob.kt`, `RecoveryJob.kt`) s'écrit test d'abord : le test échoue, puis le code le fait passer.
- Les versions de l'application (`versionCode`, `versionName`), des dépendances et du SDK sont dans `app/build.gradle.kts`. Les versions de Gradle et des plugins sont dans `build.gradle.kts` et `gradle/wrapper/`.
- Les fixtures de `app/src/test/resources` sont synthétiques. Elles ne contiennent ni capture d'un vrai boîtier ni identifiant réel.
- Le protocole du boîtier est décrit dans `docs/protocole-v05.md` et le produit dans `docs/specification.md`. Une modification du comportement met à jour le document concerné.
