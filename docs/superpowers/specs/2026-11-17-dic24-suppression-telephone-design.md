# DIC-24 : supprimer les fichiers du téléphone

## Intention et approbation

Libérer la place du téléphone après sauvegarde, sans ouvrir un gestionnaire de fichiers. Geoff a approuvé cette conception dans la conversation du 17 novembre 2026. La version visée est 0.7.0. Le plan reste à approuver avant le code.

## Approches

La suppression directe après confirmation est retenue. Une corbeille est écartée : elle ne libère pas immédiatement la place et impose un parcours de restauration hors périmètre. La suppression automatique est exclue : chaque opération reste explicite.

## Comportement

L'action « Supprimer du téléphone » est accessible depuis le menu ⋮ de la fiche et depuis la sélection de la liste. Le bouton principal reste réservé au parcours existant. Seuls les MP3, VTT et MD présents dans le dossier Android choisi sont concernés. Aucun appel ne supprime un fichier dans Drive ou sur le boîtier.

La confirmation donne le nombre d'enregistrements ayant des fichiers locaux à supprimer. Elle avertit si le VTT ou le MD manque sur Drive, ou si leur sauvegarde ne peut pas être vérifiée. Elle avertit du risque de perte de l'audio lorsqu'aucune autre copie du MP3 n'est connue sur le boîtier ou dans Drive. Ces avertissements ne bloquent pas la confirmation. Annuler ne modifie rien. Une confirmation ne peut être consommée qu'une fois.

Un enregistrement en récupération, en transcription, en synthèse, en envoi ou en attente de traitement est protégé. Il faut arrêter son opération ou le retirer de la file avant de le supprimer. Une sélection qui contient un tel enregistrement ne lance aucune suppression ; le message explique le blocage. Un élément sans fichier local n'est pas une cible.

Les fichiers sont supprimés par le Storage Access Framework, mécanisme Android d'accès au dossier choisi. Une erreur sur un fichier n'annule pas les suppressions déjà effectuées. Les autres fichiers peuvent être traités, puis la liste réelle est relue. Le résultat nomme les fichiers non supprimés ; une relecture impossible est signalée comme telle, jamais comme une réussite complète.

## Présentation après suppression

Pour chaque étape Téléphone, VTT et MD, un fichier local reste « Fait ». À défaut, une copie distante confirmée affiche « Sur Drive » et un fichier sans copie connue affiche « Absent ». Cette règle concerne l'absence après suppression ; les parcours de production existants restent disponibles lorsque l'audio local permet de les exécuter. Un audio encore sur le boîtier reste récupérable. La suppression ne déclenche aucune récupération ni production automatique.

Exemple après suppression des trois fichiers, avec seulement les documents sauvegardés :

```text
Téléphone      VTT           MD            Drive
Absent         Sur Drive    Sur Drive     Sur Drive
```

L'étape Drive reste fondée sur le choix d'envoi de DIC-21 et la destination courante. La présence d'un nom distant ne prouve pas à elle seule que la dernière version locale a été sauvegardée : les reçus de DIC-21 doivent être conservés. Un doute produit un avertissement plutôt qu'une fausse garantie.

L'enregistrement reste listé s'il existe sur le boîtier ou dans Drive, même si Drive ne contient que VTT ou MD. Il disparaît si aucune copie n'est connue. Si sa fiche est ouverte lorsqu'il disparaît, elle revient à la liste. Les documents uniquement distants ne deviennent pas lisibles dans cette carte : aucune nouvelle fonction de téléchargement Drive n'est ajoutée.

## Contraintes techniques et choix de réalisation

Les calculs de sélection, d'avertissement et de résultat restent sans dépendance Android et sont écrits test d'abord. La suppression utilise les noms reconnus de l'enregistrement, jamais les fichiers provisoires ou d'autres documents du dossier. Le dossier et la sélection confirmés sont capturés ; un changement de dossier ou une cible devenue active invalide la demande avant suppression. La suppression partage les protections de transfert existantes et réserve les cibles pour empêcher une opération concurrente de les recréer. Les métadonnées utiles au titre et à la sauvegarde distante sont conservées ; les anciennes erreurs liées aux fichiers supprimés ne doivent pas apparaître comme un nouvel échec.

Ces détails de synchronisation sont des choix de réalisation de l'agent, pas des demandes supplémentaires de Geoff.

## Vérification et livraison

Les tests couvrent la sélection simple et multiple, les avertissements, Drive limité aux documents, les tâches actives et en attente, les erreurs partielles, les changements de dossier et la confirmation unique. Les tests Android couvrent le fournisseur de documents et les dialogues. Exécuter testDebugUnitTest, assembleDebug et lint, puis effectuer une recette sur téléphone : confirmer la disparition des fichiers, le maintien des copies distantes et l'état des étapes. Mettre à jour docs/specification.md.

La compilation ne prouve pas la suppression sur un téléphone. Push, fusion, publication signée et clôture de carte attendent chacun l'accord explicite approprié. Aucune suppression de données réelles n'est autorisée par la seule approbation de cette conception.
