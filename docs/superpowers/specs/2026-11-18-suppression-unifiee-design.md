# Suppression unifiée et liste par emplacement

## Intention

Geoff trouve la suppression actuelle confuse : deux boutons distincts (boîtier, téléphone), une seconde fenêtre de résultat après la confirmation, un bouton boîtier qui n'apparaît que selon la sélection, et un filtre « Sur le boîtier » dont le compteur vaut zéro alors que la liste montre encore des fichiers. Il veut deux actions claires, une liste filtrée par emplacement et une barre du bas réservée à la sélection, avec des gestes sur les tuiles pour les actions courantes.

## Objectifs

- Deux actions de suppression remplacent « Supprimer du boîtier » et « Supprimer du téléphone », dans la sélection multiple et dans le menu ⋮ de la fiche :
  - **Supprimer partout** efface l'enregistrement du boîtier, du téléphone et de Drive, partout où une copie existe et peut être effacée.
  - **Libérer l'espace** efface les niveaux inférieurs au plus haut niveau présent : le MP3 du boîtier n'est effacé que si un MP3 complet existe sur le téléphone ou si une copie du MP3 est prouvée sur Drive ; le téléphone est effacé si Drive a une copie à jour du VTT et du MD ; rien si l'enregistrement n'est que sur le boîtier ou si sa récupération est en erreur. Une copie Drive des seuls VTT et MD suffit pour le téléphone : la perte du MP3 dans ce cas est voulue par Geoff.
- Une seule confirmation par action. Après « Supprimer », la fenêtre se ferme et rien d'autre ne s'ouvre. Un message court (snackbar) n'apparaît qu'en cas d'échec, avec le détail dans le journal.
- Filtres : **Tout, Boîtier, Téléphone, Drive**, dans cet ordre. Un enregistrement apparaît dans chaque filtre où il a une copie ; le compteur compte ces présences. « À relire » disparaît.
- Barre du bas :
  - hors sélection, elle n'affiche qu'un bouton **« Envoyer dans le cloud (n) »**, visible seulement quand n > 0, n étant le nombre d'enregistrements dont le VTT ou le MD présents sur le téléphone ne sont pas synchronisés sur Drive, une version modifiée depuis l'envoi comptant comme non synchronisée (l'audio est ignoré) et Drive activé ;
  - en sélection, elle affiche Récupérer, VTT + MD, Envoyer (si Drive activé), puis Supprimer partout et Libérer l'espace, ces deux boutons toujours visibles et désactivés quand ils n'ont aucune cible.
- Gestes sur une tuile, hors sélection :
  - glisser de gauche à droite ouvre la confirmation de « Supprimer partout » pour cet enregistrement ; annuler remet la tuile en place ;
  - glisser de droite à gauche lance la récupération de l'enregistrement s'il est sur le boîtier, puis le VTT, puis le MD, en sautant les étapes déjà faites.

## Non-objectifs

- Aucune suppression automatique, aucune corbeille locale, aucune commande de suppression multiple côté boîtier.
- Pas de changement du protocole V05 ni de la vérification par relecture de DIC-23.
- Pas de nouvelle permission Drive.

## Anti-objectifs

- Une suppression qui touche un enregistrement occupé (récupération, traitement, envoi, y compris en attente).
- Une fenêtre de résultat après une suppression réussie.
- Un échec silencieux : un échec non signalé est pire qu'une fenêtre de trop.

## Contraintes

- Le scope `drive.file` autorise la suppression des fichiers envoyés par l'application ; un fichier Drive non créé par l'application peut refuser la suppression, ce qui compte comme un échec signalé.
- Les règles sans dépendance Android s'écrivent test d'abord (AGENTS.md).
- `docs/specification.md` est mis à jour. La version sera fixée à la livraison, avec celle qui introduit la suppression unifiée.

## Approches

1. **Orchestrateur unique (retenue par Geoff).** Une règle pure calcule, pour chaque enregistrement et chaque action, les niveaux à effacer. Une demande unique porte une confirmation, puis exécute dans l'ordre Drive, téléphone, boîtier en réutilisant les suppressions existantes (DIC-24 pour le téléphone, DIC-23 pour le boîtier) et une nouvelle suppression Drive. Elle garde les protections de concurrence et la vérification par relecture.
2. Enchaîner les demandes existantes depuis l'écran : une confirmation par niveau et des états partiels difficiles à suivre. Écartée.
3. Réécrire un moteur de suppression : perte des garde-fous validés. Écartée.

## Décisions

- **Drive** : « Supprimer partout » place les fichiers Drive de l'enregistrement (MP3, VTT, MD présents dans le dossier choisi) dans la corbeille Drive (`trashed = true`), récupérable 30 jours. Choix de Claude, non contredit par Geoff.
- **Ordre d'exécution** : Drive, puis téléphone, puis boîtier, pour qu'une coupure Bluetooth n'empêche pas les autres niveaux. Un échec à un niveau n'empêche pas les autres niveaux du même enregistrement ni les autres enregistrements, sauf l'arrêt de lot déjà prévu par DIC-23 côté boîtier.
- **Confirmation** : texte unique, par exemple « Supprimer partout ? 3 enregistrements seront effacés du boîtier, du téléphone et de Drive. » ou « Libérer l'espace ? 2 enregistrements seront effacés du boîtier, 1 du téléphone. Les copies Drive sont conservées. » Les enregistrements occupés bloquent la confirmation comme aujourd'hui. Un enregistrement sans cible pour « Libérer l'espace » est ignoré.
- **Boîtier hors de portée** : les niveaux téléphone et Drive sont traités ; le niveau boîtier est signalé en échec (snackbar), sans relance automatique.
- **Liste** : un enregistrement sans aucune copie restante disparaît ; la fiche ouverte revient à la liste.
- **Glisser vers la gauche** : réutilise la récupération puis l'enchaînement automatique de DIC-20 ; si l'audio est déjà sur le téléphone, seul le traitement manquant est lancé ; s'il n'y a rien à faire, la tuile revient sans effet.

## Précisions après relecture (choix de Claude)

- **Copie Drive pour les filtres** : un enregistrement est « Drive » quand la liste Drive du dernier rafraîchissement contient au moins un de ses fichiers (même règle que `Recordings.build`). Drive désactivé ou liste absente : le filtre Drive compte zéro.
- **Copie Drive pour « Libérer l'espace »** : le téléphone n'est effacé que si le VTT et le MD ont une copie prouvée pour leur version actuelle. Pour un document présent sur le téléphone, `DriveSync.action` avec l'empreinte actuelle (`documentHashes`) doit rendre `SKIP` ; sans empreinte connue, rien n'est libéré. Pour un document absent du téléphone, `DriveSync.hasConfirmedCopy` suffit. Sinon seul le boîtier est libéré, s'il y a un MP3 complet ailleurs. Une action destructive ne s'appuie jamais sur une présence non confirmée.
- **MP3 du boîtier** : jamais effacé sans MP3 complet sur le téléphone (sans erreur de récupération) ni copie du MP3 prouvée sur Drive pour la destination courante. Un enregistrement dont l'étape de récupération est en erreur n'a aucune cible en « Libérer l'espace », car son MP3 du téléphone peut être tronqué. La règle des documents sur Drive pour le téléphone reste celle de Geoff : elle peut faire perdre le MP3 du téléphone.
- **Revalidation à l'exécution** : sous le verrou de transfert et après la garde, le dossier du téléphone et les empreintes sont relus, le plan est recalculé, et seule l'intersection avec le plan confirmé s'exécute. Un changement qui retire des cibles est journalisé et signalé par un snackbar.
- **Drive illisible** : Drive activé et dossier choisi mais liste absente : « Supprimer partout » le dit dans la confirmation et signale un échec du niveau Drive après exécution. Dossier du téléphone choisi mais illisible : la confirmation le dit.
- **Confirmation** : avec une seule cible, elle nomme l'enregistrement (sujet, sinon date). La sélection n'est vidée que si la suppression est effectivement lancée.
- **Après mise à la corbeille** : les reçus des fichiers concernés sont retirés de l'état local et la liste Drive est relue. Un fichier déjà absent (404) ou déjà dans la corbeille compte comme un succès.
- **Drive désactivé, hors ligne ou non autorisé** : avec Drive désactivé, le niveau Drive est ignoré et la confirmation le dit. Hors ligne ou sans autorisation, le niveau Drive est signalé en échec, comme le boîtier hors de portée.
- **Enregistrement occupé** : comme aujourd'hui, un enregistrement occupé dans la sélection bloque toute la confirmation, qui l'explique. Le geste ouvre la même confirmation, bloquée de la même façon. La garde existante (`RecoveryDeletionGuard` et les plans DIC-23 et DIC-24) est étendue aux envois Drive et à la mise à la corbeille.
- **Glisser vers la gauche sans effet** : quand rien n'est à faire ou que l'enregistrement est occupé, un snackbar le dit (« Déjà traité », « Traitement en cours »). Les échecs de récupération et de traitement suivent leurs signalements existants.
- **Bouton cloud** : son compteur porte sur toute la liste, pas sur le filtre. L'ancien bouton Envoyer hors sélection est remplacé par celui-ci et passe par la même fenêtre d'envoi qu'aujourd'hui (choix « documents seulement » préselectionné).

## Laissé au constructeur

Découpage des classes, forme exacte des textes de confirmation et du message d'échec, seuils du geste (Material 3 `SwipeToDismissBox`), retrait du code devenu mort (dialogues et résultats séparés de DIC-23 et DIC-24).

## Validation

`./gradlew testDebugUnitTest`, `assembleDebug` et `lint` passent. Tests unitaires de la règle de ciblage (chaque combinaison boîtier/téléphone/Drive pour les deux actions), des filtres, du compteur du bouton cloud et de l'orchestration (échecs partiels, occupé, boîtier absent). La recette sur téléphone, avec des fichiers d'essai, vérifie les deux actions, les deux gestes et l'absence de fenêtre de résultat ; une compilation ne prouve pas une suppression réelle.
