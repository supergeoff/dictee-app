# Protocole Bluetooth du MECHEN V05

Ce document décrit ce que le boîtier MECHEN V05 fait en Bluetooth Low Energy et ce que l'application lui envoie. Il ne décrit que le comportement observé du boîtier.

Les valeurs d'exemple sont fictives : l'identifiant d'appairage est `123456`, le numéro de série est `SN00000001` et les enregistrements portent des noms du 1er janvier 2026. Les CRC des exemples sont calculés pour ces valeurs.

## Recherche du boîtier

Le boîtier testé s'annonce sous le nom `MECHEN V05`. L'application liste les appareils dont le nom commence par `MECHEN`. Elle accepte aussi un appareil dont l'annonce porte des données fabricant d'identifiant `16 11` ou `24 A7`, repères que le boîtier testé n'a pas montrés. Un boîtier déjà connecté au téléphone par une autre application ne s'annonce plus.

## Caractéristiques GATT

Le boîtier testé expose des caractéristiques dont l'UUID contient `B0B1` à `B0B4`. L'application reconnaît le rôle d'une caractéristique quand son UUID contient l'un des repères du rôle, sans tenir compte de la casse.

| Rôle | Repère observé dans l'UUID | Handle observé | Repères que l'app accepte aussi |
|---|---|---|---|
| Écriture des commandes (sans réponse ATT) | `B0B1` | `0x001c` | `0011202a`, `E8A1` |
| Réponses aux commandes (notifications) | `B0B2` | `0x001e` | `0011203a`, `E8A2` |
| Flux audio d'un fichier demandé avec `0x0b` (notifications) | `B0B4` | `0x0024` | `0011204a`, `E8A4` |
| Flux audio d'un enregistrement transmis en direct (notifications) | `B0B3` | `0x0021` | `0011201a`, `E8A3` |

Les repères de la dernière colonne n'ont pas été observés sur le boîtier testé : l'application les accepte aussi. Le descripteur de configuration de chaque caractéristique de notification se trouve au handle suivant : `0x001f`, `0x0022` et `0x0025`. L'application active les notifications de toutes les caractéristiques ci-dessus qui en proposent.

Pendant le transfert d'un fichier, seuls les octets reçus sur la caractéristique des fichiers (`B0B4`) appartiennent au fichier. Les octets reçus sur la caractéristique du direct (`B0B3`) sont comptés au journal et ignorés.

Après la connexion, l'application demande un MTU de 247 octets. Le boîtier demande lui-même un MTU, ce qui permet des notifications de 244 octets d'audio sur la caractéristique des fichiers.

## Format des trames

```text
A0 0A 01 | commande (1 octet) | longueur du payload (1 octet) | payload | CRC16 (2 octets)
```

Le CRC porte sur tous les octets qui le précèdent. L'ordre de ses deux octets dépend du sens : l'application l'écrit en big-endian et le boîtier l'écrit en little-endian. Les entiers du payload s'écrivent en big-endian dans les deux sens. Les noms d'enregistrement sont des chaînes ASCII de 14 caractères (`AAAAMMJJhhmmss`).

Une trame reçue dont l'en-tête, la longueur ou le CRC ne correspond pas est notée au journal et ignorée.

## CRC-16/ARC

Le CRC est un CRC-16/ARC : polynôme `0x8005`, entrée et sortie réfléchies, initialisation à zéro, xorout à zéro. Pour chaque octet, le calcul combine l'octet au CRC par un OU exclusif, puis répète huit fois l'opération suivante : décaler le CRC d'un bit vers la droite et, si le bit sorti valait 1, combiner le CRC avec le polynôme réfléchi `0xA001`.

La valeur de contrôle habituelle de ce CRC est `0xbb3d` pour les neuf octets ASCII de `123456789`. Le tableau suivant donne des trames qui permettent de vérifier une implémentation du CRC.

| Sens | Octets avant le CRC | CRC | Écrit dans la trame |
|---|---|---|---|
| Application | `a00a010200` | `0x61d3` | `61 d3` |
| Application | `a00a010a00` | `0xa1d4` | `a1 d4` |
| Application | `a00a01180100` | `0xca00` | `ca 00` |
| Boîtier | `a00a01030100` | `0xcd70` | `70 cd` |
| Boîtier | `a00a01071500323032363031303131323030303000005fa00101` (trame `0x07` de l'exemple) | `0x747f` | `7f 74` |

## Commandes

| Code | Payload écrit vers le boîtier | Réponse du boîtier (payload) | Usage |
|---|---|---|---|
| `0x02` | vide | 16 octets : identifiant d'appairage en ASCII complété par des zéros (`123456` dans l'exemple) | Lire l'identifiant d'appairage |
| `0x03` | les 16 octets reçus avec `0x02`, puis `00` | 1 octet de statut, `00` pour accepté | Rejouer l'appairage |
| `0x01` | vide | numéro de série en ASCII (`SN00000001` dans l'exemple) | Journal |
| `0x05` | vide | 52 octets d'informations, dont le nom interne | Journal |
| `0x04` | 6 octets : année moins 2000, mois, jour, heure, minute, seconde (heure locale) | 1 octet de statut | Mettre le boîtier à l'heure |
| `0x18` | 1 octet `00` | 1 octet de statut | Envoyé avant chaque lecture de la liste |
| `0x0a` | vide | une notification par enregistrement (20 octets : statut, nom, taille sur 4 octets, indicateur), puis une notification de fin d'un octet `02` | Lire la liste |
| `0x0b` | 18 octets : nom, puis position de départ sur 4 octets (`0`) | 5 octets : statut (`00` pour accepté), puis taille du fichier sur 4 octets ; en fin d'envoi, une seconde trame `0x0b` d'un octet `02` | Télécharger un enregistrement en mémoire |
| `0x06` | envoyé par le boîtier | 16 octets : statut, nom, indicateur | Nouvel enregistrement, noté au journal et ignoré |
| `0x15` | vide (l'application ne l'envoie pas) | 20 octets : statut, nom, taille (`0`), indicateur | Transfert en direct |
| `0x07` | envoyé par le boîtier | 21 octets : statut, nom, taille envoyée, deux octets d'indicateurs | Fin du transfert en direct |

Chaque commande attend sa réponse 5 secondes au plus. Une seule commande est en cours à la fois.

## Déroulé d'une connexion

1. L'application se connecte au boîtier (15 secondes au plus), demande le MTU, découvre les services et active les notifications.
2. Elle envoie `0x02`, puis `0x03` avec l'identifiant reçu. Un statut différent de `00` arrête la session avec l'erreur « appairage refusé ».
3. Elle envoie `0x01` et `0x05` pour le journal, puis `0x04` avec l'heure du téléphone.
4. Elle envoie `0x18` avec `00` et attend la réponse, puis elle envoie `0x0a` et lit la liste jusqu'à la notification de fin.
5. Elle télécharge les enregistrements voulus, un par un, avec `0x0b`.
6. Elle se déconnecte.

Exemple de session avec les valeurs fictives (`TX` : trame écrite vers le boîtier par l'application, `RX` : trame reçue du boîtier) :

```text
TX a00a01020061d3   lire l'identifiant d'appairage
RX a00a010210313233343536000000000000000000000792   identifiant 123456
TX a00a0103113132333435360000000000000000000000c2bf   rejouer l'appairage
RX a00a0103010070cd   accepté
TX a00a01010091d3   lire le numéro de série
RX a00a01010a534e3030303030303031563d   SN00000001
TX a00a0104061a01020c223872af   mise à l'heure : 2026-01-02 12:34:56
RX a00a01040100c10c   accepté
TX a00a01050051d1   lire les informations
RX a00a0105340000ea600000ea5c0064000102060101ff7630355f64656d6f000000000000000000000000000009ff03000000000000000000007976   52 octets d'informations
TX a00a01180100ca00   0x18 avec 00
RX a00a0118010000ca   statut 00
TX a00a010a00a1d4   lire la liste
RX a00a010a140032303236303130313039303030300002b1d4004ea3   entrée de liste
RX a00a010a1400323032363031303131343030303000006a3c0125e6   entrée de liste
RX a00a010a140032303236303130313130333030300012d68c01c19f   entrée de liste
RX a00a010a0102210e   fin de liste
```

## Liste des enregistrements

Le boîtier répond à `0x0a` par une notification `0x0a` par enregistrement, puis par une notification de fin. Les entrées ne sont pas forcément triées par date. Le payload d'une entrée compte 20 octets :

| Octets | Contenu |
|---|---|
| 0 | statut, `00` |
| 1 à 14 | nom de l'enregistrement, 14 caractères ASCII |
| 15 à 18 | taille du fichier MP3 en octets, big-endian |
| 19 | indicateur : `01` pour une note, `00` pour un appel |

La notification de fin n'a qu'un octet de payload, `02`. L'application traite tout autre indicateur que `00` et `01` comme une note, avec une ligne au journal.

Exemple, avec la deuxième entrée de la session fictive :

```text
RX a00a010a1400323032363031303131343030303000006a3c0125e6
   statut 00, nom 20260101140000, taille 0x00006a3c (27 196 octets), indicateur 01
```

## Téléchargement avec 0x0b

Le boîtier envoie un enregistrement en mémoire sur demande. L'exemple suivant télécharge `20260101153000`, de 76 644 octets.

1. L'application envoie `0x0b` avec le nom et la position de départ `0` :

   ```text
   TX a00a010b123230323630313031313533303030000000006b8d
   ```

2. Le boîtier répond par `0x0b` avec le statut `00` et la taille du fichier sur 4 octets :

   ```text
   RX a00a010b050000012b644da0   statut 00, taille 0x00012b64 (76 644 octets)
   ```

   Une réponse sans taille ou avec un autre statut est un refus. L'application note le statut reçu, marque l'enregistrement en erreur et passe au suivant sans nouvelle demande.

3. Le boîtier envoie l'audio en notifications sur la caractéristique des fichiers (`B0B4`). L'application écrit ces octets dans un fichier `<nom>.mp3.part`.

4. Le boîtier termine l'envoi par une trame `0x0b` d'un octet `02` :

   ```text
   RX a00a010b010270ce
   ```

5. Le fichier est valide si le nombre d'octets reçus égale la taille annoncée et si les deux premiers octets forment un en-tête MP3 : premier octet `FF`, trois bits de poids fort du second à 1. L'application renomme alors le fichier en `<nom>.mp3`. Dans le cas contraire, elle le supprime et marque l'enregistrement en erreur.

Le flux reçu est du MP3 standard : MPEG-2 Layer III, 32 kbit/s, 16 kHz, mono, soit 4 000 octets par seconde. Il se lit sans conversion.

L'application interrompt le transfert si la liaison se coupe ou si aucun octet audio n'arrive pendant 20 secondes. Elle supprime alors le fichier partiel.

## Transfert en direct

Quand un enregistrement se termine pendant la connexion, le boîtier envoie `0x06` avec le statut, le nom et l'indicateur du nouvel enregistrement. L'application note cette trame au journal et l'ignore.

Un client qui envoie ensuite `0x15` sans payload déclenche le transfert en direct de cet enregistrement. Le boîtier répond par `0x15`, envoie l'audio sur la caractéristique du direct (`B0B3`), puis clôt l'envoi par une trame `0x07` qui donne le nom et la taille envoyée. Pour un enregistrement déjà en mémoire, le boîtier répond `01` (refus) à `0x15`.

Exemple d'échange observé entre le boîtier et un autre client que l'application, pour l'enregistrement `20260101120000` (24 480 octets). Ici `TX` désigne une trame écrite vers le boîtier par cet autre client et `RX` une trame reçue du boîtier :

```text
RX a00a01061000323032363031303131323030303001fa97   nouvel enregistrement 20260101120000
TX a00a01150091dc   demande du transfert en direct par l'autre client
RX a00a01151400323032363031303131323030303000000000015aa8   statut 00, nom 20260101120000, taille 0, indicateur 01
RX a00a01071500323032363031303131323030303000005fa001017f74   fin : statut 00, nom 20260101120000, taille 0x00005fa0 (24 480 octets)
```

L'application n'envoie pas `0x15`. Pendant le téléchargement d'un fichier avec `0x0b`, elle compte les octets reçus sur la caractéristique du direct et les ignore.
