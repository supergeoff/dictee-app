# DIC-23 : supprimer des enregistrements du boîtier

## Intention et périmètre

Dictée 0.8.0 permet de libérer manuellement le stockage du MECHEN V05 sans DOWAY. La suppression concerne uniquement les enregistrements présents sur le boîtier, jamais les fichiers du téléphone ou de Drive. Aucune suppression automatique après récupération n'est ajoutée. Le bouton principal de la fiche reste réservé au parcours existant.

## Approches

L'approche recommandée utilise la commande individuelle documentée dans DIC-22, puis relit la liste après chaque tentative de suppression. Elle prouve la disparition de chaque cible et garde un résultat exploitable en cas d'échec partiel. Un envoi groupé sans relecture intermédiaire rendrait les résultats moins précis après une coupure. Une corbeille et toute commande de suppression multiple sont exclues faute de protocole validé.

## Actions et confirmation

L'action « Supprimer du boîtier » apparaît dans le menu ⋮ de la fiche et dans les actions de sélection de la liste. Seuls les enregistrements encore présents sur le boîtier sont ciblés. Une confirmation partagée indique le nombre de cibles et précise que les copies du téléphone et de Drive restent intactes.

Geoff a confirmé que le dialogue doit avertir lorsque l'application ne connaît aucune autre copie du MP3, sans interdire la suppression. Une copie du téléphone doit être constatée dans la liste locale ; une copie Drive utilise les preuves existantes de DIC-21. Une vérification indisponible n'est jamais présentée comme une sauvegarde confirmée.

Exemple :

```text
Supprimer du boîtier ?
Supprimer 2 enregistrements du boîtier.
Les copies du téléphone et de Drive sont conservées.
Attention : aucune autre copie audio n'est confirmée pour 1 enregistrement.
[Annuler] [Supprimer]
```

Le dialogue est consommé une seule fois. L'annulation ne déclenche aucune commande. Une cible occupée par une récupération, un traitement ou un envoi, y compris en attente, bloque la sélection entière avant émission ; l'interface explique le blocage. Cette protection reprend le comportement de DIC-24.

## Exécution et résultats

La demande capture le boîtier choisi et les noms confirmés. Un changement de boîtier ou une cible devenue occupée annule la demande avant émission. Les réservations et protections de concurrence existantes empêchent une récupération ou une autre suppression de viser les mêmes cibles pendant l'opération. La session Bluetooth reste sérialisée par BoxConnection.

Pour chaque cible, l'application envoie `0x0d` avec exactement les 14 octets ASCII du nom, sans extension ni terminateur. Elle attend le statut selon le délai existant de cinq secondes, puis relit la liste par `0x18 00` et `0x0a`. Un acquittement `00` ne suffit pas : la disparition de la cible dans une liste complète confirme le succès. La liste relue remplace la liste mémorisée sans effacer les autres copies ou métadonnées utiles.

Un refus explicite suivi d'une liste complète est signalé pour la cible ; les autres cibles peuvent être traitées. Une coupure, un délai dépassé ou une liste incomplète arrête le lot, sans renvoi automatique de la suppression. Les suppressions déjà confirmées restent acquises. Le résultat distingue les suppressions confirmées, les refus et les cibles non vérifiées ou non traitées ; il ne présente jamais une ancienne liste comme une relecture réussie. Une actualisation ultérieure permet de constater l'état réel.

Après suppression, un enregistrement conservé sur le téléphone ou Drive reste visible selon les règles de DIC-24. Un enregistrement sans aucune copie connue disparaît de la liste ; sa fiche revient à la liste. La suppression ne déclenche ni récupération ni traitement. Les trames TX et RX sont journalisées en hexadécimal comme les autres commandes.

## Base de travail et validation

L'implémentation part de la version comprenant DIC-24 et le protocole validé de DIC-22, sans modifier leurs espaces de travail. Les règles sans dépendance Android et la commande V05 sont développées test d'abord. Les tests couvrent payload exact, refus, délai dépassé, coupure, relecture incomplète, absence effective, lot partiel, avertissement de copie, annulation et concurrence.

La livraison met à jour docs/specification.md et la version 0.8.0. Les commandes testDebugUnitTest, assembleDebug et lint doivent passer. La recette sur téléphone vérifie une suppression unique puis multiple sur des fichiers d'essai sauvegardés et la conservation des autres copies. Une compilation ne prouve pas une suppression réelle.

Cette conception a été approuvée par Geoff le 17 novembre 2026 dans la conversation consacrée à DIC-23. Le plan d'implémentation est préparé ensuite et attend sa propre validation. Aucun push, fusion, publication ni suppression réelle ne découle de l'approbation du document ; ces opérations attendent chacune l'accord explicite approprié.
