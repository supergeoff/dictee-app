# DIC-25 : déboguer et recetter la synthèse Markdown

Ce document est le brief d'exécution de la carte DIC-25. Il complète la spec `2026-11-14-dic19-synthese-design.md` et ne la remplace pas. Il décrit les défauts vus au premier essai sur téléphone de la version 0.4.0, ce que la lecture du code en dit, les preuves à obtenir, les corrections à appliquer quand une preuve est obtenue, puis la recette.

## But et fin

Sur téléphone, « Créer le MD » et « Refaire le MD » ne produisent aucun effet visible quand un VTT existe, et le journal ne montre rien pour la transcription ni pour la synthèse. Le travail consiste à rendre le parcours observable, à prouver la cause de chaque symptôme, à corriger test d'abord, puis à dérouler la recette.

La carte est finie quand l'utilisateur obtient sur son téléphone un MD à partir d'un VTT existant, que le journal exporté raconte ce qui s'est passé, et que `./gradlew testDebugUnitTest`, `./gradlew assembleDebug` et `./gradlew lint` passent.

## Cadre d'exécution

- La branche est `feature/dic-19-synthese`. La version testée est la 0.4.0 (`versionCode` 13).
- La machine de développement n'a ni téléphone ni `adb`. Les tests instrumentés ne peuvent donc pas tourner ici. La recette sur téléphone est faite par l'utilisateur, qui installe l'APK debug et exporte le journal depuis l'écran Journal.
- La méthode est celle du skill `systematic-debugging` : aucune correction avant une cause prouvée par un test ou par une ligne de journal.
- Le code sans dépendance Android s'écrit test d'abord (voir `AGENTS.md`).
- Les fixtures restent synthétiques. Aucune clé, aucune adresse réelle et aucun contenu d'enregistrement n'entre dans le dépôt ni dans le journal.
- Aucun push, aucune fusion et aucune release sans accord explicite de l'utilisateur.

## Symptômes rapportés

1. Un enregistrement a son MP3 et son VTT, sans MD. Un appui sur « Créer le MD » dans l'onglet Synthèse ne produit aucun effet visible.
2. Sur le même enregistrement, le menu « Refaire le MD » ne produit aucun effet visible non plus.
3. L'écran Journal ne montre aucune ligne avec les filtres Transcription et Synthèse.
4. Sur un enregistrement sans VTT, le menu « Refaire le VTT et le MD » lance bien la transcription.
5. La transcription est longue, et elle semble échouer quand une autre application (un client de dictée) utilise le même serveur de transcription au même moment.

## Parcours d'une demande de MD dans le code

Ce parcours sert de carte pour poser les lignes de journal et les tests.

1. `RecordingScreen` appelle `AppModel.transcribe(setOf(name), request)`. La demande vaut `MISSING` pour « Créer le MD » et `REDO_MD` pour « Refaire le MD ».
2. `AppModel.transcribe` appelle `announceExclusions` (pour `MISSING` seulement), puis `TranscriptionCoordinator.start`. Une exception est notée au journal en source `APP` et affichée dans un toast.
3. `TranscriptionCoordinator.start` prend le mutex du coordinateur, puis le verrou de transfert de `RecoverySafety`. Il appelle `prepareRequested`, donc `TranscriptionPreparation.prepare`.
4. `TranscriptionPreparation.prepare` relit le dossier du téléphone, calcule le plan avec `SynthesisSelection.plan`, lit le VTT, fige le contexte de synthèse et crée la tâche. Avec un VTT présent, la tâche naît en phase `SYNTHESIZING` avec une synthèse `READY`.
5. `start` programme le travail périodique WorkManager (`TranscriptionSchedule.enqueue`), écrit la tâche dans `state.json`, puis lance le service de premier plan.
6. `AppModel` demande la boucle interactive (`InteractiveTranscriptionLifecycle`, puis `TranscriptionInteractiveRunner.run`). La boucle prend la tâche active et non suspendue dont le nom d'enregistrement est le plus petit, et appelle `runBounded`.
7. `runBounded` reprend le mutex et le verrou de transfert, puis `runLocked` appelle `synthesize`.
8. `synthesize` relit le VTT, vérifie son empreinte et la clé, passe la synthèse en `CALLING`, appelle `UrlSynthesisClient.complete`, écrit le résultat dans un fichier temporaire, puis `saveSynthesis` écrit le MD dans le dossier et enregistre sa provenance.

## Constats tirés de la lecture du code

Le constat 1 est vérifié par une recherche dans le code. Les autres sont des lectures : chacun donne la preuve à obtenir et la correction à appliquer si la preuve est obtenue. Un constat que son test infirme est noté comme tel dans le compte rendu, sans correction.

### Constat 1 : rien n'écrit dans les sources STT et LLM (vérifié)

`Source.STT` et `Source.LLM` n'apparaissent que dans le filtre de `Journal.kt`. Ni `TranscriptionCoordinator`, ni `UrlTranscriptionClient`, ni `UrlSynthesisClient`, ni les runners ne reçoivent un `Log`. Le symptôme 3 est donc attendu, quelle que soit la cause des autres.

Correction : appliquer la section « Journalisation » ci-dessous. C'est la première étape, parce qu'elle fournit les preuves des constats suivants.

### Constat 2 : seule une exception laisse une trace

`AppModel.transcribe` n'écrit au journal que dans son `catch`, en source `APP`. Une demande qui attend, qui est exclue ou qui crée une tâche ne laisse aucune ligne.

Correction : couverte par la section « Journalisation ».

### Constat 3 : une demande attend en silence derrière un appel réseau

`TranscriptionCoordinator.start` attend le mutex du coordinateur. `runBounded` tient ce mutex pendant toute la durée de `submit` (l'envoi du MP3) et de `synthesize` (l'appel au service de synthèse, dont le délai maximal est de deux heures). Pendant ce temps, un appui sur « Créer le MD » ne crée aucune tâche et n'affiche rien.

Preuve : un test JVM lance `runBounded` sur une tâche dont le client de synthèse reste bloqué, appelle `start` pour un autre enregistrement, et constate que `start` ne rend pas la main.

Correction si la preuve est obtenue : la création d'une tâche ne doit plus attendre la fin d'un appel réseau. Le coordinateur garde un verrou court pour lire et modifier la file, et ne tient plus ce verrou pendant `client.submit`, `client.get`, `client.cancel` et `synthesisClient.complete`. Les gardes existantes restent en place : relecture de la phase avant chaque écriture, garde de promotion, annulation prioritaire. Le test de preuve devient le test de non-régression : `start` rend la main et la nouvelle tâche est enregistrée pendant qu'un appel est en cours.

Critère d'abandon : si cette séparation fait échouer plus de tests existants qu'elle n'en ajoute et que deux tentatives de correction échouent, s'arrêter et rendre compte à l'utilisateur avant d'aller plus loin.

### Constat 4 : le verrou de transfert est partagé avec la récupération et l'envoi Drive

`start` et `runBounded` prennent aussi `RecoverySafety.withTransferLock`, que tiennent la récupération Bluetooth (`AppModel.recover`) et l'envoi vers Drive (`AppModel.sendNow`). Une demande de MD attend donc aussi derrière une récupération ou un envoi en cours.

Preuve : même test que le constat 3, avec le verrou de transfert tenu par un faux transfert.

Correction si la preuve est obtenue : une tâche qui ne touche pas au MP3 (synthèse seule) n'a pas besoin du verrou de transfert pour être créée. Elle est créée tout de suite et attend son tour dans la file. Le verrou reste requis pour lire ou écrire un MP3.

### Constat 5 : sans réglages de synthèse, la tâche naît déjà terminée

Dans `TranscriptionPreparation.prepare`, si les réglages de synthèse sont incomplets, la tâche de synthèse seule est créée en phase `COMPLETED` avec une synthèse `SUSPENDED` et la raison « Configure la synthèse puis crée explicitement un travail MD. ». `SynthesisTaskNotice` affiche cette raison sans bouton, parce que la tâche n'est plus en phase `SYNTHESIZING`.

Preuve : un test de `TranscriptionPreparation.prepare` avec des réglages de synthèse vides et un VTT présent.

Correction si la preuve est obtenue : aucune tâche n'est créée quand la synthèse n'est pas configurée et que seul le MD est demandé. `AppModel.transcribe` affiche le message « Réglages de synthèse à compléter. Ouvre Réglages, rubrique Synthèse. » et l'écrit au journal en source `LLM`. La forme finale de ce message vient de la refonte DIC-26.

### Constat 6 : chaque nouvel appui ajoute une tâche identique

Conséquence du constat 5 : la tâche terminée ne compte pas comme active, donc le bouton « Créer le MD » reste affiché, et chaque appui ajoute une tâche terminée de plus dans `state.json`, sans changement à l'écran.

Preuve : le test du constat 5, appelé deux fois, compte les tâches.

Correction : celle du constat 5 suffit.

### Constat 7 : la file est stricte, du plus ancien au plus récent

`TranscriptionInteractiveRunner.run` et `runLocked` ne traitent que la tâche active dont le nom d'enregistrement est le plus petit. Une synthèse demandée pour un enregistrement récent attend la fin de toute tâche d'un enregistrement plus ancien, y compris une transcription de deux heures. Cette règle est voulue (une tâche à la fois) et ne change pas.

Preuve : un test crée une transcription en phase `TRACKING` pour un enregistrement ancien et une synthèse pour un enregistrement récent, puis constate que la synthèse ne part pas.

Correction : aucune sur la règle. Le défaut est l'affichage, traité au constat 8.

### Constat 8 : une tâche en attente affiche « Synthèse en cours »

`synthesisProgressMessage` renvoie « Synthèse en cours. Le MD sera enregistré dès réception. » pour une synthèse `READY`, même quand la tâche n'est pas en tête de file et qu'aucun appel n'est parti.

Preuve : un test de `synthesisProgressMessage` (ou de la fonction qui la remplace) avec deux tâches actives.

Correction si la preuve est obtenue : la fonction reçoit la liste des tâches. Une tâche qui n'est pas en tête renvoie « En attente : n-ième dans la file. Aucun appel n'est parti. ». La correction reste minimale, parce que la refonte DIC-26 remplace cet affichage.

### Constat 9 : une annulation non confirmée bloque la boucle interactive

`TranscriptionInteractiveRunner.run` choisit sa tâche parmi les tâches non terminées et non suspendues, sans exclure la phase `CANCEL_REQUESTED`. `runLocked`, lui, exclut cette phase pour désigner la tête de file. Tant que le serveur n'a pas confirmé une annulation (serveur injoignable, tâche encore en cours côté serveur), la boucle interactive rappelle la même tâche toutes les cinq secondes et les autres tâches ne partent pas.

Preuve : un test de `TranscriptionInteractiveRunner` avec une tâche `CANCEL_REQUESTED` dont le client répond « en cours », et une seconde tâche prête.

Correction si la preuve est obtenue : la boucle interactive traite l'annulation en attente, puis passe à la tâche suivante dans le même tour, avec la même règle de tête de file que `runLocked`.

### Constat 10 : une erreur HTTP à l'envoi du MP3 met la tâche en échec définitif

Dans `submit`, toute `TranscriptionHttpException` passe la tâche en phase `FAILED`. Un serveur occupé qui répond 429 ou 503 parce qu'un autre client l'utilise produit donc un échec, sans nouvelle tentative. C'est une explication possible du symptôme 5.

Preuve : un test de `TranscriptionCoordinator` avec un faux client qui répond 429 à `submit`.

Correction si la preuve est obtenue : les codes 429, 502, 503 et 504 à l'envoi laissent la tâche en phase `PENDING` avec une nouvelle tentative différée, selon la règle déjà retenue pour la synthèse : trois tentatives au plus, une minute puis deux minutes d'attente, puis suspension avec la raison « Le serveur de transcription est occupé. Relance quand il est disponible. ». Les autres codes 4xx gardent l'échec immédiat. Le renvoi du même MP3 est sans risque : le serveur rejoint la tâche existante au lieu d'en créer une seconde.

### Constat 11 : la liste ne distingue ni la synthèse ni l'attente

`buildUi` remplit `Busy.transcribing` avec la première tâche active, quelle que soit sa phase. La ligne affiche donc « Transcription… » pendant une synthèse. Une tâche en attente affiche « Récupéré ». La barre d'avancement dit « Transcription 1/n » pendant une synthèse, alors que la notification dit déjà « Synthèse ».

Correction : la barre d'avancement de la liste reprend `transcriptionNotificationLabel`. Le reste est traité par la refonte DIC-26 et n'entre pas dans cette carte.

## Journalisation

Le coordinateur, les deux clients et `AppModel.transcribe` reçoivent un `Log`. Les lignes de transcription vont en source `STT`, les lignes de synthèse en source `LLM`. Chaque ligne commence par le nom du fichier concerné quand il existe (par exemple `Note-20261112091400`), pour pouvoir suivre un enregistrement dans l'export.

Événements à écrire :

| Moment | Source | Exemple de ligne |
| --- | --- | --- |
| Demande reçue par `AppModel.transcribe` | STT ou LLM | `Note-… : demande reçue (MD manquant)` |
| Demande exclue | STT ou LLM | `Note-… : demande écartée, tâche déjà active` |
| Attente d'un verrou supérieure à deux secondes | STT ou LLM | `Note-… : en attente d'un transfert en cours` |
| Tâche créée | STT ou LLM | `Note-… : tâche créée, 2e dans la file` |
| Changement de phase | STT ou LLM | `Note-… : phase TRACKING` |
| Envoi du MP3 | STT | `Note-… : MP3 envoyé, réponse 200 en 41 s` |
| Suivi de la tâche serveur, à chaque changement de pourcentage entier | STT | `Note-… : serveur 62 %` |
| VTT écrit | STT | `Note-… : VTT enregistré` |
| Appel de synthèse | LLM | `Note-… : appel envoyé, tentative 1/3` |
| Réponse de synthèse | LLM | `Note-… : réponse 200 en 94 s` |
| MD écrit | LLM | `Note-… : MD enregistré` |
| Nouvelle tentative prévue | STT ou LLM | `Note-… : nouvelle tentative dans 60 s` |
| Suspension ou échec | STT ou LLM, par `Log.error` | `Note-… : suspendue, clé de synthèse inaccessible` |
| Annulation | STT ou LLM | `Note-… : annulation demandée` |

Règles :

- Une ligne ne contient jamais de clé, d'en-tête d'autorisation, de contenu de VTT, de contenu de MD ni de texte de prompt.
- Une adresse de serveur n'apparaît jamais en entier. Le code HTTP et la durée suffisent.
- Le corps d'une réponse d'erreur n'est pas recopié. Seuls les messages déjà catégorisés par les clients sont écrits.
- Le journal ne lève jamais d'exception (c'est déjà le contrat de `Log`).
- Les tests vérifient les lignes avec un faux `Log` en mémoire, et vérifient qu'une clé factice passée au client n'apparaît dans aucune ligne.

## Ordre de travail

1. Poser la journalisation et ses tests. Vérifier que les tests existants passent toujours.
2. Écrire un test JVM qui rejoue le parcours complet d'une demande de MD avec un VTT présent, depuis `TranscriptionCoordinator.start` jusqu'au MD écrit, avec de faux clients. Ce test dit si le parcours nominal fonctionne hors téléphone.
3. Écrire le test de preuve de chaque constat, du 3 au 10. Noter pour chacun s'il est confirmé ou infirmé.
4. Pour chaque constat confirmé, écrire la correction décrite ci-dessus, une à la fois, avec le test de preuve devenu test de non-régression.
5. Lancer `./gradlew testDebugUnitTest`, `./gradlew assembleDebug` et `./gradlew lint`.
6. Remettre l'APK debug à l'utilisateur pour la recette. Lire le journal exporté qu'il renvoie et désigner la cause réelle de chaque symptôme.
7. Si la recette révèle une cause que ce document ne prévoit pas, repartir de l'étape 3 avec un nouveau constat, sans corriger au jugé.
8. Mettre à jour `docs/specification.md` et la spec DIC-19 pour tout comportement modifié (nouvelles tentatives à l'envoi, absence de tâche sans réglages de synthèse, lignes de journal).

## Recette sur téléphone

L'utilisateur déroule chaque scénario, puis exporte le journal. Un scénario est réussi quand l'écran et le journal correspondent aux attentes.

| N° | Départ | Action | Attendu à l'écran | Attendu au journal |
| --- | --- | --- | --- | --- |
| 1 | MP3 et VTT présents, MD absent, synthèse configurée, aucune tâche en cours | « Créer le MD » | Un message de synthèse en cours, puis le MD rendu dans l'onglet Synthèse | Lignes LLM : demande reçue, tâche créée, appel envoyé, réponse, MD enregistré |
| 2 | MP3, VTT et MD présents | Menu « Refaire le MD » | Le MD est remplacé, l'audio n'est pas retranscrit | Lignes LLM seulement, aucune ligne STT |
| 3 | MP3, VTT et MD présents | Menu « Refaire le VTT et le MD » | Les deux fichiers sont remplacés | Lignes STT, puis lignes LLM |
| 4 | Plusieurs enregistrements, certains complets | « VTT + MD » sur la liste | Seuls les fichiers manquants sont produits | Aucune ligne pour les enregistrements complets |
| 5 | Une transcription longue en cours | « Créer le MD » sur un autre enregistrement | Un message d'attente avec le rang dans la file, dans les deux secondes | Ligne LLM : tâche créée, n-ième dans la file |
| 6 | Synthèse non configurée | « Créer le MD » | Un message qui dit quoi régler et où | Ligne LLM : réglages de synthèse à compléter |
| 7 | Synthèse en cours | Retirer l'app des applications récentes | Le MD est présent à la réouverture | Lignes LLM jusqu'à « MD enregistré » |
| 8 | Transcription en cours | Utiliser le client de dictée sur le même serveur | La tâche se termine, ou affiche son attente et sa prochaine tentative | Lignes STT avec les codes HTTP reçus |
| 9 | Tous les scénarios | Export du journal | Sans objet | Chaque scénario laisse des lignes STT ou LLM lisibles, sans clé ni contenu |

## Hors périmètre

- La refonte de l'interface (ligne des quatre étapes, bouton unique, badges) est la carte DIC-26, qui s'enchaîne sur la même branche. Les corrections d'affichage de DIC-25 restent minimales.
- Toute correction du serveur de transcription fait l'objet d'une carte séparée. DIC-25 se limite à relever les codes HTTP reçus par l'app.
- La règle « une tâche à la fois, la plus ancienne d'abord » ne change pas.

## Compte rendu attendu

Le compte rendu va en commentaire de la carte DIC-25. Il donne, pour chaque symptôme, la cause prouvée et la preuve (test ou lignes de journal), la liste des constats confirmés et infirmés, les correctifs appliqués avec leurs tests, le résultat des trois commandes Gradle, et les scénarios de recette qui n'ont pas pu être vérifiés.
