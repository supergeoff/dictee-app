# Noms des locuteurs : design

## Besoin

Après la diarisation, l'utilisateur donne un nom à chaque locuteur d'un enregistrement. Le VTT du téléphone porte ensuite ces noms, le MD est refait à partir de ce VTT, et les copies envoyées vers Drive suivent. Un nom saisi une fois se réutilise d'un toucher dans les enregistrements suivants. Le flux automatique (récupération, VTT, sujet, MD) ne change pas et n'attend jamais l'utilisateur.

Hors périmètre : la recherche d'enregistrements par locuteur, la synchronisation du carnet entre appareils, la reconnaissance automatique des voix.

## Révision après recette

La recette sur téléphone a relevé que `dictee-diarize` produit des étiquettes d'une seule lettre (A, B, C…) et que le MD ne les cite pas. Le remplacement de texte dans le MD, prévu à l'origine, était donc inutile et risqué : une étiquette d'une lettre se confond avec n'importe quel mot isolé. Cette révision change quatre choses :

- le MD n'est plus jamais modifié par le nommage ; il est refait à partir du nouveau VTT ;
- tous les locuteurs d'un enregistrement se nomment dans un seul dialogue, avec une seule écriture du VTT ;
- la fusion n'a plus de dialogue de confirmation, seulement une phrase d'avertissement ;
- la provenance n'est plus réécrite, puisque le VTT change et que le MD est refait.

## Seconde révision : retour à l'étiquette d'origine

Retirer un nom perdait l'étiquette d'origine, puisque le VTT ne gardait que la valeur de `<v>`. Le renommage garde maintenant l'origine dans une classe de voix :

- `<v A>` renommé en Marie devient `<v.spk-A Marie>`. L'origine doit correspondre à `[A-Za-z0-9_-]+` ; sinon aucune classe n'est posée et le retour est impossible pour ce locuteur.
- Une balise qui porte déjà `spk-X` la garde : `X` reste l'origine après plusieurs renommages. Les autres classes sont préservées, la classe `spk-` s'ajoute après elles.
- Revenir à l'origine retire la classe : `<v X>`, ou `<v.loud X>` s'il reste une autre classe.
- `SpeakerNames.labels` rend des `SpeakerLabel(current, original?)` : le nom courant et l'origine (la classe `spk-`, ou la valeur elle-même tant que rien n'a été renommé, si elle correspond au motif).
- Le dialogue « Locuteurs » affiche l'origine à gauche, un champ prérempli avec le nom courant s'il diffère de l'origine (« (laisser A) » sinon) et un bouton ✕ qui vide la saisie. Un champ vidé sur un locuteur nommé ramène à l'origine ; « Enregistrer » exige un changement effectif.
- Une fusion garde la classe de chaque balise. Deux locuteurs fusionnés sous « Marie » ont donc deux lignes, `(Marie, A)` et `(Marie, B)`, et les vider rend A et B. Les correspondances de `renameSpeakers` et de `renameInVtt` ont pour clé le `SpeakerLabel` (nom courant et origine), non le seul nom courant, pour distinguer ces lignes.
- Une origine rétablie n'entre pas dans le carnet. Le retour n'est pas soumis à la limite de 40 caractères.
- La rangée de puces, les étiquettes des segments et `WebVtt.parse` restent sur le nom courant. Le MD est refait après chaque `Done`.

Le prompt de synthèse par défaut demande en plus d'attribuer propos, décisions et actions aux intervenants nommés, sans traiter une étiquette d'une lettre ni « Non disponible » comme un nom. Un prompt enregistré identique à l'ancien défaut suit le nouveau à la lecture de l'état local ; un prompt personnalisé ne change jamais.

## Choix

| Sujet | Décision | Raison |
| --- | --- | --- |
| Moment du nommage | Après coup, depuis l'onglet Transcription de la fiche | Le flux auto reste intact. |
| Fichiers | Le VTT est réécrit (balises `<v>`), le MD est refait par la synthèse existante | Les étiquettes d'une lettre ne figurent pas dans le MD : un remplacement n'aurait rien à remplacer ou toucherait des mots ordinaires. |
| Granularité | Un dialogue pour tous les locuteurs, une écriture du VTT par enregistrement | Un seul VTT modifié, une seule synthèse relancée. |
| Carnet de noms | Dans `state.json`, sur le téléphone uniquement | Le plus simple ; aucune donnée ne quitte le téléphone en dehors des fichiers. |
| Format des étiquettes | Aucune hypothèse dans le code : toute valeur de `<v …>` est une étiquette renommable. Observé : une lettre (A, B, C…). Le retour à l'origine ne vaut que pour une étiquette `[A-Za-z0-9_-]+` | Le code reste valable si le format change. |

## Parcours

### Nommer les locuteurs

```
┌─ Fiche · Point projet Alpha ─────────┐
│ [ Synthèse ] [ Transcription ]       │
│ Locuteurs : [A ✎] [B ✎] [C ✎]        │
│ 00:00 · A                            │
│   Bon, on commence par le budget…    │
└──────────────────────────────────────┘
          │ toucher n'importe quelle puce (ou l'étiquette d'un segment)
          ▼
┌─ Locuteurs ──────────────────────────┐
│ A  ▶  [ Marie            ✕ ]         │
│ B  ▶  [ Paul D.          ✕ ]         │
│ C  ▶  [ (laisser C)        ]         │
│ Le MD sera refait avec ces noms.     │
│              [Annuler] [Enregistrer] │
└──────────────────────────────────────┘
```

- La rangée « Locuteurs » liste les valeurs distinctes de `<v>` dans l'ordre d'apparition. Sans balise `<v>`, elle n'apparaît pas.
- Chaque ligne du dialogue porte une étiquette, un bouton ▶ et un champ. ▶ place le lecteur de la fiche au premier segment de l'étiquette et le lance.
- Chaque champ est une saisie avec les suggestions du carnet, filtrées sans tenir compte de la casse ni des accents. Un champ vide vise l'origine : il laisse l'étiquette d'un locuteur non nommé et ramène un locuteur nommé à son étiquette. Un nom absent du carnet s'y ajoute à l'enregistrement.
- « Enregistrer » est désactivé quand aucun nom ne change ou quand un nom saisi est invalide.
- Si plusieurs locuteurs finissent sous le même nom, une phrase sous la liste l'annonce : « A et B seront fusionnés. ». Il n'y a pas de dialogue de confirmation.

### Après l'enregistrement

```
│ Locuteurs : [Marie ✎] [Paul D. ✎] [C ✎] │
│ 00:00 · Marie                        │
toast : « VTT mis à jour. Le MD est refait avec ces noms. »
```

L'app lance ensuite « Refaire le MD » sur le nouveau VTT, comme le fait le menu de la fiche. Si la synthèse n'est pas configurée, l'app affiche son message habituel (« Réglages de synthèse à compléter. Ouvre Réglages, rubrique Synthèse. ») ; sans MP3 sur le téléphone, la synthèse ne part pas. Dans ces cas le VTT reste renommé et « Refaire le MD » du menu de la fiche permet de relancer plus tard. Les toasts de l'app ne portent pas d'action.

Un nom déjà attribué se renomme de la même façon : « Marie ✎ » ouvre le même dialogue.

### Carnet

Réglages › Transcription › Locuteurs liste les noms, du plus récent au moins récent. On peut y renommer ou supprimer un nom ; cela ne modifie aucun fichier existant.

## Règles

- **Disponibilité.** Le nommage exige un VTT local lisible. Il est indisponible pendant une transcription ou une synthèse active sur cet enregistrement, avec la raison affichée. Un VTT modifié pendant qu'une tâche tourne la suspendrait ; cette règle l'évite.
- **Nom valide.** Texte d'une ligne, 1 à 40 caractères après suppression des espaces aux bords, sans `<`, `>`, `&` ni saut de ligne. Ces caractères ne sont pas échappés : un nom qui les contient est refusé.
- **Une écriture par enregistrement.** Toutes les correspondances (locuteur vers nom) sont appliquées en une passe et promues en une seule écriture du VTT. Un locuteur absent des correspondances reste inchangé. Une correspondance vide, ou qui ne change aucun nom, est refusée avec « Aucun nom modifié. » sans écriture. Un locuteur introuvable dans le VTT refuse tout le renommage.
- **Réécriture du VTT.** Seule la valeur des balises `<v étiquette>` figurant dans les correspondances change, avec la classe `spk-` décrite plus haut ; les autres classes `<v.x …>`, horodatages, notes, styles et texte restent identiques à l'octet près. Le résultat est revalidé par `WebVtt.parse`, puis promu par le mécanisme `.part` / `.old` existant, seulement si le VTT garde l'empreinte lue.
- **Fusion.** Plusieurs étiquettes envoyées vers le même nom fusionnent. Un nom égal à une étiquette laissée telle quelle fusionne aussi. L'écran l'annonce, sans confirmation.
- **MD.** Le nommage n'écrit jamais le MD. Après un renommage réussi, l'app lance « Refaire le MD » sur le nouveau VTT.
- **Provenance.** Elle n'est plus réécrite : le VTT change, donc le MD existant est présenté comme issu de l'ancien VTT jusqu'à son remplacement par le MD refait.
- **Drive.** Le VTT renommé a une nouvelle empreinte et redevient candidat à « Envoyer (N) » selon les règles actuelles, comme le MD une fois refait. Aucun envoi automatique.
- **Carnet.** `state.json` garde une liste de `{ name, lastUsedAt }`. Tous les noms utilisés par un renommage reçoivent le même `lastUsedAt`. Une ancienne version de l'état se lit avec un carnet vide.
- **Spec.** La règle « le VTT reçu est écrit tel quel » devient : écrit tel quel à la réception, puis modifiable seulement par le nommage des locuteurs. `docs/specification.md` est mis à jour en conséquence.

## Code touché

- `SpeakerNames.kt` (sans dépendance Android, test d'abord) : liste des locuteurs (`SpeakerLabel`), réécriture du VTT par correspondances, validation du nom, tri et filtre du carnet.
- `SpeakerRenamePresentation.kt` (sans dépendance Android, test d'abord) : état du dialogue (correspondances effectives, saisies invalides, phrases de fusion), suggestions, messages.
- `LocalState.kt` : carnet de noms et migration.
- `TranscriptionCoordinator.kt` : `renameSpeakers`, contrôle de disponibilité, une promotion du VTT, carnet, journal (source APP).
- `AppState.kt` : `AppModel.renameSpeakers`, qui lance ensuite `transcribe(setOf(name), RequestedWork.REDO_MD)`.
- `ui/SpeakersDialog.kt` et `ui/RecordingScreen.kt` : rangée « Locuteurs », dialogue unique, toasts.
- Réglages Transcription : écran « Locuteurs ».
- `AGENTS.md` : liste des fichiers écrits test d'abord.

## Tests

- Unitaires (`SpeakerNames`) : étiquettes avec classes, accents et `&` ; réécriture qui préserve tout le reste ; plusieurs correspondances en une passe ; échange et fusion d'étiquettes ; noms invalides ; tri et filtre du carnet.
- Unitaires (`SpeakerRenamePresentation`) : champ vide, nom égal à l'étiquette, nom invalide, phrases de fusion, suggestions, messages.
- Intégration (coordinateur) : une seule écriture du VTT pour plusieurs noms, MD et provenance intacts, refus pendant une tâche active, refus sans changement effectif, VTT modifié avant la promotion, tous les noms dans le carnet, migration de l'état.
- Recette sur téléphone : nommer plusieurs locuteurs après un traitement auto, vérifier le MD refait, réutiliser un nom sur un second enregistrement, envoyer vers Drive et vérifier les noms dans les fichiers distants.
