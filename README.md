# Dictée

Dictée is an Android app that downloads the recordings of a MECHEN V05 voice recorder over Bluetooth Low Energy, stores them in a folder on the phone and can upload them to a Google Drive folder. Transcription and summarization are planned, as described in [docs/specification.md](docs/specification.md). It is an independent project with no affiliation to MECHEN or to the publisher of the DOWAY app.

## Ce que fait l'application

Dictée récupère par Bluetooth les enregistrements d'un enregistreur MECHEN V05 et les range dans un dossier du téléphone. Elle peut aussi les envoyer dans un dossier Google Drive. Chaque enregistrement est un fichier MP3 que le boîtier envoie tel quel.

L'application réunit les enregistrements du boîtier, du téléphone et de Drive dans une même liste groupée par jour. Elle permet d'écouter un enregistrement et garde un journal des échanges avec le boîtier.

## État

La version actuelle est la 0.2.x. La récupération par Bluetooth, le stockage dans un dossier du téléphone, l'envoi vers Google Drive, l'écoute et le journal sont disponibles. La transcription et la synthèse sont prévues : [docs/specification.md](docs/specification.md) décrit les lots à venir.

## Matériel

L'application est testée avec un MECHEN V05. Elle demande Android 12 (API 31) ou plus récent.

## Vie privée

L'application n'a pas de serveur à elle. Les fichiers restent sur le téléphone et dans le Google Drive de l'utilisateur. Les fonctions de transcription prévues enverront l'audio au seul endpoint que l'utilisateur choisit. L'envoi vers Drive utilise le droit `drive.file`, qui limite l'application aux fichiers et aux dossiers qu'elle a créés ou que l'utilisateur a ouverts avec elle.

## Compiler

Le projet demande un JDK 17 et le SDK Android 35. Le fichier `mise.toml` déclare le JDK, que [mise](https://mise.jdx.dev) installe :

```sh
mise install
./gradlew assembleDebug
./gradlew testDebugUnitTest
```

Les commandes Gradle s'exécutent dans un shell où mise est activé, ou préfixées par `mise exec --`. Le SDK Android se trouve par la variable `ANDROID_HOME`, que `mise.toml` fixe à `~/Android/Sdk`, ou par un fichier `local.properties` qui contient `sdk.dir`. L'APK de la variante debug est produit dans `app/build/outputs/apk/debug/`.

## Google Drive pour qui compile

L'envoi vers Drive demande un identifiant OAuth que la personne qui compile l'application crée elle-même. Cet identifiant n'est pas nécessaire pour compiler ni pour exécuter les tests. Les étapes sont les suivantes :

1. Créer un projet Google Cloud.
2. Activer l'API Google Drive dans ce projet. Le bouton « Autre dossier… » demande aussi l'API Google Picker.
3. Configurer l'écran de consentement OAuth.
4. Créer un identifiant OAuth de type Android avec le nom de paquet `top.supergeoff.dictee` et l'empreinte SHA-1 de la clé qui signe l'APK.

Tant que l'écran de consentement est en état « Testing », le compte Google utilisé doit être ajouté comme utilisateur test, et les autorisations expirent au bout de sept jours. Publier l'écran de consentement « In production » supprime ces deux limites. Le droit `drive.file` n'est pas un droit sensible, donc Google n'exige aucune vérification pour cette publication. L'affichage d'un nom et d'un logo demande en revanche une vérification de la marque.

Pour l'APK debug, l'empreinte se lit avec `keytool -list -v -keystore ~/.android/debug.keystore -alias androiddebugkey -storepass android -keypass android`. Si la clé de signature change, l'empreinte doit être mise à jour dans Google Cloud.

## Protocole

Le protocole Bluetooth du boîtier est décrit dans [docs/protocole-v05.md](docs/protocole-v05.md). La spécification du produit se trouve dans [docs/specification.md](docs/specification.md).

## Avertissement

Dictée est un projet indépendant. Il n'a aucun lien avec MECHEN ni avec l'éditeur de l'application DOWAY. Ces marques sont citées pour décrire la compatibilité.

## Licence

Le code est publié sous licence Apache-2.0 (fichier [LICENSE](LICENSE)). Les polices Inter et JetBrains Mono sont sous licence SIL Open Font License, et les icônes viennent de Material Symbols sous licence Apache-2.0. Les textes de ces licences se trouvent dans le dossier [licenses/](licenses/).
