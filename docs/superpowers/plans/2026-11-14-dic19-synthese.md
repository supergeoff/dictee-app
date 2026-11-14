# DIC-19 : synthèse Markdown, plan d'implémentation

> **Pour les agents d'exécution :** skill requis : `subagent-driven-development` ou `executing-plans`, selon le choix de Geoff après revue. Les cases suivent les étapes, pas un état séparé du chantier.

**But :** produire les VTT et MD manquants depuis « VTT + MD », avec une synthèse configurable, durable et lisible dans la fiche.

**Architecture :** prolonger la file et le coordinateur DIC-4, avec des phases MD persistées, une réponse en attente dans le stockage privé et une provenance associée aux empreintes des fichiers. Le service interactif et WorkManager passent par le même coordinateur et le même budget d'essais.

**Stack :** Kotlin, JDK 17, Android SDK 35, Compose, WorkManager 2.10.1, Android Keystore, JUnit 4, `HttpURLConnection`, Markwon core 4.6.2 pour le rendu Markdown natif.

**Spec :** `docs/superpowers/specs/2026-11-14-dic19-synthese-design.md`. La lire intégralement avec les `AGENTS.md` avant l'exécution ; ses garanties prévalent sur les raccourcis de ce plan.

## Contraintes globales

- L'interface et les messages sont en français. Garder les composants, Figtree, JetBrains Mono, couleurs et thèmes existants.
- Aucun endpoint, identifiant de modèle personnel, clé, audio réel, VTT réel ou chemin local dans le dépôt, les fixtures ou les journaux. Les exemples utilisent `example.invalid` et des valeurs synthétiques.
- La clé de synthèse utilise Android Keystore et n'apparaît jamais dans `state.json` ; aucune ancienne référence encore utilisée ne doit être supprimée.
- Une requête `POST <base>/chat/completions` contient le prompt résolu dans `system`, le VTT entier dans `user` et le modèle figé. Aucun découpage ni troncature silencieuse.
- Une tentative initiale plus deux relances automatiques au maximum ; délais minimaux persistés de 60 s et 120 s. Une relance manuelle repart avec trois tentatives. Une tentative interrompue peut être facturée.
- La sélection ne remplace rien. Les remplacements sont explicites. L'ancien MD reste disponible jusqu'à promotion ; Drive reste manuel.
- Les tâches DIC-4 déjà persistées restent VTT uniquement, sans synthèse implicite. Le dossier du téléphone reste la source de vérité des fichiers.
- Les nouvelles règles sont testées d'abord : RED, puis GREEN et refactorisation. Une erreur de compilation due à une API pas encore définie est un premier RED, à compléter par un échec d'assertion avant de considérer le comportement testé.
- Tous les commits indiqués sont locaux. Ne pas pousser, taguer, publier ou transmettre une réunion réelle sans une confirmation distincte au moment de l'action. Ne jamais demander à Geoff de coller un secret dans la conversation.

## Points de revue

Chaque ligne est associée à un test dans la tâche indiquée.

1. Le VTT change après l'appel mais avant la promotion : suspendre sans remplacer l'ancien MD (tâche 7).
2. Le processus s'arrête après promotion mais avant la provenance : vérifier le hash final, finaliser sans nouvel appel (tâches 6 et 7).
3. Une réponse de chat contient un refus, un texte vide ou `finish_reason=length` : suspendre sans écrire (tâche 4).
4. Deux intervenants portent des caractères accentués ou des doublons : les variables restent fidèles et ne deviennent pas des identités inférées (tâche 3).
5. Un VTT/MD apparaît ou change à l'extérieur après la sélection : ne pas écraser un fichier apparu entre-temps ; signaler la provenance inconnue ou ancienne (tâches 2, 6 et 7).

## Carte des fichiers

Les chemins Kotlin sont relatifs à `app/src/main/java/top/supergeoff/dictee/`, les tests à `app/src/test/java/top/supergeoff/dictee/`, sauf indication contraire.

- `SynthesisState.kt` : contexte figé, phases, budget et provenance MD. `SynthesisSettings.kt` : type des réglages dès la tâche 1, gestionnaire à la tâche 5. `TranscriptionState.kt`, `LocalState.kt` : lien avec la file existante et sérialisation version 4.
- `SynthesisSelection.kt` : matrice des fichiers manquants et actions explicites ; `RecordingState.kt` : sélection pour l'accueil.
- `SynthesisPrompt.kt` : prompt par défaut, extraction des variables et substitution ; `WebVtt.kt` reste le parseur de validation.
- `SynthesisSettings.kt`, `SynthesisSettingsForm.kt` : réglages séparés et formulaire ; réutiliser `CredentialStore` avec un alias Keystore et des préférences distincts.
- `SynthesisClient.kt` : requête chat, validation et erreurs sûres ; aucun corps de fournisseur dans les erreurs ou le journal.
- `SynthesisStorage.kt` : attente privée durable, empreintes et réconciliation de provenance. `PhoneStore.kt`, `PhoneFolder.kt` : garde juste avant promotion.
- `TranscriptionPreparation.kt`, `TranscriptionCoordinator.kt`, `TranscriptionWorkerRunner.kt`, `TranscriptionWorker.kt`, `AppState.kt`, `WorkService.kt` : préparation et orchestration communes, annulation, reprise et affichage.
- `ui/SettingsScreen.kt`, `ui/RecordingScreen.kt`, `ui/RecordingsScreen.kt`, `MainActivity.kt`, nouveau `ui/MarkdownView.kt` : parcours validé.
- `app/build.gradle.kts`, `docs/specification.md` : dépendance de rendu, version et documentation livrée.

Les tests nouveaux portent les noms des fichiers de production ; les tests DIC-4 existants restent actifs. Les commandes ciblées utilisent `./gradlew testDebugUnitTest --tests 'top.supergeoff.dictee.NomDuTest'` ; chaque tâche passe aussi `./gradlew testDebugUnitTest` avant son commit.

---

### Tâche 1 : état durable et migration

**Fichiers :** créer `SynthesisState.kt`, `SynthesisSettings.kt`, `SynthesisStateTest.kt` ; modifier `TranscriptionState.kt`, `TranscriptionSettings.kt`, `LocalState.kt`, `LocalStateTest.kt`.

**Interfaces :** ajouter `SynthesisPhase { WAITING_VTT, READY, CALLING, SAVE_PENDING, SUSPENDED, COMPLETED, CANCELLED }`, `PromptMetadata(type: String, date: String, duree: String)`, `SynthesisContext(baseUrl: String, credentialRef: String, model: String, promptTemplate: String, metadata: PromptMetadata, systemPrompt: String?, vttSha256: String?)`, `SynthesisWork(phase: SynthesisPhase, mdName: String, replaceMd: Boolean, context: SynthesisContext?, attempts: Int, nextAttemptAtMillis: Long?, stagedSha256: String?, suspensionReason: String?)` et `SynthesisProvenance(folderUri: String, mdName: String, model: String, vttSha256: String, mdSha256: String)`. Ajouter `SYNTHESIZING` à `TranscriptionPhase`, `synthesis: SynthesisWork? = null` et `replaceVtt: Boolean = true` à `TranscriptionTask`. Ajouter `synthesisSettings: SynthesisSettings` et `synthesisProvenance: List<SynthesisProvenance>` à `LocalState`. Dès cette tâche, définir dans `SynthesisSettings.kt` le type complet `SynthesisSettings(baseUrl: String = "/v1", model: String = "", credentialRef: String? = null, prompt: String = DEFAULT_SYNTHESIS_PROMPT)` et le texte `DEFAULT_SYNTHESIS_PROMPT` conforme à la spec ; la tâche 3 expose ce même texte comme `SynthesisPrompt.DEFAULT` sans le dupliquer.

`context == null` n'est autorisé que pour une synthèse non configurée, jamais pour un appel. Pour un nouveau VTT, figer le template, le type, la date, la durée et les réglages dès la création, puis figer les locuteurs, le prompt résolu et le hash après promotion du VTT ; un VTT déjà présent est validé et figé dès la préparation. Une tâche MD seul utilise `SYNTHESIZING`, des champs STT vides et jamais de client STT. Une tâche ancienne conserve `synthesis == null` et `replaceVtt == true` à la migration. Une synthèse non configurée après un VTT réussi reste visible comme suspendue mais ne bloque pas la création explicite d'une nouvelle tâche MD après configuration.

- [x] Écrire dans `LocalStateTest` `version3TasksRemainVttOnly` : lire un JSON version 3 avec tâches `PENDING`, `TRACKING`, `SAVING`, `COMPLETED` et vérifier `synthesis == null`, paramètres et IDs inchangés. Écrire `version4RoundTripKeepsBudgetAndProvenance` : `CALLING`, `attempts=2`, `nextAttemptAtMillis=120000`, hashes et modèle survivent à save/load ; le texte d'une clé synthétique n'apparaît pas dans le fichier. Tester aussi un enum MD inconnu : chargement en erreur, pas d'exécution par défaut.
- [x] Lancer `./gradlew testDebugUnitTest --tests 'top.supergeoff.dictee.LocalStateTest'` et observer l'échec lié à l'absence du nouvel état.
- [x] Implémenter ces types et la lecture/écriture `LocalStateStore` version 4. Préserver les migrations et la validation des adresses existantes ; vérifier les adresses figées de synthèse comme celles de transcription. Autoriser les champs STT vides uniquement pour une tâche MD seul en phase `SYNTHESIZING`, sans jamais les transmettre au client STT. Le lecteur v3 initialise tous les ajouts sans déclencher de travail MD.
- [x] Relancer le test ciblé et `./gradlew testDebugUnitTest` : succès. Commit local : `git add app/src/main/java/top/supergeoff/dictee/{SynthesisState,SynthesisSettings,TranscriptionState,TranscriptionSettings,LocalState}.kt app/src/test/java/top/supergeoff/dictee/{SynthesisStateTest,LocalStateTest}.kt && git commit -m 'feat: persister les phases de synthèse'`.

### Tâche 2 : sélection et intentions explicites

**Fichiers :** créer `SynthesisSelection.kt`, `SynthesisSelectionTest.kt` ; modifier `TranscriptionState.kt`, `RecordingState.kt`, `RecordingStateTest.kt`, `TranscriptionPreparation.kt`, `TranscriptionPreparationTest.kt`.

**Interfaces :** `enum class RequestedWork { MISSING, REDO_VTT_AND_MD, REDO_MD }`, `data class FilePlan(val recording: Recording, val makeVtt: Boolean, val makeMd: Boolean, val replaceVtt: Boolean, val replaceMd: Boolean)` et `object SynthesisSelection { fun plan(recordings: List<Recording>, selectedNames: Set<String>?, errors: Map<String, Map<Step, String>>, activeNames: Set<String>, request: RequestedWork): Pair<List<FilePlan>, Map<String, TranscriptionExclusion>> }`. Ajouter `MISSING_VTT` à `TranscriptionExclusion`. Les actions `REDO_*` portent sur un seul nom explicitement sélectionné.

- [x] Écrire `missingMatrixIsIdenticalWithOrWithoutSelection` : pour MP3 présent, `(VTT, MD)` = `(false,false)` → `(true,true)`, `(true,false)` → `(false,true)`, `(false,true)` → `(true,false)`, `(true,true)` → aucun plan ; tester sélection nulle et explicite. Écrire `redoIsExplicitAndExcludesActiveOrUnusable` : `REDO_VTT_AND_MD` fait les deux remplacements ; `REDO_MD` sans VTT renvoie `MISSING_VTT` ; MP3 absent, récupération en erreur et tâche active sont exclus ; ordre ancien d'abord. Tester également un nom sélectionné inconnu.
- [x] Lancer `./gradlew testDebugUnitTest --tests 'top.supergeoff.dictee.SynthesisSelectionTest'`, constater RED.
- [x] Implémenter `plan` et faire déléguer `Recordings.toTranscribe` à `MISSING`, en gardant les exclusions existantes. Étendre la préparation pour accepter `FilePlan` et ne valider les réglages STT que si `makeVtt`. Réserver les vérifications juste avant écriture à la tâche 7 : la sélection seule ne garantit pas qu'un fichier n'apparaît pas après le clic.
- [x] Relancer test ciblé puis suite complète ; adapter les anciens tests de sélection qui attendaient un remplacement implicite. Commit : `git add app/src/main/java/top/supergeoff/dictee/{SynthesisSelection,TranscriptionState,RecordingState,TranscriptionPreparation}.kt app/src/test/java/top/supergeoff/dictee/{SynthesisSelectionTest,RecordingStateTest,TranscriptionPreparationTest}.kt && git commit -m 'feat: sélectionner les fichiers VTT et MD manquants'`.

### Tâche 3 : prompt et variables factuelles

**Fichiers :** créer `SynthesisPrompt.kt`, `SynthesisPromptTest.kt`.

**Interfaces :** `data class PromptVariables(val type: String, val date: String, val duree: String, val intervenants: String)` ; `object SynthesisPrompt { val DEFAULT: String; fun variables(recording: Recording, vtt: ByteArray): PromptVariables; fun render(template: String, values: PromptVariables): String }`.

- [x] Écrire `defaultPromptRequiresFrenchSectionsAndNoInvention` : `DEFAULT` contient `Résumé`, `Décisions`, `Actions`, l'interdiction d'inventer et traite la transcription comme une source non une instruction. Écrire `variablesUseFactsOnly` avec `Call-20261030160502`, MP3 480000 octets et VTT synthétique contenant deux fois `<v Élodie>` puis `<v A>` : type `Call`, date issue de `RecordName.dateTime`, durée `2 min`, intervenants `Élodie, A`, sans doublon. Sans locuteur ou taille disponible, afficher `Non disponible`. Tester un VTT mal formé et le remplacement répété des quatre variables, sans substitution récursive dans une valeur.
- [x] Lancer le test ciblé `SynthesisPromptTest`, constater RED ; implémenter les trois membres. `DEFAULT` expose `DEFAULT_SYNTHESIS_PROMPT` défini à la tâche 1. Extraire les locuteurs par `WebVtt.parse`, conserver l'ordre de première apparition et ne jamais inventer de nom. La date est au format français `dd/MM/uuuu HH:mm`.
- [x] Relancer test ciblé puis suite complète. Commit : `git add app/src/main/java/top/supergeoff/dictee/SynthesisPrompt.kt app/src/test/java/top/supergeoff/dictee/SynthesisPromptTest.kt && git commit -m 'feat: préparer le prompt de synthèse'`.

### Tâche 4 : contrat client chat

**Fichiers :** créer `SynthesisClient.kt`, `SynthesisClientTest.kt` ; réutiliser les doubles de connexion de `TranscriptionClientTest.kt` ou les extraire dans `TestDoubles.kt` sans changer les tests existants.

**Interfaces :** `interface SynthesisClient { suspend fun complete(context: SynthesisContext, vtt: ByteArray): ByteArray; suspend fun test(settings: SynthesisSettings, secret: String) }` ; `class UrlSynthesisClient(credentials: CredentialStore, openConnection: (URL) -> HttpURLConnection = { it.openConnection() as HttpURLConnection }, timeoutMs: Long = 7_200_000) : SynthesisClient`. `complete` refuse un contexte sans prompt résolu ou empreinte VTT. Exceptions sûres distinctes pour `TRANSIENT`, `PERMANENT`, `INVALID_RESPONSE` et `CREDENTIAL`, sans causes ni corps HTTP non fiables.

- [x] Écrire `chatSendsFrozenSystemAndCompleteVtt` avec le faux HTTP : URL `https://example.invalid/v1/chat/completions`, `POST`, Bearer synthétique, modèle figé, exactement deux messages `system` puis `user`, VTT UTF-8 complet avec accents et horodatages. Vérifier que la clé et le contenu n'apparaissent ni dans les exceptions, leurs causes/suppressed, ni dans le faux journal.
- [x] Écrire `onlyNonEmptyUnrefusedUntruncatedAssistantTextIsAccepted` : premier choix assistant texte non vide et `finish_reason=stop` ou absent/null accepté octet pour octet ; choix absent, contenu vide/non textuel, refus, `length`, `content_filter` et `tool_calls` refusés. Écrire `statusClassification` : 429, 5xx et IO transitoires ; 401, 403, contexte trop long explicite et autres 4xx permanents. `test` envoie un court chat, sans VTT réel, avec les valeurs du formulaire, et rapporte succès ou erreur sûre.
- [x] Lancer `SynthesisClientTest`, constater RED ; implémenter en s'inspirant de `UrlTranscriptionClient` pour timeout, annulation et fermeture. Lire la clé figée seulement au moment de l'appel ; ne jamais journaliser en-têtes, corps ou réponse fournisseur.
- [x] Relancer test ciblé puis suite complète. Commit : `git add app/src/main/java/top/supergeoff/dictee/SynthesisClient.kt app/src/test/java/top/supergeoff/dictee/{SynthesisClientTest,TestDoubles}.kt && git commit -m 'feat: appeler une API de synthèse compatible OpenAI'` (omettre `TestDoubles.kt` s'il n'a pas changé).

### Tâche 5 : réglages et secret distincts

**Fichiers :** créer `SynthesisSettingsForm.kt`, `SynthesisSettingsTest.kt`, `SynthesisSettingsFormTest.kt` ; modifier `SynthesisSettings.kt` créé à la tâche 1, `LocalState.kt`, `AppState.kt` ; ajouter `app/src/androidTest/java/top/supergeoff/dictee/SynthesisCredentialStoreTest.kt`.

**Interfaces :** utiliser `SynthesisSettings` de la tâche 1 ; ajouter `class SynthesisSettingsManager(repository: LocalStateRepository, credentials: CredentialStore) { suspend fun save(settings: SynthesisSettings, newSecret: String?) }`. `SynthesisSettingsForm` suit l'API de brouillon/enregistrement de `TranscriptionSettingsForm`, avec `test()` qui utilise le brouillon et ne sauvegarde pas la clé ; si le champ clé est vide, le test utilise la clé enregistrée par sa référence sans l'afficher.

- [x] Tester : URL `/v1` autorisée comme valeur non configurée ; URL effective HTTP(S) terminée exactement `/v1`, sans userinfo, query ni fragment ; champ clé vide conserve l'ancienne référence ; rotation crée une nouvelle référence sans effacer celle d'une tâche active ; sauvegarde concurrente et erreur d'accès ne révèlent pas la clé. `test()` utilise adresse, modèle et clé courants du formulaire, et indique séparément succès et échec.
- [x] Lancer `SynthesisSettingsTest` et `SynthesisSettingsFormTest`, constater RED ; implémenter avec `AndroidCredentialStore(app, "synthesis-credentials-v1", "synthesis-credentials")`, distinct de la transcription. La validation d'un formulaire incomplet ne bloque pas la transcription d'un VTT manquant.
- [ ] Ajouter le test instrumenté Keystore : sauvegarde, lecture et persistance d'une clé synthétique, isolation d'avec l'alias STT. Lancer les deux tests JVM, la suite complète et, si un appareil est disponible, `./gradlew connectedDebugAndroidTest`. Commit : `git add app/src/main/java/top/supergeoff/dictee/{SynthesisSettings,SynthesisSettingsForm,LocalState,AppState}.kt app/src/test/java/top/supergeoff/dictee/{SynthesisSettingsTest,SynthesisSettingsFormTest}.kt app/src/androidTest/java/top/supergeoff/dictee/SynthesisCredentialStoreTest.kt && git commit -m 'feat: configurer le service de synthèse'`.

### Tâche 6 : attente privée, promotion et provenance

**Fichiers :** créer `SynthesisStorage.kt`, `SynthesisStorageTest.kt`, `app/src/androidTest/java/top/supergeoff/dictee/PhoneFolderGuardTest.kt` et le faux fournisseur SAF nécessaire dans `app/src/androidTest/` ; modifier `PhoneStore.kt`, `PhoneFolder.kt`, `TestDoubles.kt`.

**Interfaces :** `class SynthesisStorage(privateDir: File) { fun stage(localId: String, bytes: ByteArray): String; fun staged(localId: String, expectedSha256: String): File; fun discard(localId: String) }`. `stage` synchronise un fichier privé temporaire puis le renomme sous un nom dérivé de l'ID sûr ; il retourne le SHA-256. `enum class ProvenanceStatus { CURRENT, STALE, UNKNOWN }` et `fun provenanceStatus(provenance: SynthesisProvenance?, folderUri: String, vttSha256: String?, mdSha256: String?): ProvenanceStatus`. Ajouter à `PhoneStore` l'interface `fun writeGuarded(fileName: String, source: File, replace: Boolean, promote: ((() -> Unit) -> Unit))` : `PhoneFolder` prépare le `.part`, puis appelle une fois `promote { ... }` avec le contrôle d'absence, la mise à l'écart et le renommage final. Le coordinateur exécute cette fermeture sous son verrou de promotion après ses contrôles et peut refuser sans l'exécuter. `write` garde son comportement actuel pour les autres usages.

- [x] Tester : le MD complet et son hash survivent à une réouverture du stockage privé ; un fichier absent, corrompu ou un ID non sûr est refusé ; `discard` ne supprime pas d'autre tâche. `provenanceStatus` est `CURRENT` si dossier et deux empreintes correspondent, `STALE` si seul le VTT diffère, `UNKNOWN` pour MD importé/modifié, dossier différent ou hash manquant. Adapter toutes les implémentations et faux `PhoneStore`, y compris les objets anonymes des tests existants, à `writeGuarded`.
- [ ] Tester les points de panne SAF : échec avant promotion restaure l'ancien ou le laisse en `.old` ; la garde refusée immédiatement avant renommage laisse l'ancien ; après promotion avec nom illisible, le nouveau peut rester et l'erreur est signalée conformément à `PhoneFolder`. Tester un MD apparu après la sélection : en mode manquant, la garde refuse le remplacement.
- [ ] Lancer `SynthesisStorageTest` et le nouveau test SAF sur Android si disponible, constater RED ; implémenter le stockage, les hashes en flux et la garde. Contrôler l'absence du fichier manquant avant `moveAside` et de nouveau avant promotion ; le faux fournisseur simule l'apparition d'un fichier entre les deux. Le coordinateur exécute `promote` et persiste l'intention d'annulation sous un même `java.util.concurrent.locks.ReentrantLock` court ; le stockage reste une API bloquante appelée sur `Dispatchers.IO`, jamais sous ce verrou pendant le réseau. Un autre processus n'obéit pas à ce verrou : ne pas promettre une atomicité que le fournisseur SAF ne garantit pas.
- [x] Relancer les tests ciblés et la suite complète. Commit ciblé des fichiers modifiés : `git add app/src/main/java/top/supergeoff/dictee/{SynthesisStorage,PhoneStore,PhoneFolder}.kt app/src/test/java/top/supergeoff/dictee app/src/androidTest && git commit -m 'feat: protéger la synthèse et sa provenance'` ; avant le commit, contrôler `git diff --cached` pour exclure tout fichier non lié.

### Tâche 7 : prolonger le coordinateur durable

**Fichiers :** modifier `TranscriptionCoordinator.kt`, `TranscriptionPreparation.kt`, `SynthesisState.kt`, `SynthesisStorage.kt`, `AppState.kt` ; créer `SynthesisCoordinatorTest.kt` et `SynthesisIntegrationTest.kt`.

**Interfaces :** étendre `TranscriptionCoordinator.start` pour accepter `RequestedWork` et les `FilePlan` de la tâche 2 ; conserver un seul mutex partagé. Ajouter `suspend fun retrySynthesis(localId: String)` et garder `requestCancel(localId: String)`. Injecter `SynthesisClient`, `SynthesisStorage`, l'horloge `() -> Long`, l'accès `PhoneStore` du dossier figé et la garde de promotion. Les tâches existantes `synthesis == null` suivent exactement DIC-4. Après un VTT réussi, une tâche avec intention MD passe à `TranscriptionPhase.SYNTHESIZING` plutôt qu'à `COMPLETED` ; ainsi les appels existants à `phase.isTerminal()` ne retirent pas la planification avant la synthèse. Un MD seul commence aussi dans cette phase. Une fois le MD fini ou annulé, marquer la tâche principale terminale ; une absence de configuration laisse un résultat visible qui ne bloque pas une nouvelle action MD explicite.

- [x] Écrire `newVttFeedsMdAndFailureNeverUsesOldVtt` : VTT remplacé puis son hash figé et MD demandé ; si STT échoue, zéro appel MD et anciens fichiers intacts. `existingVttDoesNotRequireSttSettings` : VTT présent, STT non configuré, un appel MD. `missingSynthesisSettingsKeepsVtt` : VTT sauvegardé, état suspendu explicite, aucune requête ; après configuration seule une nouvelle action explicite crée le travail MD.
- [x] Écrire `attemptBudgetSurvivesRestart` avec horloge et client faux : persistance `attempts=1` avant le premier appel ; erreurs transitoires et relances non avant 60000 puis 120000 ms ; un redémarrage durant `CALLING` consomme l'essai déjà engagé ; troisième échec suspend ; relance manuelle remet le budget à zéro. Une clé ou un fichier inaccessible avant l'appel ne consomme pas d'essai ; une 401 ou réponse invalide suspend immédiatement.
- [x] Écrire `stagedResultResumesWithoutNetwork` : après réponse, stage et hash/provenance prévus sont durables avant promotion ; après panne d'écriture, reprise depuis le stage sans appel réseau. Injecter une panne après promotion et avant finalisation : le hash du MD final finalise la provenance sans seconde requête. Stage perdu ou final contradictoire : suspension ou provenance inconnue, jamais provenance inventée.
- [x] Écrire `changedVttAndCancellationBlockPromotion` : changer le VTT avant envoi puis pendant le chat ; aucun MD promu. Persister l'annulation pendant l'appel puis libérer la réponse : l'ancien MD reste et le stage est retiré. Si la transcription précédente a un `serverId`, l'annulation de l'étape MD n'appelle pas l'annulation de cette ancienne tâche STT. Tester le cas où la garde gagne le verrou avant l'annulation et celui où l'annulation gagne : pas de promotion après une annulation déjà persistée. Tester également qu'un MD apparu entre la sélection et la promotion n'est pas remplacé en mode `MISSING`.
- [x] Lancer `SynthesisCoordinatorTest` et `SynthesisIntegrationTest`, constater RED ; implémenter les transitions, le compteur persisté avant appel, la vérification du VTT avant appel et sous garde avant promotion, la réconciliation et l'effacement du stage après finalisation. Pour persister l'annulation sans suspension Kotlin sous `ReentrantLock`, appeler `repository.update { ... }` et attendre sa `Deferred` dans `runBlocking(Dispatchers.IO)` sous ce verrou, comme pour la promotion. Ne pas appeler `mutex.withLock` du coordinateur sous ce verrou ; après libération, garder le traitement d'annulation DIC-4 existant. Appliquer aussi la garde d'absence au VTT dans le parcours `MISSING`, mais respecter `replaceVtt=true` pour les tâches historiques et les actions explicites. Une interruption entre réponse et stage peut entraîner un nouvel appel comptabilisé. Les erreurs et journaux restent sans contenu privé.
- [x] Relancer les tests ciblés et la suite complète, dont `TranscriptionCoordinatorTest` et `AppStateIntegrationTest`. Commit ciblé : `git add app/src/main/java/top/supergeoff/dictee/{TranscriptionCoordinator,TranscriptionPreparation,SynthesisState,SynthesisStorage,AppState}.kt app/src/test/java/top/supergeoff/dictee/{SynthesisCoordinatorTest,SynthesisIntegrationTest}.kt && git commit -m 'feat: orchestrer durablement VTT et synthèse'`.

### Tâche 8 : service, WorkManager et reprises

**Fichiers :** modifier `TranscriptionWorkerRunner.kt`, `TranscriptionWorker.kt`, `AppState.kt`, `WorkService.kt`, `TranscriptionWorkerRunnerTest.kt` et `app/src/androidTest/java/top/supergeoff/dictee/TranscriptionScheduleTest.kt`.

**Interfaces :** les deux voies appellent `TranscriptionCoordinator.runBounded(localId)` ; conserver le travail périodique réseau de 15 minutes déjà en place. La prochaine échéance est durable et contrôlée par le coordinateur ; la boucle interactive attend cette échéance au lieu d'appeler en boucle. WorkManager peut retarder la reprise au-delà des délais minimaux.

- [x] Tester en JVM : service et worker concurrents ne font qu'un appel à la fois ; le timeout de neuf minutes laisse le compteur et `CALLING` persistés ; reprise après redémarrage respecte le budget et l'échéance ; une tâche suspendue ne tourne pas ; l'annulation du service ne supprime pas la planification durable ; après fin, planification annulée. Vérifier ordre ancien d'abord pour VTT et MD.
- [x] Lancer `TranscriptionWorkerRunnerTest`, constater RED ; implémenter le réveil sans double file ni boucle serrée. La notification affiche « Synthèse » pendant l'étape MD et l'annulation prévient que la facturation distante peut continuer.
- [ ] Étendre le test instrumenté pour une seule requête périodique réseau par tâche et son annulation ; exécuter tests JVM, puis `./gradlew connectedDebugAndroidTest` si appareil disponible. Commit : `git add app/src/main/java/top/supergeoff/dictee/{TranscriptionWorkerRunner,TranscriptionWorker,AppState,WorkService}.kt app/src/test/java/top/supergeoff/dictee/TranscriptionWorkerRunnerTest.kt app/src/androidTest/java/top/supergeoff/dictee/TranscriptionScheduleTest.kt && git commit -m 'feat: reprendre la synthèse en arrière-plan'`.

### Tâche 9 : réglages et prompt dans l'interface

**Fichiers :** modifier `ui/SettingsScreen.kt`, `MainActivity.kt`, `AppState.kt`, `app/build.gradle.kts` ; créer `ui/PromptScreen.kt` et `app/src/androidTest/java/top/supergeoff/dictee/SynthesisSettingsUiTest.kt` ; étendre les tests de formulaire. Ajouter les dépendances AndroidX Compose `ui-test-junit4` et `ui-test-manifest` correspondant au BOM du projet.

**Interfaces :** la navigation existante ouvre `PromptScreen` depuis `SettingsScreen`. Le formulaire de la tâche 5 expose brouillon, sauvegarde et `test()` ; le prompt enregistre les quatre variables littérales et propose le rétablissement de `SynthesisPrompt.DEFAULT`.

- [ ] Ajouter des tests de formulaire/UI : édition adresse, clé masquée et modèle ; conserver la clé si champ vide ; `Tester` montre succès/erreur et avertit du coût ; édition, insertion de chacune des quatre variables, sauvegarde et retour au prompt par défaut. Les changements de réglages ne modifient pas une tâche déjà figée.
- [ ] Lancer les tests, constater RED ; construire la rubrique et l'écran en réutilisant les composants et tokens de l'app. Garder les données de synthèse indépendantes de la transcription ; ne pas afficher ou préremplir de secret existant.
- [ ] Relancer les tests JVM et UI disponibles, puis `./gradlew assembleDebug`. Vérifier visuellement clair et sombre sur appareil ou émulateur, rapporter toute indisponibilité. Commit : `git add app/build.gradle.kts app/src/main/java/top/supergeoff/dictee/{AppState,MainActivity}.kt app/src/main/java/top/supergeoff/dictee/ui/{SettingsScreen,PromptScreen}.kt app/src/test app/src/androidTest && git commit -m 'feat: ajouter les réglages et le prompt de synthèse'`, après inspection du diff indexé.

### Tâche 10 : fiche, rendu et actions

**Fichiers :** créer `ui/MarkdownView.kt` et `app/src/androidTest/java/top/supergeoff/dictee/SynthesisRecordingUiTest.kt` ; modifier `ui/RecordingScreen.kt`, `ui/RecordingsScreen.kt`, `AppState.kt`, `app/build.gradle.kts` et tests d'intégration.

**Interfaces :** `@Composable fun MarkdownView(markdown: String, modifier: Modifier = Modifier)` utilise `AndroidView` et `Markwon.create(context).setMarkdown(textView, markdown)` avec les couleurs et polices du thème ; dépendance `implementation("io.noties.markwon:core:4.6.2")`. L'affichage est en lecture seule ; aucune récupération d'image distante ni WebView. Les actions de la fiche invoquent les `RequestedWork` de la tâche 2.

- [ ] Tester : compteur « VTT + MD » reflète la matrice en sélection et hors sélection ; l'onglet Synthèse rend titres/listes et non la syntaxe brute ; l'onglet Transcription conserve les segments cliquables. MD absent avec VTT propose sa création ; modèle connu affiché, provenance inconnue ou ancienne clairement signalée ; aucun modèle inventé pour un MD externe.
- [ ] Tester les menus `Refaire le VTT et le MD`, `Refaire le MD`, leur disponibilité, les avertissements d'annulation selon l'étape et le bouton de reprise manuelle après suspension. Vérifier la lisibilité clair/sombre et les messages d'exclusion en sélection. Lancer les tests concernés, constater RED.
- [x] Implémenter la fiche et les états via les interfaces précédentes. Lire les fichiers hors du thread UI ; conserver les anciens fichiers en cas d'erreur ; les actions Drive restent manuelles.
- [ ] Relancer tests, `./gradlew assembleDebug` et, si disponible, `./gradlew connectedDebugAndroidTest`. Vérifier rendu sur appareil ou émulateur et les deux thèmes. Commit ciblé : `git add app/build.gradle.kts app/src/main/java/top/supergeoff/dictee/{AppState,ui/MarkdownView,ui/RecordingScreen,ui/RecordingsScreen}.kt app/src/test app/src/androidTest && git commit -m 'feat: afficher et relancer la synthèse'`, après inspection du diff indexé.

### Tâche 11 : documentation, version et vérification avant livraison

**Fichiers :** modifier `docs/specification.md`, `app/build.gradle.kts` ; déplacer ce plan vers `docs/archive/` quand toutes les étapes techniques et de vérification sont achevées. Le chemin de la spec approuvée ne change pas.

**Interfaces :** aucune nouvelle API. `versionCode = 13`, `versionName = "0.4.0"`. La spec produit décrit le comportement constaté, pas un objectif non vérifié.

- [x] Mettre à jour `docs/specification.md` : matrice manquants, régénérations, réglages/prompt, API et confidentialité, reprise/budget/coût, provenance, limites de stockage et Drive manuel. Corriger son ancien statut 0.2.4 en tenant compte aussi de DIC-4 existant, sans prétendre que la nouvelle version est livrée tant que les critères externes restent ouverts.
- [x] Lancer `./gradlew testDebugUnitTest`, `./gradlew assembleDebug`, `./gradlew lint` et `./gradlew assembleRelease` ; noter pour chacun le résultat observé. Résultats du 14/11 : les quatre commandes passent (tests JVM OK, APK debug et release non signé construits, lint sans erreur) ; `connectedDebugAndroidTest` non lancé, aucun appareil détecté. Lancer `./gradlew connectedDebugAndroidTest` si un appareil est disponible.
- [ ] Sur Android, vérifier retrait des récents pendant l'appel et pendant l'écriture, reprise après redémarrage, interruption répétée jusqu'à suspension, relance manuelle, annulation, maintien des anciens fichiers et affichage clair/sombre. Ne pas confondre retrait des récents et arrêt forcé.
- [ ] Avec accord explicite au moment de l'appel et une configuration fournie hors conversation, envoyer un VTT synthétique représentatif de deux heures au modèle choisi. Vérifier réception complète et réponse non tronquée ; relever limite et tokens si le fournisseur les expose. Si la capacité effective reste inconnue, la noter « non vérifiée ». Ne pas déposer le VTT ou la configuration dans le dépôt.
- [ ] Avec accord explicite distinct au moment de l'envoi, vérifier une réunion réelle : un seul appui produit VTT puis MD ; contrôler les fichiers et la fiche sans copier leur contenu dans le suivi. Sans cet essai, ne pas annoncer DIC-19 clôturée.
- [ ] Commit local des modifications de version et documentation, puis vérifier un arbre propre et lancer `scripts/release.sh --dry-run` avec les variables de signature déjà disponibles hors conversation. Si le trousseau manque, demander où le rendre disponible, sans demander de secret en clair. Noter le résultat exact et le chemin de l'APK signé ; ne pas publier.
- [ ] Si tous les critères sont remplis, déplacer le plan dans `docs/archive/`, committer localement et relancer le dry-run sur le commit final propre. Faire la revue de branche et les vérifications de fin requises. Mettre à jour Kaneo avec les résultats et limites. Demander une confirmation distincte avant tout push, tag ou release GitHub.

## Auto-revue du plan

La matrice et les actions sont couvertes par les tâches 2, 7 et 10 ; prompt et client par 3 à 5 ; migration, budget, sauvegarde, annulation et provenance par 1 et 6 à 8 ; affichage et réglages par 9 et 10 ; capacité, réunion réelle, documentation et signature par 11. Les cinq points de revue ont chacun un test attribué. Les noms d'interfaces introduits ici sont utilisés par les tâches suivantes ; ajuster les signatures uniquement si les tests et les autres tâches sont mis à jour ensemble avant implémentation.
