# DIC-20 : enchaînement automatique Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [x]`) syntax for tracking.

**Goal:** Produire les VTT et MD manquants après chaque récupération réussie lorsque l'option est active, sans autre appui et sans envoi Drive.

**Architecture:** Une intention durable par récupération alimente la file de transcription et synthèse existante. Le verrou de transfert est pris par enregistrement, puis libéré avant sa programmation ; la reprise consomme les intentions validées sans chercher les autres MP3. Les règles de traitement MISSING et les workers existants restent la seule file d'exécution.

**Tech Stack:** Kotlin, coroutines, Jetpack Compose, WorkManager, JSON local, JUnit ; aucune nouvelle dépendance.

**Spec:** `docs/superpowers/specs/2026-11-15-dic20-enchainement-design.md` (approuvée le 15 novembre 2026).

## Global Constraints

- Le réglage est désactivé par défaut ; son activation ne traite jamais les MP3 déjà présents.
- La désactivation empêche les prochaines demandes automatiques sans annuler celles déjà programmées.
- La valeur est capturée au début de chaque récupération individuelle.
- Drive reste manuel ; les trois boutons et les quatre étapes existants sont conservés.
- Une transcription ou une synthèse s'exécute à la fois ; seuls les fichiers manquants sont produits.
- Une interruption de récupération Bluetooth ne déclenche pas de transcription ni de récupération automatique.
- Une synthèse non configurée garde la reprise explicite existante après production du VTT.
- La version cible est 0.5.0, versionCode 16 (base observée : 0.4.2, code 15).
- Aucun push, fusion, appel réel aux services ou publication sans accord séparé.
- Chaque tâche suit RED, GREEN, REFACTOR, vérification, puis commit local ciblé. Ne jamais ajouter globalement les fichiers non suivis préexistants.
- Avant l'exécution, créer le worktree avec using-git-worktrees, y reporter seulement la spec et ce plan, puis vérifier la base par testDebugUnitTest. À la fin, déplacer ce plan terminé dans docs/archive/.

## Review Focus

1. Un arrêt après création durable de la tâche mais avant consommation de l'intention ne crée pas de deuxième traitement (tâche 2).
2. Un changement de dossier téléphone ne redirige pas une ancienne demande vers un autre MP3 de même nom (tâches 1 et 2).
3. Une erreur de sauvegarde après écriture physique du MP3 n'autorise pas son traitement prématuré (tâche 3).
4. Une programmation automatique pendant la récupération du suivant ne cause ni interblocage ni attente de tout le lot (tâche 3).
5. Un refus de notifications ne casse pas la file ; un succès VTT seul n'est pas annoncé comme VTT + MD réussi (tâche 5).

## Fichiers et responsabilités

Tous les chemins Kotlin ci-dessous ont pour racine `app/src/main/java/top/supergeoff/dictee/` (M), `app/src/test/java/top/supergeoff/dictee/` (T) ou `app/src/androidTest/java/top/supergeoff/dictee/` (A).

- Créer M/AutomaticProcessing.kt : types et transitions pures des intentions, puis orchestration de leur consommation.
- Modifier M/LocalState.kt : persistance du réglage et des intentions, garde de récupération.
- Modifier M/TranscriptionCoordinator.kt : entrée automatique atomique dans la file, sans changer l'entrée manuelle.
- Modifier M/RecoveryJob.kt et M/AppState.kt : verrou par transfert, raccord au résultat, réconciliation et réveil après réglages.
- Modifier M/RecordingPipeline.kt et M/ui/SettingsScreen.kt : attente de configuration et réglage, sans refonte.
- Créer M/ProcessingNotifications.kt : décision testable et adaptateur Android de notification de résultat.
- Modifier M/DicteeApp.kt si nécessaire pour partager l'adaptateur entre service et worker ; M/TranscriptionCoordinator.kt pour observer les transitions de résultat.
- Adapter les tests existants et créer les tests indiqués dans chaque tâche.

## Tâche 1 : conserver le réglage et les intentions

**Fichiers :** M/AutomaticProcessing.kt (nouveau), M/LocalState.kt ; T/AutomaticProcessingTest.kt (nouveau), T/LocalStateTest.kt.

**Interfaces :**
- `enum class AutomaticProcessingPhase { RECOVERING, READY, WAITING_SETTINGS }`.
- `data class AutomaticProcessingRequest(val id: String, val recordingName: String, val folderUri: String, val phase: AutomaticProcessingPhase, val reason: String? = null)`.
- `LocalState.autoProcessAfterRecovery: Boolean = false` et `LocalState.automaticProcessingRequests: List<AutomaticProcessingRequest> = emptyList()`.
- `fun LocalState.beginAutomaticRecovery(name: String, folderUri: String, id: String): LocalState` capture le booléen actuel : si actif, crée/remplace l'intention de ce nom et dossier en RECOVERING ; sinon retire une intention RECOVERING du même fichier sans toucher à une demande déjà validée.
- `fun LocalState.finishAutomaticRecovery(result: RecoveryResult, folderUri: String): LocalState` passe une intention RECOVERING à READY sur succès et la retire sur échec. Ne crée jamais d'intention absente.

- [x] Écrire les tests rouges : ancien JSON v5 => option false et liste vide ; aller-retour des trois phases ; option false => aucune intention ; capture true puis désactivation => succès READY ; capture false puis activation => aucun traitement ; échec => aucune demande programmable. Assert : les intentions d'un autre dossier restent inchangées.
- [x] Exécuter `./gradlew testDebugUnitTest --tests '*AutomaticProcessingTest' --tests '*LocalStateTest'` et constater l'échec pour les nouveaux contrats.
- [x] Implémenter ces transitions pures et le JSON v6. Accepter les anciennes versions prises en charge, conserver les contrôles de sécurité et de contexte existants ; les URI sont des données locales, jamais des constantes commitées.
- [x] Rejouer la commande jusqu'au succès, puis relire le diff.
- [x] Commit local : `feat: persist automatic processing intentions` avec uniquement les fichiers de cette tâche.

## Tâche 2 : consommer les intentions dans la file existante

**Fichiers :** M/AutomaticProcessing.kt, M/TranscriptionCoordinator.kt ; T/AutomaticProcessingTest.kt, T/TranscriptionCoordinatorTest.kt, T/SynthesisCoordinatorTest.kt.

**Interfaces :**
- Ajouter `suspend fun TranscriptionCoordinator.startAutomatic(requestId: String): List<String>`.
- Ajouter `class AutomaticProcessingCoordinator(private val repository: LocalStateRepository, private val transcription: TranscriptionCoordinator)` et `suspend fun reconcile(): Unit` ; sérialiser ses appels concurrents par Mutex.
- `startAutomatic` utilise RequestedWork.MISSING et le contexte du dossier de la demande. Si le dossier courant diffère, garder WAITING_SETTINGS avec raison « Le dossier du téléphone a changé. » ; aucun fichier n'est ouvert dans le nouveau dossier pour cette demande.
- La demande READY ou WAITING_SETTINGS est consommée dans la même écriture durable que l'ajout de sa tâche. Utiliser request.id comme localId de la tâche ; une tâche existante portant cet identifiant permet de consommer sans recréer, même si elle est déjà terminale.

- [x] Écrire les tests rouges : une demande READY produit une seule tâche ; RECOVERING ne produit rien ; deux réconciliations simultanées et une reprise après création ne dupliquent pas ; un traitement manuel déjà actif du même nom et dossier satisfait la demande sans ajout ; VTT et MD déjà présents consomment sans tâche ; VTT présent ne retranscrit pas ; aucun appel Drive n'existe dans le chemin.
- [x] Ajouter les tests : transcription non configurée => WAITING_SETTINGS et zéro tâche, puis configuration corrigée => une tâche ; erreur isolée de préparation n'empêche pas la demande suivante ; dossier changé => aucun appel de préparation ; synthèse non configurée => VTT autorisé et politique MD existante conservée.
- [x] Exécuter `./gradlew testDebugUnitTest --tests '*AutomaticProcessingTest' --tests '*TranscriptionCoordinatorTest' --tests '*SynthesisCoordinatorTest'` et constater le rouge.
- [x] Implémenter l'entrée automatique en réutilisant préparation, déduplication, écriture durable et programmation existantes. Ne pas appeler start puis retirer la demande dans deux écritures séparées. Après erreur de programmation, garder un état récupérable ; reconcile() existant doit reprogrammer toute tâche persistée. Ne pas avaler CancellationException. Ne pas attendre le verrou de transfert en tenant le mutex de file.
- [x] Rejouer les tests ; vérifier que l'entrée manuelle start conserve tous ses tests.
- [x] Commit local : `feat: enqueue recovered recordings durably`.

## Tâche 3 : raccorder chaque récupération sans verrou global de lot

**Fichiers :** M/RecoveryJob.kt, M/LocalState.kt (RecoverySafety), M/AppState.kt, M/AutomaticProcessing.kt ; T/RecoveryJobTest.kt, T/AppStateIntegrationTest.kt, T/AutomaticProcessingTest.kt.

**Interfaces :**
- Ajouter en dernier paramètre de `RecoveryJob.run` : `withRecoveryLock: suspend (suspend () -> RecoveryResult) -> RecoveryResult = { block -> block() }`.
- Pour chaque entrée, appeler beforeRecovery et recover à l'intérieur de cette garde, puis onResult après sa libération. Conserver tri, rang i/total, callbacks et stopsQueue.
- Ajouter `suspend fun RecoverySafety.begin(name: String, folderUri: String)` et `suspend fun RecoverySafety.finish(result: RecoveryResult, folderUri: String)` ; conserver les signatures existantes pour leurs usages actuels. Le begin durabilise protection et intention ensemble. Le finish durabilise protection levée et intention READY ensemble.
- AppModel possède une instance paresseuse de AutomaticProcessingCoordinator. Initialisation : réconcilier la file existante puis les demandes automatiques. Après chaque résultat : finish durable, readPhone, puis réconciliation automatique indépendante de l'erreur Bluetooth.

- [x] Écrire un test où le premier MP3 passe en file alors que le second transfert est retenu par une barrière. Avec withTimeout, prouver que le premier traitement progresse, que le second ne programme rien avant succès et que les pourcentages restent i/total.
- [x] Écrire les tests : premier résultat échoué non fatal puis second réussi ; erreur de programmation du premier sans arrêt du lot ; sauvegarde du succès impossible => protection conservée et zéro tâche ; arrêt pendant RECOVERING => aucune transcription au redémarrage ; arrêt après READY => une tâche après réconciliation.
- [x] Exécuter `./gradlew testDebugUnitTest --tests '*RecoveryJobTest' --tests '*AppStateIntegrationTest' --tests '*AutomaticProcessingTest'` et constater le rouge.
- [x] Retirer le verrou qui entoure tout runRecovery dans AppModel. Passer recoverySafety.withTransferLock au nouveau paramètre. Capturer le réglage dans begin pour chaque entrée, pas une fois par lot. Protéger encore la promotion du MP3 et vérifier à l'intérieur du verrou qu'aucune tâche active ne l'utilise, car la liste sélectionnée a pu vieillir depuis recoveryEntries.
- [x] Garder toutes les protections d'échec de stockage et la fermeture Bluetooth existantes. Une annulation traverse les callbacks ; une erreur de programmation est journalisée sans devenir un échec de récupération. Réconcilier les demandes validées à l'initialisation et après nouvelle récupération, sans scan rétroactif.
- [x] Rejouer les tests et les tests de garde mémoire `./gradlew testDebugUnitTest --tests '*MemoryStoreGuardTest'` ; tous doivent passer.
- [x] Commit local : `feat: chain successful recoveries into processing`.

## Tâche 4 : exposer l'option et les attentes de configuration

**Fichiers :** M/AppState.kt, M/ui/SettingsScreen.kt, M/RecordingPipeline.kt ; T/RecordingPipelineTest.kt ; A/AutomaticProcessingSettingsUiTest.kt (nouveau).

**Interfaces :** `fun AppModel.setAutoProcessAfterRecovery(enabled: Boolean): Unit` ne fait que persister la préférence. Le changement de préférence ne réconcilie pas les MP3. Après enregistrement réussi de la configuration de transcription, AppModel appelle la réconciliation automatique ; ne pas reprogrammer sur chaque frappe du formulaire.

- [x] Écrire un test de présentation : une intention WAITING_SETTINGS sans tâche active donne à l'étape VTT le statut WAITING, label « À configurer », raison conservée, bouton OPEN_SETTINGS ; aucune priorité sur une récupération en cours ni sur une tâche active existante. Le dossier changé affiche sa propre raison, sans accuser les identifiants du serveur.
- [x] Écrire un test Compose : texte « Créer le VTT et le MD après récupération », désactivé initialement, activation puis relecture persistante, explication exacte de la spec ; les boutons de liste et fiche restent inchangés. Vérifier le comportement clair/sombre via les jetons existants, sans nouvelles couleurs.
- [x] Exécuter `./gradlew testDebugUnitTest --tests '*RecordingPipelineTest'` pour le rouge, et le test Compose sur appareil/émulateur s'il est disponible. Sans appareil, compiler les tests avec assembleDebugAndroidTest et signaler que l'exécution UI reste non vérifiée.
- [x] Ajouter le réglage dans les rubriques existantes et le raccord d'enregistrement de configuration. Ajouter la présentation des intentions d'attente dans RecordingPipeline.calculate, sans nouvel état de tâche ni bouton de fiche.
- [x] Rejouer les tests unitaires et `./gradlew assembleDebugAndroidTest`. Si disponible : `./gradlew connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=top.supergeoff.dictee.AutomaticProcessingSettingsUiTest`.
- [x] Commit local : `feat: expose automatic processing preference`.

## Tâche 5 : notifier les résultats sans dépendre de l'écran ouvert

**Fichiers :** M/ProcessingNotifications.kt (nouveau), M/TranscriptionCoordinator.kt, M/AppState.kt ; T/ProcessingNotificationsTest.kt (nouveau), T/TranscriptionWorkerRunnerTest.kt ; A/ProcessingNotificationsTest.kt (nouveau).

**Interfaces :**
- `data class ProcessingResultNotice(val taskId: String, val title: String, val text: String)`.
- `fun processingResultNotice(previous: TranscriptionTask, next: TranscriptionTask): ProcessingResultNotice?` : retourne une notification seulement lorsqu'une transition nouvelle atteint réussite VTT + MD, échec ou suspension avec raison. Aucun succès global si le MD manque.
- `class ProcessingNotifications(context: Context)` avec `fun show(notice: ProcessingResultNotice): Unit` ; canal de résultats distinct de la notification de service en cours, identifiant stable dérivé de taskId.
- Ajouter à TranscriptionCoordinator le callback facultatif `onProcessingResult: (ProcessingResultNotice) -> Unit = {}`. Le déclencher après écriture durable d'une transition, pas lors de simple relecture. L'instance de coordinator partagée par AppModel et WorkManager utilise le même adaptateur.

- [x] Écrire les tests : VTT terminé avec MD encore en attente => null ; MD sauvegardé => notification de succès ; échec/suspension => raison ; transition inchangée => null ; annulation volontaire => pas de succès ni fausse erreur. Les textes ne contiennent ni clé, ni prompt, ni contenu de réunion.
- [x] Ajouter un test de worker : sans écran/service interactif, la transition MD réussie appelle le callback ; un callback défaillant ne change ni l'état terminal ni l'avancement des tâches suivantes.
- [x] Exécuter `./gradlew testDebugUnitTest --tests '*ProcessingNotificationsTest' --tests '*TranscriptionWorkerRunnerTest'` et constater le rouge.
- [x] Implémenter les décisions pures puis l'adaptateur Android. Texte de succès : « VTT et MD créés. » ; titre « Traitement terminé ». Une erreur utilise « Traitement interrompu » et sa raison. Respecter POST_NOTIFICATIONS et intercepter SecurityException, sans bloquer la file. Un identifiant stable remplace une notification rejouée au lieu d'en empiler plusieurs.
- [x] Vérifier en instrumentation le refus de permission et la conservation de l'état consultable. Rejouer les tests unitaires et assembleDebugAndroidTest ; noter explicitement les contrôles appareil indisponibles.
- [x] Commit local : `feat: notify processing completion and errors`.

## Tâche 6 : contrôler le parcours et préparer la livraison 0.5.0

**Fichiers :** T/AutomaticProcessingIntegrationTest.kt (nouveau), `app/build.gradle.kts`, `docs/specification.md`, ce plan.

**Interfaces :** aucune nouvelle interface produit. Réutiliser FakeLink, PhoneStore synthétique, faux clients réseau et LocalStateStore existants ; aucune donnée réelle dans les fixtures.

- [x] Écrire un test de bout en bout local avant les ajustements : lot de deux récupérations avec option active => deux MP3, deux VTT et deux MD ; premier traitement en erreur => second traité ; reprise depuis état sauvegardé => aucun doublon ; option coupée => MP3 seuls ; compteur des appels Drive toujours zéro.
- [x] Exécuter `./gradlew testDebugUnitTest --tests '*AutomaticProcessingIntegrationTest'`. S'il révèle un défaut, appliquer systematic-debugging puis corriger au plus près du contrat ; aucun élargissement de périmètre.
- [x] Mettre versionName à 0.5.0 et versionCode à 16 ; adapter si la base a avancé avant exécution, en conservant un code strictement croissant. Mettre docs/specification.md en accord avec l'option, la non-rétroactivité, les reprises et les notifications ; ne pas annoncer de validation sur téléphone non réalisée.
- [x] Exécuter `./gradlew testDebugUnitTest assembleDebug lint assembleDebugAndroidTest` ; attendu BUILD SUCCESSFUL pour tous. Lire les rapports de tests et lint, ne pas se contenter d'un ancien artefact APK.
- [x] Faire une revue complète du diff après les revues par tâche selon la méthode choisie. Corriger et rejouer les tests concernés. Consigner dans la carte DIC-20 les étapes franchies, écarts et contrôles non réalisés, sans fichier d'état supplémentaire.
- [ ] Proposer la recette téléphone : option off, option on avec lot, retrait des récents, redémarrage avec tâche en file, erreur d'un fichier, refus des notifications, désactivation en cours, MP3 antérieurs non traités et absence de Drive automatique. Attendre les observations de Geoff avant d'affirmer la recette réussie.
- [x] Commit local ciblé : `feat: prepare DIC-20 version 0.5.0`. Déplacer ce plan dans docs/archive/ une fois terminé. Demander séparément l'accord pour push/fusion et publication de la release signée ; ne pas les exécuter dans cette tâche sans cet accord.

## Auto-revue du plan

La spec est couverte par les tâches 1 (persistance et capture), 2 (file et configuration), 3 (récupération et interruptions), 4 (présentation), 5 (notifications), 6 (parcours et livraison). Les cinq conditions de Review Focus ont chacune leurs tests. Les signatures de begin/finish existantes restent disponibles pour ne pas casser les tests et les usages manuels. Aucun changement du protocole du boîtier ni de dictee-server n'est prévu.

## Résultat de l'exécution

Geoff a approuvé le plan et choisi Terra ou Luna au premier plan. L'implémentation et les revues sont terminées sur feature/dic-20-enchainement, jusqu'au commit 94402eb. Les cases de tests Android désignent leur écriture et compilation lorsque l'appareil était absent, jamais leur exécution.

Vérification finale du contrôleur : testDebugUnitTest rejoué avec --rerun-tasks, 461 tests, aucun échec ni erreur ; assembleDebug, lint et assembleDebugAndroidTest réussis. Lint comporte dix avertissements. Après disparition du daemon pendant lint, le contrôle a réussi avec --max-workers=1 --no-daemon et une heap de 512 Mo, sans modification du dépôt.

Les ajustements issus des revues sont intégrés : préparation automatique avec dossier capturé et résultat explicite, validation durable sous garde par transfert, suppression du verrou global de soumission qui bloquait la progression pendant le lot, destinations de réglages adaptées et notifications identifiées jusqu'à l'année et la seconde. Les régressions correspondantes sont couvertes.

La recette sur téléphone reste à réaliser, dont notifications système, arrêt/reprise et absence d'envoi Drive. Le chemin automatique n'appelle pas Drive à la lecture du code, mais aucun espion Drive n'est raccordé à AppModel dans le test JVM. Ces limites ne sont pas des validations réussies. Aucun APK release signé n'a été construit ou publié. Aucun push ni fusion n'a eu lieu. La carte DIC-20 garde la recette et la livraison comme suite.

## Correctif du suivi interactif

Le diagnostic ultérieur a montré que la programmation automatique démarrait le service sans demander la boucle de suivi ; le worker périodique pouvait ainsi laisser attendre un résultat déjà prêt. Le correctif raccorde la création automatique et la reprise au premier plan à la boucle unique existante, avec comptabilisation du service. Les tests JVM couvrent la création après récupération et la reprise d'une tâche serveur sans nouvel envoi, les demandes simultanées et l'équilibre des travaux. La recette sur téléphone reste nécessaire.

