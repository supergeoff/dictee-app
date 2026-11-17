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

## Réglages et relecture de l'état

Les captures DOWAY du 11 novembre 2026 et les rejeux indépendants dans nRF Connect du 17 novembre 2026 distinguent les commandes observées des valeurs effectivement relues. Les captures brutes, adresses Bluetooth et identifiants réels restent hors du dépôt public.

Chaque commande de réglage porte un payload d'un octet. La réponse reprend la commande et porte `00` pour « accepté » ; elle ne renvoie pas la valeur réglée. Aucun refus de réglage n'a été observé. Lire `0x05` après chaque écriture pour vérifier la valeur.

| Réglage | Commande | Valeurs observées dans DOWAY | Rejeu indépendant vérifié |
|---|---|---|---|
| Rétroéclairage | `0x1a` | `00` désactivé, `01` activé | `00` et `01`, avec relecture |
| Support USB | `0x13` | `00` désactivé, `01` activé | `00` et `01`, avec cycle complet relu à l'index 14 |
| Réduction du bruit | `0x17` | `00` désactivé, `05` faible, `09` moyen, `0f` élevé | Les quatre valeurs, avec relecture |
| Durée des segments | `0x16` | `00` 30 minutes, `01` 1 heure, `02` 2 heures, `03` 3 heures | Les quatre valeurs, avec relecture |

Tous les niveaux de bruit ont été rejoués et relus. Aucun réglage de gain n'a été identifié. La réinitialisation d'usine et le firmware ne sont pas documentés ; aucune trame n'est inventée pour ces actions. La commande de suppression `0x0d` est observée dans DOWAY et son effet est confirmé par relecture nRF ; son émission indépendante depuis nRF est également validée par le rejeu du 17 novembre à 12:00.

### Trames de réglage observées

Les TX utilisent le CRC big-endian, les RX le CRC little-endian. Ces exemples ne contiennent pas d'identifiant personnel.

| Réglage | TX | RX accepté |
|---|---|---|
| Rétroéclairage activé | `a00a011a0101ca60` | `a00a011a0100a10a` |
| Rétroéclairage désactivé | `a00a011a01000aa1` | `a00a011a0100a10a` |
| USB désactivé | `a00a011301000871` | `a00a011301007108` |
| USB activé | `a00a01130101c8b0` | `a00a011301007108` |
| Bruit élevé | `a00a0117010fcd70` | `a00a0117010030c9` |
| Bruit moyen | `a00a01170109cff0` | `a00a0117010030c9` |
| Bruit faible | `a00a01170105caf0` | `a00a0117010030c9` |
| Bruit désactivé | `a00a01170100c930` | `a00a0117010030c9` |
| Durée 3 heures | `a00a011601030821` | `a00a011601006109` |
| Durée 1 heure | `a00a01160101c9a0` | `a00a011601006109` |
| Durée 30 minutes | `a00a011601000961` | `a00a011601006109` |
| Durée 2 heures | `a00a01160102c8e0` | `a00a011601006109` |

### Champs du payload de 52 octets de `0x05`

Tous les index ci-dessous commencent à zéro dans le payload, sans les cinq octets d'en-tête ni le CRC.

| Index | Interprétation | Preuve et limite |
|---|---|---|
| 0 à 3 | Capacité totale, entier big-endian | 59 934, cohérent avec 59,93 GB affichés par DOWAY ; conversion exacte en octets non établie |
| 4 à 7 | Espace libre, entier big-endian | 59 911 → 59 910 après ajout de 1 323 968 octets ; compteur grossier d'environ un MB, base et arrondi exacts non établis |
| 9 | Batterie en pourcentage | Confirmé : DOWAY affiche 71 % le 17 novembre à 12:00:23, puis les quatre lectures nRF proches donnent `47` hexadécimal, soit 71 décimal |
| 10 | Sens inconnu | Reste `00` pendant le test de rétroéclairage ; ce n'est pas le champ confirmé |
| 11 | Sens inconnu | Reste `01` pendant le rejeu USB ; ce n'est pas le champ qui change |
| 12 | Sens inconnu | Reste `02` pendant le test de durée ; ce n'est pas le champ de durée qui a été confirmé |
| 14 | Support USB | Confirmé : second rejeu `0x13`, `01` → `00` → `01`, seul octet modifié |
| 17 à 25 | Nom interne ASCII terminé par zéro | Observé, ne pas confondre avec le numéro de série |
| 39 | Réduction du bruit | Confirmé : `00` → `09` → `00`, puis `00` → `05` → `0f` → `00` ; seul champ modifié par ces réglages |
| 41 | Durée des segments | Confirmé : `02` → `03` → `02`, puis `02` → `00` → `01` → `02` ; seul champ modifié par ces réglages |
| 42 | Rétroéclairage | Confirmé : `00` → `01` → `00`, seul octet modifié lors du rejeu |

Le rejeu bruit du 17 novembre à 06:39 reçoit deux acquittements `0x17` acceptés et trois états de 52 octets. Seul l'index 39 varie ; l'état final est identique à l'état initial. Le rejeu durée à 06:43 reçoit deux acquittements `0x16` acceptés et trois états ; seul l'index 41 varie, puis revient à sa valeur initiale. Les longueurs et CRC de ces réponses ont été contrôlés. Le rejeu rétroéclairage du 17 novembre à 06:53 reçoit deux acquittements `0x1a` acceptés et trois états ; seul l'index 42 varie (`00` → `01` → `00`). Les six réponses ont des longueurs et CRC valides, et l'état final est identique à l'état initial. L'index 10 reste inchangé, ce qui écarte l'hypothèse initiale de localisation du rétroéclairage à cet index. Le rejeu groupé du 17 novembre à 07:47 complète les listes : bruit `00` → `05` → `0f` → `00` à l'index 39, puis durée `02` → `00` → `01` → `02` à l'index 41. Les six réglages sont acquittés `00`, les sept lectures d'état montrent seulement le champ attendu qui varie à chaque étape, et l'état final est strictement identique à l'état initial. Les 14 notifications ont des longueurs et CRC valides. Toutes les options des quatre réglages ont ainsi été rejouées et relues sur le boîtier testé ; cela n'établit pas leur effet acoustique, la durée effective d'un enregistrement ni l'accès physique par câble USB.

Le rejeu USB du 17 novembre à 07:23 reçoit deux acquittements `0x13` acceptés et trois états, tous avec longueurs et CRC valides. L'état initial et celui après désactivation sont identiques ; après activation, seul l'index 14 passe de `00` à `01`. L'index 11 reste `01` sur les trois lectures. Contrairement aux autres cycles, l'état final n'est donc pas identique à l'état initial : le retour fixe sur Activé n'est pas une restauration de la valeur réellement lue au départ. Le second rejeu du 17 novembre à 07:30 vérifie ensuite le cycle complet `01` → `00` → `01` à l'index 14 : les deux commandes sont acceptées, les six réponses ont des longueurs et CRC valides, seul cet index varie, et l'état final est strictement identique à l'état initial. Le champ USB à l'index 14 est donc confirmé dans les deux sens. L'accès réel aux fichiers par câble USB n'a pas été testé.

### Suppression définitive observée dans DOWAY

La capture HCI du 17 novembre couvrant 11:02:37 à 11:06:29 contient 4 000 enregistrements sans troncature et 28 trames V05 complètes aux longueurs et CRC valides. La liste initiale est complète (10 fichiers, marqueur `0x0a 02`). Deux actions de suppression définitive sont suivies de requêtes `0x0d` à 11:03:52 et 11:03:55, chacune acquittée avec une charge utile `00`.

La requête `0x0d` contient exactement les 14 octets ASCII du nom d'enregistrement, sans extension ni terminateur NUL dans ces deux observations (longueur `0e`). Les deux noms correspondent à des entrées de la liste initiale. La réponse est `a00a010d0100110e` (CRC RX little-endian). Cette observation identifie la commande envoyée par DOWAY pour la suppression définitive et son acquittement ; aucune relecture de liste après ces commandes n'est présente dans cette capture seule. La lecture nRF suivante à 11:15:43 confirme cependant la disparition des deux fichiers : la liste complète passe de 10 à 8 entrées, sans ajout et sans changement des noms ou tailles des huit autres fichiers. Les deux entrées retirées faisaient 28 690 et 30 702 octets, soit 59 392 octets au total. Les 13 notifications de cette lecture ont des longueurs et CRC valides, le marqueur de fin est reçu et les deux états encadrant la liste sont identiques. Les compteurs total/libre restent 59 934 / 59 911 ; aucune variation d'unité entière n'est observée pour ce petit volume. La suppression effective sur le boîtier est donc confirmée par une relecture indépendante après reconnexion. Le rejeu indépendant nRF du 17 novembre à 12:00 est confirmé par le journal reçu : acquittement `00` à 12:00:53, listes complètes de 8 puis 7 puis 7 entrées. Seul le fichier d'essai ciblé de 1 323 968 octets disparaît ; les sept autres noms et tailles sont inchangés et les deux listes après suppression sont identiques. Les 40 notifications du journal ont des longueurs et CRC valides. Quatre lectures d'état donnent une capacité constante de 59 934, un espace libre passant de 59 911 à 59 912 dans la dernière lecture, et une batterie candidate constante à 71. Ce décalage de mise à jour invite à relire les compteurs plutôt qu'à attendre nécessairement une variation immédiate après l'acquittement. Ne pas rejouer une telle commande sans accord explicite sur un fichier d'essai sauvegardé. Les noms réels et la capture brute ne sont pas conservés dans le dépôt public.

Exemple synthétique pour le nom fictif `20260102030405` (ne pas exécuter sans fichier d'essai autorisé) : TX `a00a010d0e32303236303130323033303430355b62`, RX d'acquittement `a00a010d0100110e`. La demande a une charge utile de 14 octets et un CRC TX big-endian ; la réponse a une charge utile d'un octet et un CRC RX little-endian. Cet exemple est calculé, pas une preuve de rejeu.

### Mesure du stockage

La capture d'écran DOWAY du 17 novembre à 08:00 affiche « 23 MB sur 59.93 GB utilisés ». Les deux premiers entiers big-endian de `0x05` valent 59 934 et 59 911 : leur différence est 23 et 59 934 / 1 000 = 59,934. Cela étaye l'interprétation capacité totale et espace libre en unités affichées comme MB par DOWAY, mais ne confirme pas encore l'unité exacte en octets ni l'arrondi.

Les mesures nRF à 08:06 et 08:08 lisent chacune `0x05`, préparent la liste par `0x18 00`, lisent `0x0a` jusqu'au marqueur de fin `a00a010a0102210e`, puis relisent `0x05`. Les 37 notifications ont des longueurs et CRC valides et chaque liste est complète. La liste passe de 13 à 14 entrées ; les anciennes restent identiques, et le seul ajout fait 44 666 octets (environ 11,17 secondes à 4 000 octets/s). Les sommes des tailles passent de 21 252 842 à 21 297 508 octets.

Les compteurs restent 59 934 et 59 911 avant comme après, y compris après la liste. Seule la batterie candidate change de 75 à 74. Ce fichier trop petit ne permet pas d'observer le pas d'arrondi du stockage ; il ne confirme ni ne réfute l'hypothèse capacité/libre en MB. La mesure suivante à 08:18, décrite ci-dessous, ajoute un enregistrement assez long pour observer une variation. Ne pas confondre somme des fichiers audio et occupation totale du système de fichiers. Les noms réels des enregistrements ne sont pas conservés dans ce document public.

La mesure du 17 novembre à 08:18 contient 20 notifications dont les longueurs et CRC sont valides, une liste complète de 15 fichiers et deux états identiques encadrant cette liste. Les 14 entrées précédentes restent inchangées ; le seul ajout fait 1 323 968 octets, soit environ 331 secondes au débit de 4 000 octets/s. La somme des tailles atteint 22 621 476 octets. Le premier compteur reste 59 934 et le second diminue de 59 911 à 59 910. Le sens espace libre du second compteur est ainsi étayé par une variation cohérente avec l'ajout, et le premier correspond à la capacité affichée par DOWAY. Ce seul pas de compteur ne distingue pas une unité décimale de 1 000 000 octets d'une unité binaire de 1 048 576 octets, ni le mode d'arrondi. Ne pas présenter une conversion en octets comme exacte. Aucun fichier n'a disparu entre ces deux listes ; aucun effacement n'était requis pour cette mesure.

### Diagnostic de connexion nRF Connect

Les trois connexions de la capture DOWAY négocient un MTU effectif de 247, activent les notifications B0B2, B0B3 et B0B4, lisent 16 octets avec `0x02`, puis rejouent ces octets suivis de `00` avec `0x03`, accepté.

Dans les essais nRF, la même lecture `0x02` retourne une trame valide `a00a01020100210d`, dont le payload est un seul octet `00`. Sa signification reste inconnue : ne pas la traiter comme un identifiant de 16 octets ni comme un appairage accepté. Les connexions sans appairage accepté se terminent après environ 4,9 secondes, avec statut BLE 19 (terminées par le pair). La cause exacte n'est pas établie. MTU 247 seul, puis ajout des trois abonnements, n'ont pas changé cette réponse courte.

Le rejeu direct de l'appairage réellement capturé, sans lecture `0x02`, est accepté dans nRF et permet ensuite `0x05` et les tests de réglage. Le journal montre une connexion maintenue plus de cinq minutes. Cela valide ce rejeu sur ce boîtier, pas une procédure universelle pour un autre appareil. Les identifiants fictifs de ce document ne doivent jamais être rejoués sur un boîtier réel.

Les macros privées exécutent : MTU 247, écriture `0100` des CCCD de B0B2/B0B3/B0B4, appairage capturé, attente de sa réponse, puis lectures et réglages. Une assertion nRF peut signaler une différence sans arrêter la suite ; le journal doit donc être contrôlé. Une déconnexion peut empêcher le retour à la valeur de départ. Les macros ne restaurent pas dynamiquement l'état : elles terminent sur une valeur fixe connue avant l'essai. Ne pas les lancer en boucle ni pendant un enregistrement important.

### Notification spontanée `0x0f`

Des notifications de 20 octets ont été reçues sans demande, dont deux espacées d'environ cinq minutes dans le journal nRF. L'index 1 du payload est cohérent avec la batterie, sans confirmation indépendante. Les autres champs restent inconnus. Journaliser ces notifications sans les confondre avec la réponse d'une commande attendue.

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
