# Noms des locuteurs Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Nommer les locuteurs d'un enregistrement depuis la fiche, réécrire le VTT et le MD du téléphone avec ces noms, et réutiliser les noms grâce à un carnet local.

**Architecture:** Un fichier pur `SpeakerNames.kt` porte toute la logique de texte (étiquettes, réécriture, carnet). `TranscriptionCoordinator` reçoit une opération `renameSpeaker` qui vérifie l'absence de tâche active, promeut le VTT puis le MD par `PhoneStore.writeGuarded` sous `promotionGuard`, et met à jour la provenance. `LocalState` passe en version 9 avec le carnet. L'UI ajoute une rangée « Locuteurs » dans l'onglet Transcription, un dialogue de choix et une section du carnet dans Réglages › Transcription.

**Tech Stack:** Kotlin, Jetpack Compose, JUnit, Gradle. Aucune dépendance nouvelle.

**Spec:** `docs/superpowers/specs/2026-11-19-noms-locuteurs-design.md` (approuvée).

## Global Constraints

- Le flux automatique ne change pas et n'attend jamais l'utilisateur.
- Aucun appel réseau pendant un renommage.
- Le VTT réécrit ne diffère de l'original que dans la valeur des balises `<v>` visées ; il est revalidé par `WebVtt.parse` avant écriture.
- Un nom valide fait 1 à 40 caractères (points de code) après `trim`, sur une ligne, sans `<`, `>` ni `&`. Cette règle remplace l'échappement prévu dans la spec, qui est corrigée en conséquence (tâche 6) : `WebVtt.parse` ne décode pas la valeur des voix, et un nom échappé s'afficherait `&amp;`.
- Renommage refusé si une tâche non terminale (`!phase.isTerminal()`, suspendue ou non) concerne l'enregistrement.
- Le VTT est promu avant le MD. Un échec du MD laisse le VTT renommé et le signale.
- La provenance n'est réécrite que si elle était `CURRENT` avant le renommage ; sinon elle n'est pas créée.
- Le carnet vit seulement dans `state.json`. Le modifier ne touche aucun fichier.
- Code pur écrit test d'abord, conformément à `AGENTS.md`.

## Review Focus

- Le remplacement dans le MD est en mot entier : `A` ne touche pas `Alice`, `SPEAKER_0` ne touche pas `SPEAKER_00` (tâche 1).
- Les classes de voix (`<v.loud A>`), les espaces, les fins de ligne CRLF et le BOM sont préservés (tâche 1).
- Fusion : renommer `B` en un nom déjà porté par `A` produit un seul locuteur (tâche 1, tâche 3).
- Après renommage, `provenanceStatus` reste `CURRENT` et Drive voit une nouvelle empreinte (tâche 3).
- Un état version 8 se lit avec un carnet vide, et la version 9 fait l'aller-retour (tâche 2).

## Préparation d'exécution

- [ ] Lire la spec, charger les skills d'exécution, TDD et worktrees, puis créer un worktree `feature/noms-locuteurs`. La spec et ce plan étant non suivis, les copier explicitement dans le worktree.
- [ ] Vérifier la base : `./gradlew testDebugUnitTest` passe sur le worktree avant toute modification.

## Tâche 1 : `SpeakerNames.kt` (pur, test d'abord)

**Fichiers :** créer `app/src/main/java/top/supergeoff/dictee/SpeakerNames.kt` et `app/src/test/java/top/supergeoff/dictee/SpeakerNamesTest.kt`.

API visée :

```kotlin
data class SpeakerName(val name: String, val lastUsedAt: Long)

object SpeakerNames {
    /** Étiquettes distinctes des balises `<v>`, dans l'ordre d'apparition. */
    fun labels(vtt: ByteArray): List<String>
    /** Nom normalisé (`trim`) ou null s'il est invalide. */
    fun validate(name: String): String?
    /** Remplace la valeur des voix égales à [from] ; lève VttFormatException si le résultat ne se relit pas. */
    fun renameInVtt(vtt: ByteArray, from: String, to: String): ByteArray
    /** Remplacement en mot entier ; renvoie null si [from] n'apparaît pas. */
    fun replaceInMd(md: ByteArray, from: String, to: String): ByteArray?
    /** Ajoute ou rafraîchit un nom (égalité sans casse ni accents), le plus récent en tête. */
    fun use(book: List<SpeakerName>, name: String, now: Long): List<SpeakerName>
    /** Filtre sans casse ni accents, du plus récent au moins récent. */
    fun suggestions(book: List<SpeakerName>, query: String): List<SpeakerName>
    fun rename(book: List<SpeakerName>, old: String, new: String): List<SpeakerName>
    fun remove(book: List<SpeakerName>, name: String): List<SpeakerName>
}
```

- [ ] Écrire les tests, puis les lancer et constater l'échec (`./gradlew testDebugUnitTest --tests '*SpeakerNamesTest'`). Cas minimum :
  - `labels` sur `<v A>`, `<v.loud B>`, `<v A>` répété, segment sans voix → `[A, B]`.
  - `renameInVtt(A → Marie)` : seul `<v A>` devient `<v Marie>` ; `<v.loud A>` devient `<v.loud Marie>` ; `<v Alice>` intact ; CRLF, BOM, `NOTE` et horodatages identiques octet pour octet hors balises.
  - `renameInVtt` qui fusionne : `B → A` donne `labels == [A]`.
  - `replaceInMd` : `SPEAKER_00` remplacé partout, `SPEAKER_000` et `xSPEAKER_00` intacts, retour null quand absent ; le remplacement contient `$` sans être interprété.
  - `validate` : `"  Marie "` → `"Marie"` ; vide, 41 points de code, `"a<b"`, `"a&b"`, `"a\nb"` → null ; un nom de 40 emojis est accepté.
  - `use` : `"élodie"` rafraîchit `"Élodie"` sans doublon et garde l'orthographe saisie en dernier ; `suggestions("elo")` trouve `Élodie`.
- [ ] Implémenter. Indications : regex de voix `<v((?:\.[^ \t>]*)?)([ \t]+)([^>]*)>` en ne remplaçant que le groupe 3 quand `trim()` vaut `from` ; décoder en UTF-8 strict et reproduire le BOM ; mot entier par `(?<![\p{L}\p{N}_])` + `Regex.escape(from)` + `(?![\p{L}\p{N}_])` et `Regex.escapeReplacement(to)` ; comparaison sans accents par `java.text.Normalizer` (NFD, suppression de `\p{Mn}`, `lowercase()`).
- [ ] Tests verts, puis commit `feat: logique des noms de locuteurs`.

## Tâche 2 : carnet dans `LocalState` (version 9)

**Fichiers :** `LocalState.kt`, `LocalStateTest.kt`.

- [ ] Tests d'abord : un état version 8 se lit avec `speakerNames` vide ; un état version 9 avec deux noms fait l'aller-retour ; `VERSION == 9`.
- [ ] Ajouter `val speakerNames: List<SpeakerName> = emptyList()` à `LocalState`, l'écrire sous `"speakerNames"` (`[{name, lastUsedAt}]`), le lire si `version >= 9`. Passer `VERSION` à 9 et le commentaire de classe à « version 9 ». Ne pas imposer le champ en version 9 (contrairement à `boxStatus`) : absent vaut vide.
- [ ] Tests verts, commit `feat: carnet des locuteurs dans l'état local`.

## Tâche 3 : `TranscriptionCoordinator.renameSpeaker`

**Fichiers :** `TranscriptionCoordinator.kt`, nouveau `SpeakerRenamingTest.kt` (s'appuyer sur `MemoryStore` et les doubles de `SynthesisCoordinatorTest`).

Signature :

```kotlin
sealed interface SpeakerRenameResult {
    data object Done : SpeakerRenameResult                 // VTT et MD mis à jour
    data object VttOnly : SpeakerRenameResult              // MD absent ou sans l'étiquette
    data class Refused(val reason: String) : SpeakerRenameResult
}

suspend fun renameSpeaker(recordingName: String, folderUri: String, vttName: String, mdName: String,
    from: String, to: String): SpeakerRenameResult
```

Déroulé, sous `mutex.withLock` puis `withContext(Dispatchers.IO)` :

1. `SpeakerNames.validate(to)` sinon `Refused("Nom invalide.")`.
2. Une tâche de `transcriptionTasks` avec `recordingName` égal et `!phase.isTerminal()` → `Refused("Un traitement est en cours sur cet enregistrement.")`.
3. Lire VTT et MD (`phoneStore(folderUri)`), calculer `oldVtt`, `oldMd` et le statut de provenance avant changement via `provenanceStatus`.
4. `renameInVtt` ; écrire un fichier temporaire (`File.createTempFile`) puis `writeGuarded(vttName, temp, replace = true) { promote -> promotionGuard.withLock { vérifier que le sha du VTT vaut toujours oldVtt, sinon lever PhoneStoreException("Le VTT a changé."); promote() } }`.
5. Si le MD existe et que `replaceInMd` ne renvoie pas null : même écriture gardée avec contrôle de `oldMd`.
6. `repository.updateDurably` : `speakerNames = SpeakerNames.use(..., to, clock())` ; si la provenance était `CURRENT`, remplacer l'entrée `(folderUri, mdName)` par les nouvelles empreintes, sinon la laisser. Si le MD n'a pas été réécrit mais la provenance était `CURRENT`, mettre seulement à jour `vttSha256` : le MD reste la synthèse du même contenu, au nom près.
7. `notifyFilesPromoted(folderUri)`, journal `Source.APP` (« Locuteur renommé », sans le nom), puis résultat. Une `PhoneStoreException` donne `Refused(message)`.

- [ ] Tests d'abord, en échec, puis implémentation. Cas : renommage complet avec provenance restée `CURRENT` ; MD sans l'étiquette → `VttOnly` et VTT renommé ; tâche `SYNTHESIZING` ou suspendue → `Refused` sans écriture ; VTT modifié entre lecture et promotion → `Refused` et MD intact ; provenance `UNKNOWN` avant → aucune provenance créée ; carnet mis à jour.
- [ ] Tests verts, commit `feat: renommage des locuteurs dans les fichiers du téléphone`.

## Tâche 4 : fiche, rangée « Locuteurs » et dialogue

**Fichiers :** `AppState.kt`, `ui/RecordingScreen.kt`, nouveau `ui/SpeakerDialog.kt`.

- [ ] `AppModel.renameSpeaker(name, from, to)` : lance `transcriptionCoordinator.renameSpeaker` dans `scope` avec `jobs.track`, en tirant `folderUri`, `vttName` et `mdName` de l'enregistrement courant ; toast selon le résultat : « VTT et MD mis à jour. », « VTT mis à jour. Le MD ne cite pas <étiquette>. » ou la raison du refus. Exposer `speakerNames` depuis l'état local pour l'UI.
- [ ] Dans `Transcript` : au-dessus des segments, rangée défilante de puces `étiquette ✎` à partir des `cues` déjà lus (`mapNotNull { it.speaker }.distinct()`), absente sans voix. Toucher une puce ou l'étiquette d'un segment ouvre le dialogue. Puces désactivées, avec la raison, quand une tâche active concerne l'enregistrement.
- [ ] Dans le toast « Le MD ne cite pas… », l'action « Refaire le MD » appelle `model.transcribe(setOf(name), RequestedWork.REDO_MD)`. Si le système de toast ne porte pas d'action, afficher le message seul : le menu propose déjà « Refaire le MD ».
- [ ] `SpeakerDialog` : titre « Qui est <étiquette> ? », bouton « Écouter un extrait » (seek au premier segment de l'étiquette puis lecture via `Playback`), champ de saisie, puces du carnet filtrées par `SpeakerNames.suggestions`, entrée « + Ajouter « … » » pour un nom nouveau, « Annuler » et « Valider » (désactivé si `validate` rend null). Si le nom choisi est déjà une autre étiquette de l'enregistrement, demander confirmation : « <étiquette> deviendra aussi <nom>. ». Réutiliser les composants et tokens existants (`Components.kt`, `Dialogs.kt`).
- [ ] `./gradlew assembleDebug` et `./gradlew testDebugUnitTest` passent, commit `feat: nommer les locuteurs depuis la fiche`.

## Tâche 5 : carnet dans Réglages › Transcription

**Fichiers :** `ui/SettingsScreen.kt`, `AppState.kt`.

- [ ] Sous le glossaire (`SettingsScreen.kt`, vers `GlossaryEditor(form)`), section « Locuteurs » : liste du carnet du plus récent au moins récent, chaque ligne avec « Renommer » et « Supprimer ». Liste vide : « Aucun nom enregistré. Nomme un locuteur depuis l'onglet Transcription d'un enregistrement. ».
- [ ] `AppModel.renameSpeakerName(old, new)` et `removeSpeakerName(name)` passent par `localRepository.update` avec `SpeakerNames.rename` et `remove` ; aucun fichier modifié.
- [ ] Build et tests verts, commit `feat: gérer le carnet des locuteurs`.

## Tâche 6 : documentation

- [ ] `docs/specification.md` : dans la fiche, décrire la rangée « Locuteurs » et le dialogue ; dans Réglages, la section « Locuteurs » ; à la ligne « Le VTT reçu est validé en entier… écrit tel quel », ajouter que seul le nommage des locuteurs le modifie ensuite ; dans l'état local, mentionner le carnet ; ajuster la version de `state.json` si elle est citée.
- [ ] Spec du chantier : remplacer l'échappement de `&` et `<` par l'interdiction de `<`, `>` et `&`.
- [ ] `AGENTS.md` : ajouter `SpeakerNames.kt` à la liste des fichiers écrits test d'abord.
- [ ] Commit `docs: noms des locuteurs`.

## Vérification finale

- [ ] `./gradlew testDebugUnitTest`, `./gradlew assembleDebug` et `./gradlew lint` passent.
- [ ] Recette sur téléphone si un appareil est disponible : traitement automatique, puis nommage de deux locuteurs, réutilisation d'un nom sur un second enregistrement, envoi vers Drive et lecture des fichiers distants. Relever le format réel des étiquettes de `dictee-diarize` dans le résultat. Sans appareil, le dire dans le compte rendu plutôt que d'annoncer la recette faite.
- [ ] Revue de code (`requesting-code-review`), puis `finishing-a-development-branch`. Aucun push ni fusion sans accord explicite.

## Révision après recette

La recette sur téléphone a montré que `dictee-diarize` produit des étiquettes d'une lettre (A, B, C…) et que le MD ne les cite pas. Le remplacement de texte dans le MD est donc supprimé, et le renommage devient groupé. Les tâches 1, 3 et 4 ci-dessus décrivent l'état avant cette révision ; voici ce qui les remplace.

- **`SpeakerNames.kt`.** `replaceInMd` et ses tests disparaissent. `renameInVtt(vtt, renames: Map<String, String>)` applique toutes les correspondances en une passe, ce qui permet les fusions et les échanges d'étiquettes.
- **`TranscriptionCoordinator`.** `renameSpeaker(from, to)` est remplacé par `renameSpeakers(recordingName, folderUri, vttName, renames)`. Mêmes garde-fous (tâche non terminale, étiquette introuvable, VTT modifié entre lecture et promotion, revalidation WebVTT), une seule écriture du VTT, aucune écriture du MD, provenance laissée telle quelle, tous les noms utilisés dans le carnet. Une correspondance vide ou sans changement effectif est refusée avec « Aucun nom modifié. ». Le résultat est `Done` ou `Refused(reason)`.
- **`AppModel`.** `renameSpeakers(name, renames)` lance, après `Done`, `transcribe(setOf(name), RequestedWork.REDO_MD)`. Le toast est « VTT mis à jour. Le MD est refait avec ces noms. ». Si la synthèse n'est pas configurée, `reportUnconfiguredSynthesis` affiche déjà son message ; le VTT reste renommé.
- **UI.** Le dialogue par locuteur est remplacé par un dialogue « Locuteurs » unique (`ui/SpeakersDialog.kt`), ouvert depuis n'importe quelle puce ou étiquette de segment. Chaque ligne a un bouton lecture et un champ avec suggestions du carnet ; « Enregistrer » est désactivé sans changement ou avec un nom invalide ; une phrase annonce les fusions, sans confirmation. `SpeakerRenamePresentation.kt` porte l'état du dialogue et perd `speakerMergeNotice` et `speakerNameChoices`.
- **Documentation.** `docs/specification.md` et la spec du chantier décrivent le nouveau parcours, les nouvelles règles et l'observation des étiquettes d'une lettre.
- **Vérification.** `./gradlew testDebugUnitTest assembleDebug lint` et `./gradlew compileDebugAndroidTestKotlin` passent. La recette sur téléphone du renommage groupé et du MD refait reste à faire.
