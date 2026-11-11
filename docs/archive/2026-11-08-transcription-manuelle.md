# Transcription manuelle Implementation Plan

Statut : abandonné le 11 novembre 2026, avant toute implémentation. La transcription passe en tâche de fond (carte Kaneo DIC-4). Ce plan est gardé comme archive.

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Transcrire manuellement un MP3 du téléphone et déposer son VTT horodaté à côté, sans altérer le MP3 ni perdre l'ancien VTT avant remplacement.

**Architecture:** Un client dédié envoie le MP3 en flux et reçoit les octets dans un fichier privé. Un job valide le résultat puis appelle `PhoneStore.write` ; `AppModel` possède l'état vivant, coordonne les accès au dossier et expose les actions aux écrans. Les réglages non secrets sont persistés dans `LocalState`, la clé est chiffrée avec Android Keystore.

**Tech Stack:** Kotlin, Android SDK 35/minSdk 31, JDK 17, Jetpack Compose Material 3, coroutines, `HttpURLConnection`, Android Keystore, JUnit 4.

**Spec:** `docs/superpowers/specs/2026-11-07-transcription-dictee-app-design.md` (approuvée ; ses mentions « proposition » sont devenues des décisions).

## Global Constraints

- Déclenchement manuel seulement ; pas de service de premier plan pour la transcription, pas de reprise après arrêt du processus.
- Adresse fournie par l'utilisateur incluant `/v1`, `language=fr` par défaut, `model=dictee-diarize`, `response_format=vtt`, délai de lecture 7 200 000 ms.
- Conserver les octets VTT tels quels après validation UTF-8 stricte et vérification de l'en-tête `WEBVTT` ; accepter un VTT sans séquence parlée.
- Nom de sortie dérivé du nom réel du MP3 du téléphone, en remplaçant `.mp3` par `.vtt`, pas du type éventuellement différent reçu du boîtier.
- Ancien VTT intact jusqu'à la promotion du nouveau ; si le fournisseur empêche la restauration, prévenir de la présence possible d'un `.old` ; après promotion incertaine, demander une vérification du dossier.
- Clé `dk_…` masquée et chiffrée au repos ; aucun secret réel, contenu de réponse ou en-tête d'autorisation dans les logs, les erreurs ou le dépôt.
- Ne pas ajouter de synchronisation Drive dans ce chantier ; conserver le comportement d'envoi Drive existant et ne jamais lancer d'envoi après transcription.
- TDD pour la logique métier : test ciblé d'abord, constater l'échec pertinent, implémenter, constater la réussite, puis commit local. Ne pas pousser ni déployer sans nouvel accord.
- Ne pas écrire de fichier d'état du chantier dans le dépôt ; une fois le chantier terminé, déplacer ce plan dans `docs/archive/`.

## Review Focus

1. La réponse HTTP se termine prématurément malgré un `Content-Length` annoncé : signaler l'erreur et conserver l'ancien VTT (tâche 1 et tâche 2).
2. Les octets contiennent du texte UTF-8 mal formé après `WEBVTT` : refuser sans écriture dans le dossier (tâche 2).
3. Le dossier ou le MP3 change entre l'affichage et le lancement, ou pendant une récupération : ne pas démarrer avec une entrée périmée (tâche 4).
4. Un fournisseur refuse aussi le retour de `.old` au nom final, ou échoue après promotion : avertir sans prétendre que l'ancien nom existe encore (tâche 3).
5. La clé Keystore est absente ou perdue : demander une nouvelle clé ; si un texte d'exception ou une réponse HTTP contient une clé fictive, ne pas la divulguer (tâches 1, 5 et 6).

## Ordre d'exécution

Exécuter les tâches dans l'ordre 1, 2, 3, 5, 4, 6 : les réglages et le coffre de la tâche 5 doivent exister avant le raccordement du modèle de la tâche 4. Chaque commit laisse le projet compilable ; les réglages de la tâche 5 restent utilisables avant l'ajout du bouton.

## Carte des fichiers

- Créer `app/src/main/java/top/supergeoff/dictee/TranscriptionClient.kt` : transport multipart, délai et erreurs HTTP sûres.
- Créer `app/src/main/java/top/supergeoff/dictee/TranscriptionJob.kt` : réception temporaire, validation VTT et écriture via `PhoneStore`.
- Modifier `PhoneFolder.kt` et ses tests de fournisseur : rendre explicites les restaurations ratées et les états incertains après promotion.
- Modifier `LocalState.kt`, `RecordingState.kt`, `AppState.kt` : réglages, erreurs par étape, coordination et état vivant.
- Créer `TranscriptionSecrets.kt` : stockage chiffré et abstraction testable du secret ; modifier `SettingsScreen.kt` et `RecordingScreen.kt` pour le parcours.
- Créer les tests unitaires correspondants dans `app/src/test/java/top/supergeoff/dictee/` et, pour Keystore et les fournisseurs Android, les tests instrumentés dans `app/src/androidTest/java/top/supergeoff/dictee/` ; configurer leur runner dans `app/build.gradle.kts` si nécessaire.

---

### Task 1 : client de transcription en flux

**Fichiers :** créer `TranscriptionClient.kt`, `TranscriptionClientTest.kt` ; tests sous `app/src/test/java/top/supergeoff/dictee/`.

**Interfaces :**
- Produit `data class TranscriptionSettings(val baseUrl: String, val language: String = "fr")` et `interface TranscriptionClient { fun transcribe(settings: TranscriptionSettings, apiKey: String, mp3: PhoneFile, openMp3: () -> InputStream, destination: File) }`.
- Produit `class UrlTranscriptionClient : TranscriptionClient` et `class TranscriptionException(message: String) : IOException` ; succès = destination entièrement écrite, échec = exception sûre et destination non utilisable.

- [ ] **Écrire les tests d'abord.** Dans `TranscriptionClientTest`, utiliser un serveur HTTP local sur loopback et une clé fictive. Vérifier `POST /v1/audio/transcriptions` pour une adresse avec et sans barre finale ; `Authorization: Bearer dk_test`, multipart CRLF avec `file` (nom exact, `audio/mpeg`), `model=dictee-diarize`, `response_format=vtt`, `language=fr`. Vérifier le MP3 octet pour octet, plusieurs blocs de lecture et un fichier destination égal aux octets reçus. Vérifier explicitement `readTimeout == 7_200_000` avec une fabrique de connexions injectable de visibilité interne, sans attendre deux heures.
- [ ] **Ajouter les tests d'erreur.** Un `429` avec `long_audio_busy` donne un message sûr invitant à réessayer ; 401, 500, exception réseau, lecture interrompue et corps plus court que `Content-Length` lèvent `TranscriptionException`. Un faux message serveur contenant `dk_test` n'apparaît ni dans l'exception ni dans un `ListLog`. Le serveur de test annonce `Content-Length` et ferme la socket avant le compte complet.
- [ ] **Constater l'échec.** Exécuter `./gradlew testDebugUnitTest --tests 'top.supergeoff.dictee.TranscriptionClientTest'` ; les nouvelles références manquantes doivent faire échouer la compilation.
- [ ] **Implémenter.** Ajouter les interfaces ci-dessus et le client dédié, sans passer par `UrlHttp` qui convertit actuellement la réponse en `String`. Nettoyer uniquement la barre finale de l'adresse, activer le mode streaming HTTP (`setFixedLengthStreamingMode` avec la taille multipart calculée ou, à défaut, `setChunkedStreamingMode`), puis diffuser les parties multipart et le MP3 par blocs, fixer le timeout de connexion à 30 000 ms et celui de lecture à 7 200 000 ms. Vérifier la longueur déclarée de la réponse lorsqu'elle est présente ; fermer les flux et supprimer le résultat partiel en cas d'erreur. Ne pas utiliser `LoggingHttp` pour transmettre des exceptions brutes ou des corps de réponse.
- [ ] **Vérifier puis commiter.** Relancer la commande ciblée, obtenir `BUILD SUCCESSFUL`, puis `git add app/src/main/java/top/supergeoff/dictee/TranscriptionClient.kt app/src/test/java/top/supergeoff/dictee/TranscriptionClientTest.kt` et `git commit -m 'feat: client de transcription en flux'`.

### Task 2 : validation et dépôt du VTT

**Fichiers :** créer `TranscriptionJob.kt` et `TranscriptionJobTest.kt` dans les répertoires source et test correspondants.

**Interfaces :**
- Consomme `TranscriptionClient.transcribe(...)`, `PhoneStore.open(fileName)` et `PhoneStore.write(fileName, source)`.
- Produit `class TranscriptionJob(private val client: TranscriptionClient, private val store: PhoneStore, private val cacheDir: File) { fun run(settings: TranscriptionSettings, apiKey: String, mp3: PhoneFile): String }`. Le retour est le nom VTT effectivement écrit ; les exceptions sûres signalent l'échec.

- [ ] **Écrire les tests d'abord.** Avec `MemoryStore` et un client fictif, vérifier : `Note-20261107120000.mp3` produit `Note-20261107120000.vtt`, `Call-20261107120000.mp3` produit `Call-20261107120000.vtt` ; un ancien VTT est remplacé seulement après succès, et les octets `WEBVTT\n\n00:00:00.000 --> 00:00:01.000\n<v A>Bonjour\n` restent identiques. `WEBVTT\n\n` est accepté. Le MP3 reste identique.
- [ ] **Tester les refus.** Réponse vide, sans en-tête, UTF-8 invalide même après l'en-tête et exception du client : aucun appel à `store.write`, et l'ancien contenu reste accessible. Une erreur de `store.write` remonte sans être masquée ; l'état du dossier après une promotion incertaine suit les règles de la tâche 3. Aucun fichier temporaire privé ne subsiste après succès ou échec. Faire échouer le flux après quelques octets et vérifier l'ancien VTT intact. Un nom de fichier non reconnu ou non-MP3 est refusé.
- [ ] **Constater l'échec.** Exécuter `./gradlew testDebugUnitTest --tests 'top.supergeoff.dictee.TranscriptionJobTest'` et vérifier l'échec sur les nouvelles références.
- [ ] **Implémenter.** Créer un fichier temporaire distinct par appel dans `cacheDir`, passer au client `store.open(mp3.fileName)`, puis valider tous les octets en UTF-8 avec `CodingErrorAction.REPORT` et contrôler que le premier en-tête est `WEBVTT`. Ne jamais réencoder le contenu ; écrire le fichier validé avec `store.write`, puis retirer le temporaire dans `finally`. Déduire le nom VTT de `RecordName.parse(mp3.fileName)` et de son type réel.
- [ ] **Vérifier puis commiter.** Relancer le test ciblé, attendre `BUILD SUCCESSFUL`, puis ajouter les deux fichiers et commiter `feat: valider et déposer le VTT`.

### Task 3 : signaler les limites du fournisseur de documents

**Fichiers :** modifier `app/src/main/java/top/supergeoff/dictee/PhoneFolder.kt` ; créer `app/src/androidTest/java/top/supergeoff/dictee/PhoneFolderTranscriptionTest.kt` et son fournisseur de documents de test, configurer le runner instrumenté et les dépendances AndroidX Test dans `app/build.gradle.kts` et le manifest de test.

**Interfaces :** consomme et conserve `PhoneStore.write(fileName: String, source: File)` ; produit une `PhoneStoreException` dont le message distingue restauration ratée (`.old` à récupérer) et résultat incertain après promotion. Ne pas modifier le contrat des écritures existantes quand elles réussissent.

- [ ] **Écrire les tests instrumentés d'abord.** Avec un fournisseur de test programmable, initialiser un ancien VTT et injecter un échec avant promotion : l'ancien nom et son contenu sont conservés. Injecter ensuite un refus du renommage de restauration : l'ancien contenu reste sous `.old` et le message indique de le récupérer. Injecter une erreur de lecture du nom après promotion : le message demande de vérifier l'état du dossier et ne promet pas la présence de l'ancien fichier. Vérifier aussi `.part` résiduel et absence d'altération du MP3.
- [ ] **Constater l'échec.** Sur un appareil ou émulateur Android disponible, exécuter `./gradlew connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=top.supergeoff.dictee.PhoneFolderTranscriptionTest` ; constater l'échec des nouvelles assertions. Si aucun appareil n'est accessible, documenter le blocage, préparer les tests et ne pas présenter cette tâche comme vérifiée.
- [ ] **Implémenter.** Modifier `undo` pour détecter les échecs de restauration, sans masquer l'erreur d'origine ; inclure le nom `.old` dans une exception destinée à l'utilisateur. Dans `settle`, transformer l'échec de lecture après promotion en message d'état incertain. Préserver la gestion de `.part` et les autres opérations du fournisseur.
- [ ] **Vérifier puis commiter.** Relancer le test instrumenté jusqu'à réussite, puis `./gradlew testDebugUnitTest` ; commiter les fichiers de source, tests et configuration sous `fix: signaler les restaurations de fichiers impossibles`.

### Task 4 : état, erreurs et coordination du parcours

**Fichiers :** modifier `LocalState.kt`, `RecordingState.kt`, `AppState.kt` ; étendre `LocalStateTest.kt` et `RecordingStateTest.kt` ; créer `TranscriptionCoordinatorTest.kt` dans `app/src/test/java/top/supergeoff/dictee/`.

**Interfaces :**
- Produit `Step.TRANSCRIBE`, `DisplayState.Transcribing`, `Busy(..., transcribing: Set<String> = emptySet())` et `UiState.transcribing: Set<String>`.
- Produit `AppModel.transcribe(name: String)` et étend `AppModel.retry(name: String)` selon la première erreur dans l'ordre de `Step`. `TranscriptionJob.run(...)` est la seule opération qui produit le VTT.
- Produit `class TranscriptionCoordinator` avec `fun tryStartTranscription(name: String): Boolean`, `fun finishTranscription(name: String)`, `fun tryStartRecovery(names: Set<String>): Boolean`, `fun finishRecovery(names: Set<String>)`, `fun tryStartFolderChange(): Boolean` et `fun finishFolderChange()`. Ses réservations sont atomiques, les démarrages incompatibles retournent `false`, et les états d'envoi existants restent pris en compte pour le dossier. Le modèle utilise ce coordinateur aussi pour la récupération et pour toute acceptation d'un changement de dossier.

- [ ] **Écrire les tests d'abord.** Sérialiser puis relire une erreur `TRANSCRIBE` et les erreurs des autres étapes. Vérifier la priorité d'affichage et que « Réessayer » relance exactement l'étape affichée. Avec un client bloqué par latch, vérifier l'état en cours après sortie puis retour sur la fiche, l'absence de deuxième lancement du même nom, l'interdiction de récupérer ou de lire ce MP3 pendant son remplacement et l'interdiction de changer de dossier avant la fin. La récupération en cours ou son erreur, un MP3 absent, un dossier perdu et des réglages absents empêchent l'envoi et donnent un message utile. Tester un choix de dossier reçu pendant la transcription et deux démarrages simultanés : aucune course ne relâche le droit du dossier ou ne déclenche deux jobs.
- [ ] **Constater l'échec.** Exécuter `./gradlew testDebugUnitTest --tests 'top.supergeoff.dictee.TranscriptionCoordinatorTest' --tests 'top.supergeoff.dictee.LocalStateTest' --tests 'top.supergeoff.dictee.RecordingStateTest'` et constater les références absentes.
- [ ] **Implémenter.** Ajouter l'étape, les états et les réservations atomiques ; revalider le dossier, le MP3 et les erreurs au démarrage réel, non uniquement à l'affichage. Raccorder les réglages et le coffre de la tâche 5, puis exécuter le job dans `AppModel.scope` sur `Dispatchers.IO`, pas dans la portée Compose, et relâcher les réservations dans `finally`. Garder le blocage de dossier distinct du compteur `activeJobs` utilisé par `WorkService` : la transcription n'appelle pas `startWork()`. Au succès, effacer uniquement l'erreur de transcription, relire le dossier et annoncer la réussite ; en erreur, enregistrer uniquement un message sûr sous `Step.TRANSCRIBE`. Ne jamais déclencher d'envoi Drive.
- [ ] **Vérifier puis commiter.** Relancer les trois classes de tests, attendre `BUILD SUCCESSFUL` et commiter les fichiers modifiés sous `feat: coordonner la transcription manuelle`.

### Task 5 : réglages et clé protégée

**Fichiers :** modifier `LocalState.kt`, `AppState.kt` et `ui/SettingsScreen.kt` ; créer `TranscriptionSecrets.kt`, `TranscriptionSecretsTest.kt` et `app/src/androidTest/java/top/supergeoff/dictee/AndroidTranscriptionSecretsTest.kt`.

**Interfaces :**
- Produit `interface TranscriptionSecrets { fun read(): String?; fun write(value: String); fun clear() }` et `class AndroidTranscriptionSecrets(context: Context) : TranscriptionSecrets`.
- Ajouter `LocalState.transcriptionBaseUrl: String = ""`, `LocalState.transcriptionLanguage: String = "fr"`, `UiState.hasTranscriptionKey: Boolean` et les actions `AppModel.setTranscriptionSettings(baseUrl: String, language: String)`, `AppModel.setTranscriptionKey(value: String)`, `AppModel.clearTranscriptionKey()`.

- [ ] **Écrire les tests d'abord.** Avec une implémentation fictive et des valeurs fictives, vérifier défauts, sauvegarde/relecture de l'adresse et de la langue, remplacement et suppression de la clé, absence de clé dans `state.json`, messages et journal. Dans le test instrumenté, vérifier le stockage chiffré, la relecture après recréation de l'objet, le remplacement et l'effacement ; si l'entrée chiffrée est corrompue ou la clé Keystore indisponible, la lecture échoue de façon sûre et l'UI demande de saisir à nouveau la clé. Vérifier qu'aucun secret fictif n'apparaît dans les exceptions et les logs.
- [ ] **Constater l'échec.** Exécuter `./gradlew testDebugUnitTest --tests 'top.supergeoff.dictee.TranscriptionSecretsTest' --tests 'top.supergeoff.dictee.LocalStateTest'` et constater l'échec avant implémentation ; lancer le test Android ciblé de façon analogue à la tâche 3 si un appareil est accessible.
- [ ] **Implémenter.** Conserver adresse et langue dans le JSON existant avec leurs défauts pour les états anciens. Chiffrer la clé via AES-GCM avec une clé Android Keystore, IV aléatoire et stockage privé ; `read()` ne divulgue jamais l'exception de déchiffrement. Ne placer que `hasTranscriptionKey` dans `UiState`, jamais la clé. Dans Réglages, ajouter adresse, langue, champ de saisie masqué, remplacement et effacement. Conserver les brouillons de clé hors de l'état sauvegardé par Compose, et ne les afficher ni en texte clair ni dans les captures de logs. Au démarrage, transmettre la clé lue localement au client sans la journaliser.
- [ ] **Vérifier puis commiter.** Relancer les tests unitaires ciblés et le test instrumenté Keystore, puis commiter sous `feat: régler et protéger la clé de transcription`. Signaler séparément tout test Android non exécuté.

### Task 6 : fiche de l'enregistrement et vérification de bout en bout

**Fichiers :** modifier `ui/RecordingScreen.kt`, `ui/SettingsScreen.kt`, les tests d'état nécessaires et `docs/specification.md` ou `docs/prototype-v05.md` selon les conventions du dépôt.

**Interfaces :** consomme `AppModel.transcribe(name)`, `AppModel.retry(name)`, `UiState.transcribing`, `Step.TRANSCRIBE` et les réglages de la tâche 5.

- [ ] **Écrire les tests d'abord.** Tester les règles d'affichage extraites dans une fonction pure de présentation : MP3 disponible et sans erreur de récupération = action disponible ; MP3 absent, récupération/remplacement en cours ou transcription de ce nom = action indisponible. Vérifier « Transcription en cours » sans pourcentage, le bandeau et l'aiguillage « Réessayer », ainsi que la désactivation de « Changer » dans les réglages pendant une transcription. Vérifier que la réponse 429 ne montre aucun corps serveur ni clé, et que quitter puis rouvrir la fiche conserve l'état vivant.
- [ ] **Constater l'échec.** Exécuter la classe de tests de présentation créée pour ces règles avec `./gradlew testDebugUnitTest --tests 'top.supergeoff.dictee.TranscriptionPresentationTest'` et constater l'échec.
- [ ] **Implémenter.** Ajouter un bouton visible « Transcrire » sur la fiche, l'état en cours et le bandeau existant ; ne pas permettre la lecture du MP3 pendant sa récupération ou son remplacement. Faire refléter le verrou du dossier dans les réglages. Documenter le parcours manuel et ses limites dans la documentation existante ; la présence du nouveau VTT ne lance aucune synchronisation Drive.
- [ ] **Vérifier automatiquement.** Exécuter `./gradlew testDebugUnitTest`, puis `./gradlew assembleDebug` ; chacun doit finir par `BUILD SUCCESSFUL`. Exécuter aussi les tests instrumentés des tâches 3 et 5 sur un appareil accessible. Vérifier `git diff --check` et l'absence de clé réelle dans le diff.
- [ ] **Vérifier le parcours sur Android.** Sur un appareil configuré avec le serveur et une clé saisie par l'utilisateur, transcrire un court MP3 de test, vérifier le `.vtt` à côté du MP3 et l'en-tête/locuteurs, relancer puis provoquer une erreur pour contrôler la conservation du résultat précédent. Une requête réelle envoie l'audio au serveur : obtenir un accord explicite sur le fichier choisi avant cet essai. Ne pas prétendre que les cas longs ou ce parcours réel sont vérifiés si aucun appareil, serveur ou accord n'est disponible.
- [ ] **Finaliser.** Commiter les changements locaux sous `feat: ajouter la transcription à la fiche`, déplacer le plan terminé dans `docs/archive/` avec un commit local séparé et mettre à jour DIC-4 avec les vérifications effectivement observées et les limites restantes. Ne pas pousser ni déployer sans accord explicite.
