# DIC-9 : afficher l’état du boîtier et le régler

## Intention et périmètre

Dictée 0.9.0 doit remplacer DOWAY pour les consultations et réglages couverts par le protocole validé dans DIC-22. La section Boîtier des réglages affiche la batterie et le stockage du MECHEN V05. Elle permet de changer la réduction du bruit, la durée des segments et le rétro-éclairage sans ouvrir une autre application.

La batterie et le stockage sont lus à chaque ouverture de liaison Bluetooth. Chaque réglage modifié est relu sur le boîtier avant d’être présenté comme confirmé. Hors de portée, les commandes sont grisées et la dernière lecture reste visible.

Le support USB, le gain, la réinitialisation d’usine et le firmware restent hors périmètre. L’application ne change aucune valeur au démarrage ou à l’ouverture des réglages. Elle ne force pas la désactivation de la réduction du bruit : la décision de Geoff du 10 novembre concerne son propre boîtier, pas une valeur imposée aux autres utilisateurs.

## Approches

L’approche retenue avec Geoff exploite la trame `0x05` déjà envoyée pendant la poignée de main de `V05Session`. Sa réponse devient un état typé du boîtier, publié avec l’adresse de l’appareil puis enregistré dans `state.json`. Cette approche garantit une lecture à chaque connexion, y compris lorsqu’elle sert à une récupération, une suppression ou un réglage, sans ajouter une seconde trame d’information.

Une lecture séparée à l’ouverture des réglages aurait limité les changements de la session, mais elle aurait ajouté des trames et laissé les autres connexions sans mise à jour. Une consultation uniquement en ligne aurait simplifié l’état local, mais elle n’aurait pas conservé les dernières valeurs hors de portée.

## État du boîtier et persistance

Un modèle pur représente les champs documentés du payload de 52 octets :

- batterie en pourcentage, index 9 ;
- capacité totale et espace libre, entiers non signés big-endian aux index 0 et 4 ;
- réduction du bruit, index 39 : `00` coupée, `05` faible, `09` moyenne, `0f` élevée ;
- durée des segments, index 41 : `00` 30 minutes, `01` 1 heure, `02` 2 heures, `03` 3 heures ;
- rétro-éclairage, index 42 : `00` désactivé, `01` activé.

Une longueur différente de 52 octets invalide la lecture. Une valeur inconnue de réduction, de durée ou de rétro-éclairage reste identifiable comme inconnue dans le modèle et dans l’interface ; elle ne fait pas perdre la batterie et le stockage lus dans la même trame. L’utilisateur peut alors choisir une valeur documentée. Aucun champ inconnu du payload n’est interprété.

L’état local passe à la version 8 et conserve la dernière lecture valide avec ses valeurs brutes utiles. Une migration depuis la version 7 laisse cet état absent jusqu’à la première connexion. L’association d’un autre boîtier efface la lecture précédente. Une lecture reçue pour une adresse qui n’est plus associée est ignorée. Une coupure ou un échec de lecture conserve la dernière valeur valide sans la présenter comme fraîche.

## Présentation dans Réglages

La section Boîtier garde la ligne d’association existante, puis ajoute les informations et commandes dans la même rubrique. Les libellés restent en français et utilisent les composants Compose déjà présents.

Exemple :

```text
Boîtier
MECHEN                         [Changer]
Batterie                       71 %
Stockage                       23 Mo utilisés sur 59 934 Mo
Réduction du bruit             [Coupée]
Durée des segments             [2 heures]
Rétro-éclairage                [Désactivé | Activé]
```

Les compteurs de stockage sont présentés dans leurs unités documentées, en mégaoctets affichés par DOWAY. Leur différence donne l’espace utilisé. Aucune conversion prétendument exacte en octets n’est affichée. Une capacité ou un espace libre incohérent, par exemple un espace libre supérieur à la capacité, est signalé comme inconnu plutôt que converti en valeur négative.

La réduction du bruit propose quatre choix : « Coupée », « Faible », « Moyenne », « Élevée ». La durée propose « 30 minutes », « 1 heure », « 2 heures » et « 3 heures ». Le rétro-éclairage propose « Désactivé » et « Activé » avec le même type de sélecteur que les autres réglages. Si la valeur lue est inconnue, aucun choix n’est présenté comme actif et une mention « Inconnu » reste visible ; les deux choix documentés restent disponibles. La sélection d’un nouveau choix déclenche immédiatement l’écriture, sans bouton « Enregistrer ».

Les commandes exigent un boîtier associé, une liaison ouverte et une lecture `0x05` valide obtenue sur cette liaison. Elles sont grisées hors ligne, sans lecture fraîche, pendant leur propre écriture et pendant une récupération, une suppression du boîtier, une recherche ou une lecture déjà en cours. Une lecture invalide ou en échec sur une liaison encore ouverte laisse les commandes grisées jusqu’à une nouvelle lecture réussie. Une seule écriture de réglage est admise à la fois. La ligne de changement de boîtier suit les mêmes protections pour éviter un changement d’adresse pendant l’opération.

Hors ligne ou sans lecture fraîche sur la liaison courante, la rubrique indique qu’il s’agit des dernières valeurs lues. En l’absence de toute lecture, elle affiche « Non lu » plutôt qu’une valeur inventée.

## Écriture et confirmation

Chaque commande porte exactement un octet : `0x17` pour la réduction du bruit, `0x16` pour la durée et `0x1a` pour le rétro-éclairage. La sérialisation de `BoxConnection` empêche l’entrelacement avec les autres commandes Bluetooth.

Après l’acquittement, l’application envoie `0x05` et analyse une nouvelle réponse complète. L’affichage n’est jamais optimiste : la valeur confirmée vient de cette relecture. Si la valeur relue correspond au choix, elle devient la dernière lecture enregistrée. Si elle diffère, l’interface affiche la valeur réellement relue et signale que le réglage demandé n’a pas été appliqué.

Un statut d’acquittement différent de `00` est un refus. L’application relit encore l’état si la liaison le permet, actualise les valeurs réelles et signale le refus. Une coupure, un délai dépassé ou une réponse invalide ne provoque aucun renvoi automatique, car la commande peut avoir été appliquée. L’application conserve alors la dernière valeur confirmée et marque l’opération comme non vérifiée. Elle ne conserve pas une demande de réglage à rejouer et n’ajoute pas de bouton « Réessayer » qui enverrait une ancienne intention. Après une reconnexion et une nouvelle lecture valide, l’utilisateur voit l’état réel et peut choisir explicitement une valeur. La reconnexion existante sert à relire le boîtier.

Les trames TX et RX utilisent la journalisation hexadécimale existante. Le journal distingue le choix demandé, le statut reçu et la valeur relue, sans ajouter de données personnelles.

## Concurrence et cycle de vie

L’ouverture de liaison publie l’état `0x05` avec l’adresse du boîtier avant les opérations qui utilisent la session. L’enregistrement durable est rattaché à l’adresse encore associée au moment de sa réception. Les lectures de liste et les lectures d’état restent indépendantes : un échec de l’une ne fabrique pas un succès de l’autre.

Les réglages utilisent les verrous de travail existants afin de ne pas attendre silencieusement la fin d’une longue récupération ou d’une suppression. Le passage en arrière-plan suit la règle actuelle de fermeture Bluetooth. Une écriture déjà engagée n’est ni rejouée ni considérée comme confirmée après une coupure.

## Tests et validation

Le décodage, les commandes et les règles de présentation sans dépendance Android sont développés test d’abord. Les tests couvrent les quatre niveaux de bruit, les quatre durées, les deux états du rétro-éclairage, les entiers non signés, les longueurs invalides, les valeurs inconnues, les acquittements refusés, les relectures concordantes ou divergentes, les délais et les coupures.

Les tests d’état couvrent la migration vers la version 8, la conservation hors ligne, le changement de boîtier et les réponses arrivant pour une ancienne adresse. Les tests de présentation couvrent l’état grisé, l’absence de lecture et l’affichage des dernières valeurs. Les conventions d’`AGENTS.md` sont complétées avec les nouveaux fichiers purs.

La livraison met à jour `docs/specification.md`, `versionName` à `0.9.0` et `versionCode` à 20. Les commandes `testDebugUnitTest`, `assembleDebug`, `lint` et la construction release doivent passer. La recette matérielle compare les valeurs avec DOWAY, change puis relit chaque option sur le boîtier et vérifie l’affichage hors de portée. Elle nécessite un accord explicite avant les modifications réelles du boîtier ; une compilation ne prouve pas leur effet.

## Choix laissés au plan

Le plan choisira les découpages de fichiers et les signatures entre la session, `BoxConnection` et le modèle d’application. Il devra préserver la publication de l’état sur toutes les ouvertures de liaison et éviter les écritures concurrentes. Il choisira aussi entre boutons segmentés et listes de choix pour tenir dans l’écran, sans modifier les valeurs, la représentation de l’inconnu ni le déclenchement immédiat décidés ici.

Cette conception est une proposition en attente de l’accord de Geoff. Le plan d’implémentation sera rédigé après cet accord et attendra sa propre validation. Aucun push, fusion, publication ou changement réel du boîtier ne découle de ce document.
