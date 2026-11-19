# Noms des locuteurs : design

## Besoin

Après la diarisation, l'utilisateur donne un nom à chaque locuteur d'un enregistrement. Le VTT et le MD du téléphone portent ensuite ces noms, et les copies envoyées vers Drive aussi. Un nom saisi une fois se réutilise d'un toucher dans les enregistrements suivants. Le flux automatique (récupération, VTT, sujet, MD) ne change pas et n'attend jamais l'utilisateur.

Hors périmètre : la recherche d'enregistrements par locuteur, la synchronisation du carnet entre appareils, la reconnaissance automatique des voix.

## Choix

| Sujet | Décision | Raison |
| --- | --- | --- |
| Moment du nommage | Après coup, depuis l'onglet Transcription de la fiche | Le flux auto reste intact. |
| Fichiers | Le VTT est réécrit (balises `<v>`), le MD reçoit un remplacement littéral de l'étiquette | Les fichiers locaux et Drive portent les noms, sans appel facturé. |
| Carnet de noms | Dans `state.json`, sur le téléphone uniquement | Le plus simple ; aucune donnée ne quitte le téléphone en dehors des fichiers. |
| Format des étiquettes | Aucune hypothèse : toute valeur de `<v …>` est une étiquette renommable | Le format exact de `dictee-diarize` n'est pas connu. |

## Parcours

### Nommer un locuteur

```
┌─ Fiche · Point projet Alpha ─────────┐
│ [ Synthèse ] [ Transcription ]       │
│ Locuteurs : [SPEAKER_00 ✎] [SPEAKER_01 ✎]
│ 00:00 · SPEAKER_00                   │
│   Bon, on commence par le budget…    │
└──────────────────────────────────────┘
          │ toucher « SPEAKER_00 ✎ » (ou l'étiquette d'un segment)
          ▼
┌─ Qui est SPEAKER_00 ? ───────────────┐
│ ▶ Écouter un extrait (00:00)         │
│ [ Rechercher ou saisir un nom…     ] │
│ Récents                              │
│  ( Marie )  ( Paul D. )  ( Moi )     │
│            [Annuler]  [Valider]      │
└──────────────────────────────────────┘
```

- La rangée « Locuteurs » liste les valeurs distinctes de `<v>` dans l'ordre d'apparition. Sans balise `<v>`, elle n'apparaît pas.
- « Écouter un extrait » place le lecteur de la fiche au premier segment du locuteur et le lance.
- La saisie filtre le carnet sans tenir compte de la casse ni des accents. Un nom absent du carnet s'y ajoute à la validation, via « + Ajouter « … » ».
- Un nom déjà donné à un autre locuteur du même enregistrement fusionne les deux : un dialogue le confirme (« SPEAKER_01 deviendra aussi Marie. »).

### Après validation

```
│ Locuteurs : [Marie ✎] [SPEAKER_01 ✎] │
│ 00:00 · Marie                        │
toast : « VTT et MD mis à jour. »
```

Si le MD ne contient pas l'étiquette telle quelle :

```
toast : « VTT mis à jour. Le MD ne cite pas SPEAKER_00. »  [Refaire le MD]
```

Un nom déjà attribué se renomme de la même façon : « Marie ✎ » ouvre le même dialogue.

### Carnet

Réglages › Transcription › Locuteurs liste les noms, du plus récent au moins récent. On peut y renommer ou supprimer un nom ; cela ne modifie aucun fichier existant.

## Règles

- **Disponibilité.** Le nommage exige un VTT local lisible. Il est indisponible pendant une transcription ou une synthèse active sur cet enregistrement, avec la raison affichée. Un VTT modifié pendant qu'une tâche tourne la suspendrait ; cette règle l'évite.
- **Nom valide.** Texte d'une ligne, 1 à 40 caractères après suppression des espaces aux bords, sans `<`, `>`, `&` ni saut de ligne. Ces caractères ne sont pas échappés : un nom qui les contient est refusé.
- **Réécriture du VTT.** Seule la valeur des balises `<v étiquette>` égale à l'étiquette visée change ; classes `<v.x …>`, horodatages, notes, styles et texte restent identiques à l'octet près. Le résultat est revalidé par `WebVtt.parse`, puis promu par le mécanisme `.part` / `.old` existant.
- **Mise à jour du MD.** Remplacement de toutes les occurrences exactes de l'étiquette, en mot entier, puis promotion par `.part` / `.old`. Le VTT est écrit d'abord ; un échec du MD laisse le VTT renommé et le signale.
- **Provenance.** Après promotion, les empreintes VTT et MD conservées pour la synthèse sont remplacées par celles des nouveaux fichiers, afin que la fiche ne présente pas le MD comme issu d'un ancien VTT et que le parcours Drive reste prouvé. Le modèle mémorisé ne change pas. Si le MD n'avait pas de provenance connue, il n'en reçoit pas.
- **Drive.** Les fichiers renommés ont une nouvelle empreinte : ils redeviennent candidats à « Envoyer (N) » selon les règles actuelles. Aucun envoi automatique.
- **Carnet.** `state.json` garde une liste de `{ name, lastUsedAt }`. Toute validation met `lastUsedAt` à jour. Une ancienne version de l'état se lit avec un carnet vide.
- **Spec.** La règle « le VTT reçu est écrit tel quel » devient : écrit tel quel à la réception, puis modifiable seulement par le nommage des locuteurs. `docs/specification.md` est mis à jour en conséquence.

## Code touché

- `SpeakerNames.kt` (nouveau, sans dépendance Android, test d'abord) : liste des étiquettes, réécriture du VTT, remplacement dans le MD, validation du nom, tri et filtre du carnet.
- `LocalState.kt` : carnet de noms et migration.
- `TranscriptionCoordinator.kt` ou un petit orchestrateur : contrôle de disponibilité, promotions, mise à jour des empreintes, journal (source APP).
- `ui/RecordingScreen.kt` : rangée « Locuteurs », dialogue de choix, toasts.
- Réglages Transcription : écran « Locuteurs ».
- `AGENTS.md` : ajout de `SpeakerNames.kt` à la liste des fichiers écrits test d'abord.

## Tests

- Unitaires (`SpeakerNames`) : étiquettes avec classes, accents et `&` ; réécriture qui préserve tout le reste ; fusion de deux étiquettes ; remplacement en mot entier (`A` ne touche pas `Alice`) ; noms invalides ; tri et filtre du carnet.
- Intégration : provenance conservée après renommage, MD sans l'étiquette, refus pendant une tâche active, migration de l'état.
- Recette sur téléphone : nommer après un traitement auto, réutiliser un nom sur un second enregistrement, envoyer vers Drive et vérifier les noms dans les fichiers distants. Cette recette permettra aussi de relever le format réel des étiquettes de `dictee-diarize`.
