# Réglages et glossaire complémentaire Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Séparer les rubriques de réglages et ajouter des bulles de vocabulaire envoyées dans le champ standard `prompt`.

**Architecture:** Le formulaire conserve un brouillon de glossaire, représenté durablement par une chaîne unique. Les tâches figent cette chaîne avant tout appel réseau. La navigation affiche une seule rubrique par écran et réutilise les composants du design system.

**Tech Stack:** Kotlin, Jetpack Compose, StateFlow, JUnit, tests Compose, Gradle. Aucune dépendance nouvelle.

**Spec:** `docs/superpowers/specs/2026-11-18-reglages-glossaire-design.md` (approuvée).

## Global Constraints

- Le glossaire appartient uniquement à la transcription, jamais à la synthèse.
- Au plus 500 lignes et 20 000 caractères Unicode ; compter les points de code, pas les unités UTF-16.
- Aucun champ propriétaire, aucune détection du serveur et aucune modification du glossaire distant.
- La chaîne des bulles est transmise exactement, jointe par `\n`, sans préambule. Un champ vide est omis.
- Préserver les doublons, les espaces des lignes non vides, les accents et les variantes. Ignorer les lignes blanches à la validation de saisie.
- Ne pas ajouter de client Realtime, de fallback silencieux sans glossaire ni de refonte du transport existant.
- Les anciens états et tâches ont un glossaire vide ; les tâches actives gardent leur contexte figé.
- Utiliser les composants, couleurs, styles et tokens existants, sans contrôles Material bruts dans le formulaire de transcription.
- Aucun changement des versions, secrets ou fichiers non liés au chantier.

## Review Focus

- Un collage CRLF doit produire des séparateurs `\n` sans conserver de `\r` dans les bulles (tâche 1).
- Deux bulles identiques doivent rester distinctes : retirer la seconde ne retire pas la première (tâche 1).
- Un échec de sauvegarde doit conserver les entrées et la saisie, sans annoncer un succès (tâche 2).
- Une tâche relue après changement des réglages doit envoyer son ancien glossaire (tâche 3).
- Un aller-retour par le prompt ou les dossiers Drive doit revenir à la rubrique d’origine, sans perte du brouillon (tâche 4).

## Résultat d’exécution

Le code et les tests ont été implémentés dans `feature/reglages-glossaire`. Les tests unitaires, `assembleDebug`, `lint` et `compileDebugAndroidTestKotlin` ont passé après la revue indépendante. Les cases ci-dessous décrivent la procédure originale ; les preuves d’exécution sont les commits et les résultats de commande, pas une recette UI supposée.

La revue a relevé que le test du menu ne couvrait pas le routage de l’activité. Le routage et les opérations de pile utilisés par `MainActivity` ont donc été extraits dans `SettingsRoutes.kt`, avec des tests unitaires écrits et exécutés en échec avant implémentation, puis réussis. Ils couvrent les accès directs, les correspondances des rubriques, le retour Prompt/Drive et les callbacks périmés.

La validation UI comportementale et visuelle reste à effectuer : aucun appareil Android n’est connecté. Les tests Compose sont compilés mais n’ont pas été exécutés. Le retour visuel et la conservation du brouillon sur appareil ne sont pas présentés comme vérifiés. Aucun push ni aucune fusion n’a été effectué.

Décisions d’exécution : utiliser les outils locaux et le JDK via `mise exec`, avec le SDK fourni par `ANDROID_HOME` ; conserver les erreurs HTTP existantes ; ajouter la couverture du routage de production en tests unitaires faute d’appareil. Cette dernière vérifie les routes et la pile mais ne remplace pas une recette réelle des écrans.

## Préparation d’exécution

- [ ] Lire la spec, charger les skills d’exécution, TDD et worktrees, puis créer un worktree isolé selon le skill. La spec et ce plan étant initialement non suivis, les transférer explicitement et ne pas emporter les autres fichiers non suivis.
- [ ] Exécuter `./gradlew testDebugUnitTest` pour établir la référence. Si l’environnement ou un test est déjà en échec, le consigner dans le compte rendu, sans l’attribuer à ce chantier ni élargir le périmètre.

Les chemins Kotlin ci-dessous sont relatifs à `app/src/main/java/top/supergeoff/dictee/`, les tests unitaires à `app/src/test/java/top/supergeoff/dictee/`, et les tests UI à `app/src/androidTest/java/top/supergeoff/dictee/`.

### Tâche 1 : Modèle pur d’édition du glossaire

**Files:** créer `ComplementaryGlossary.kt` et `ComplementaryGlossaryTest.kt`.

**Interfaces:** produire `object ComplementaryGlossary` avec `entries(text: String): List<String>`, `append(text: String, input: String): String`, `remove(text: String, index: Int): String`, `validationError(text: String): String?`, `hasAdvisory(text: String): Boolean`. `append` normalise seulement les séparateurs de lignes et omet les lignes blanches ; il ne valide pas les limites à la place de l’appelant. `remove` ignore un index invalide.

- [ ] Écrire les tests : `entries("NeMo\nTerme (souvent entendu : a, b)")` restitue les deux lignes intactes ; `append("NeMo", "A\r\nB\n \n") == "NeMo\nA\nB"` ; `remove("A\nA\nB", 1) == "A\nB"` ; un index hors bornes ne change rien.
- [ ] Ajouter les assertions de limites : 500 lignes acceptées et 501 refusées ; 20 000 caractères acceptés et 20 001 refusés ; 10 000 caractères supplémentaires Unicode hors BMP comptent pour 10 000 et non 20 000. Une chaîne vide est valide. Les avertissements sur plus de cinq mots ou la ponctuation finale n’invalident pas le texte et ne le réécrivent pas.
- [ ] Lancer `./gradlew testDebugUnitTest --tests '*ComplementaryGlossaryTest'` et observer l’échec dû aux interfaces absentes.
- [ ] Implémenter les interfaces, avec `String.codePointCount(0, length)` pour les caractères. Conserver l’entrée complète pour l’avertissement, sans analyse propriétaire des variantes.
- [ ] Relancer la même commande, attendre zéro échec, puis committer uniquement les fichiers de cette tâche avec `feat: model complementary glossary editing`.

### Tâche 2 : Réglages persistés et brouillon de formulaire

**Files:** modifier `TranscriptionSettings.kt`, `TranscriptionSettingsForm.kt`, `LocalState.kt` et les tests `TranscriptionSettingsTest.kt`, `TranscriptionSettingsFormTest.kt`, `LocalStateTest.kt`.

**Interfaces:** ajouter `glossary: String = ""` en dernière propriété de `TranscriptionSettings`. Ajouter `glossary: String = ""`, `glossaryInput: String = ""` au draft sans déplacer les paramètres positionnels existants. Ajouter `setGlossaryInput(value: String)`, `addGlossaryEntry(): Boolean`, `removeGlossaryEntry(index: Int)` au formulaire ; garder `submit(): Boolean`.

- [ ] Écrire les tests : initialisation depuis les réglages ; ajout et suppression par occurrence ; dépassement refusé sans perte de saisie ; `submit()` intègre la saisie en attente ; modification bloquée pendant sauvegarde ; échec de sauvegarde conserve le contenu et produit un message d’erreur ; succès vide la saisie intégrée. Le texte ne figure pas dans le `toString()` du draft.
- [ ] Écrire les tests de round-trip JSON des réglages avec `glossary`, de lecture d’un JSON ancien sans ce champ (vide), et de refus d’une sauvegarde hors limites sans changement durable. Garder les validations hors du bloc qui convertirait toute erreur en `CredentialException`.
- [ ] Lancer `./gradlew testDebugUnitTest --tests '*TranscriptionSettings*Test' --tests '*LocalStateTest'` et observer les échecs attendus.
- [ ] Implémenter les propriétés et méthodes avec le verrou du formulaire. Calculer le candidat intégrant la saisie et le valider avant sauvegarde ; en cas d’échec ne pas perdre de contenu. Persister `glossary` dans l’objet JSON des réglages et le lire avec défaut vide. Garder la version JSON existante si l’ajout optionnel reste compatible avec les lecteurs et fixtures actuels.
- [ ] Relancer les tests ciblés et committer avec `feat: persist complementary transcription glossary`.

### Tâche 3 : Contexte figé et champ multipart standard

**Files:** modifier `TranscriptionState.kt`, `TranscriptionPreparation.kt`, `LocalState.kt`, `TranscriptionClient.kt` et les tests `TranscriptionPreparationTest.kt`, `LocalStateTest.kt`, `TranscriptionClientTest.kt`.

**Interfaces:** ajouter `glossary: String = ""` en dernière propriété de `TranscriptionTask`. Les deux chemins de préparation STT copient `settings.glossary`. Les tâches MD seules gardent une chaîne vide. Le client lit uniquement `task.glossary`.

- [ ] Écrire les tests de préparation dans les deux surcharges, de tâche MD seule sans glossaire, de round-trip JSON des tâches et de lecture d’anciennes tâches sans le champ. Vérifier qu’un changement de réglages ne change pas une tâche préparée puis persistée et relue.
- [ ] Étendre le faux transport existant : `task.copy(glossary = "NeMo\nTerme (souvent entendu : a, b)")` produit exactement une partie multipart `name="prompt"` contenant ces octets UTF-8 ; une tâche au glossaire vide ne contient pas cette partie. Ne pas ajouter de champs maison. Une réponse HTTP 400 reste une erreur et ne déclenche pas de seconde requête sans glossaire.
- [ ] Lancer `./gradlew testDebugUnitTest --tests '*TranscriptionPreparationTest' --tests '*LocalStateTest' --tests '*TranscriptionClientTest'` et observer les échecs attendus.
- [ ] Implémenter la propriété, sa persistance et son passage aux deux constructeurs de préparation. Ajouter `field("prompt", task.glossary)` seulement lorsque non vide ; ne pas modifier les autres parties multipart. Ne pas journaliser le contenu.
- [ ] Relancer les tests ciblés puis tous les tests unitaires. Committer avec `feat: send frozen glossary through standard audio prompt`.

### Tâche 4 : Sous-écrans et interface conforme au design system

**Files:** modifier `ui/SettingsScreen.kt`, `MainActivity.kt` ; créer `ui/GlossaryEditor.kt` et `SettingsNavigationUiTest.kt` ; adapter `TranscriptionSettingsUiTest.kt`, `SynthesisSettingsUiTest.kt`, `AutomaticProcessingSettingsUiTest.kt`. Modifier `ui/Components.kt` seulement si nécessaire pour exposer les actions clavier existantes.

**Interfaces:** ajouter `Screen.BoxSettings` dans `MainActivity`. Conserver `SettingsDestination` pour les accès directs existants sans ajouter un cas boîtier à cette enum utilisée par le pipeline. Ajouter dans `ui/SettingsScreen.kt` `enum class SettingsPage { HOME, BOX, STORAGE, TRANSCRIPTION, SYNTHESIS }`. `SettingsScreen` reçoit `page: SettingsPage = HOME` et `onPage: (SettingsPage) -> Unit`, à la place du défilement par `destination`. Produire `@Composable internal fun GlossaryEditor(form: TranscriptionSettingsForm)`.

- [ ] Écrire les tests Compose : HOME affiche seulement les accès et l’apparence/journal/version ; chaque accès ouvre sa rubrique ; le retour revient à HOME ; les accès directs existants ouvrent la rubrique sans afficher les autres formulaires. Tester le retour du prompt vers SYNTHESIS et des dossiers Drive vers STORAGE. Prévoir un contenu composable interne à rappels injectables si nécessaire pour ne pas construire un vrai `AppModel` dans les tests.
- [ ] Écrire les tests du glossaire : validation via clavier ou bouton Ajouter crée une bulle ; collage multiligne crée plusieurs bulles ; croix retire la bonne occurrence ; erreur de limite conserve la saisie ; Enregistrer intègre la saisie en attente. Vérifier libellés accessibles et état désactivé pendant sauvegarde.
- [ ] Lancer `./gradlew connectedDebugAndroidTest` sur appareil disponible et observer les échecs attendus. Sans appareil, compiler les tests avec `./gradlew compileDebugAndroidTestKotlin` et signaler explicitement que le RED comportemental UI reste non observé ; ne pas présenter une compilation comme une exécution de tests.
- [ ] Remplacer le contenu défilant global par un routage sur `SettingsPage`. Réutiliser les sections existantes et préserver leurs conditions busy. Relier les écrans et la pile retour dans `MainActivity`. Supprimer les `BringIntoViewRequester` devenus inutiles.
- [ ] Remplacer les `OutlinedTextField` et `Button` de transcription par labels, `DicteeTextField` et `PrimaryButton`, avec styles et espacements existants. Conserver les fonctions et messages actuels sans ajouter de test réseau facturable.
- [ ] Implémenter `GlossaryEditor` avec champ unique, action Ajouter et action clavier, bulles dans un `FlowRow`, croix accessible et compteurs. Utiliser les formes et couleurs existantes ; respecter la zone tactile minimale et permettre le retour à la ligne des textes longs. Afficher l’aide de format et l’avertissement non bloquant défini par la spec.
- [ ] Relancer les tests UI disponibles, compiler debug et committer avec `feat: split settings screens and align glossary UI with design system`.

### Tâche 5 : Documentation et vérification finale

**Files:** modifier `docs/specification.md` ; déplacer ce plan terminé dans `docs/archive/` après exécution complète. Ne pas toucher aux autres plans non suivis.

**Interfaces:** aucune interface nouvelle ; vérifier ensemble les contrats des tâches 1 à 4.

- [ ] Mettre à jour la documentation produit avec les rubriques, le fonctionnement des bulles, les limites, l’omission si vide et la distinction entre complément distant et simple indice OpenAI. Mentionner que Realtime n’est pas implémenté dans l’app.
- [ ] Exécuter `./gradlew testDebugUnitTest assembleDebug lint compileDebugAndroidTestKotlin` et vérifier les résultats réels. Lancer `./gradlew connectedDebugAndroidTest` si un appareil est disponible.
- [ ] Vérifier visuellement les thèmes clair/sombre, les grandes polices, le clavier, les longues entrées et le retour entre rubriques. En l’absence d’appareil ou d’émulateur, conserver ces points comme recette non effectuée dans le compte rendu, sans ajout d’un fichier d’état.
- [ ] Examiner `git diff --check` et le diff : aucune clé, aucun contenu personnel, aucune modification de synthèse ou du transport hors ajout de `prompt`. Vérifier l’absence d’accès du client aux réglages courants pour le glossaire d’une tâche existante.
- [ ] Effectuer la revue finale selon la méthode d’exécution choisie, traiter ses résultats avec des tests avant correction, puis archiver le plan terminé et committer avec `docs: document separated settings and glossary behavior`.
- [ ] Présenter les tests réellement exécutés et les limites restantes. Aucun push, publication, fusion ni déploiement sans accord explicite.
