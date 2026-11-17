# DIC-22 : compléter le protocole V05 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Documenter et rejouer les réglages, l'état et la suppression du MECHEN V05 sans modifier dictee-app.

**Architecture:** Les trames déjà capturées dans DOWAY servent de point de départ. Des manipulations ciblées avec DOWAY et un client GATT indépendant sur le téléphone vérifient les commandes et font varier les champs d'état. Seul `docs/protocole-v05.md` entre dans le dépôt, avec des exemples synthétiques contrôlés par un script Python jetable.

**Tech Stack:** Markdown, Bluetooth Low Energy GATT, journal HCI Android, DOWAY, nRF Connect ou client GATT équivalent, Python 3 sans dépendance.

**Spec:** `docs/superpowers/specs/2026-11-16-dic22-protocole-design.md`

## Global Constraints

- Aucun fichier de `app/`, aucune dépendance et aucun outil de construction ne change.
- Le gain, la réinitialisation d'usine et les mises à jour du firmware sont hors périmètre.
- Les exemples publics utilisent les valeurs fictives déjà déclarées dans `docs/protocole-v05.md` ; aucune capture, donnée réelle ou copie du code DOWAY n'entre dans le dépôt.
- Une commande retenue doit avoir été observée, documentée avec sa réponse, puis rejouée une fois par un client indépendant.
- Chaque suppression demandée par l'agent nécessite l'accord explicite de Geoff immédiatement avant l'action et porte sur un enregistrement d'essai sauvegardé.
- Les push, fusions, publications et changements de statut Kaneo attendent des accords séparés.
- Les manipulations physiques sont faites par Geoff ; l'agent analyse les valeurs et met à jour la documentation après chaque palier vérifié.

## Review Focus

- Une réponse de statut non nulle ne doit pas être présentée comme une réussite : la tâche 1 impose l'arrêt du rejeu concerné.
- Une réponse `0x05` identique après écriture peut être périmée : la tâche 1 impose une nouvelle connexion avant de conclure.
- Le stockage peut être arrondi ou exprimé dans une autre unité : la tâche 2 interdit l'unité tant que la variation connue ne la confirme pas.
- Une suppression peut viser le mauvais enregistrement : la tâche 3 impose la vérification du nom avant envoi et sa disparition dans `0x0a` après envoi.
- Des identifiants réels peuvent rester dans un exemple copié : la tâche 4 impose une revue de toutes les trames ajoutées et le remplacement par les fixtures fictives.

---

### Task 1: Rejouer les quatre réglages et lire leurs valeurs

**Files:**
- Modify: `docs/protocole-v05.md`, sections « Commandes » et « Lecture de l'état avec 0x05 » à créer.
- External: notes et captures conservées hors du dépôt, dans `/home/supergeoff/projects/v05-ble-dic22/`.

**Interfaces:**
- Consumes: les commandes et valeurs du rapport fourni par Geoff, `0x1a`, `0x13`, `0x17`, `0x16`, et la lecture `0x05`.
- Produces: un tableau public commande/valeur/réponse et une correspondance vérifiée entre chaque réglage et son octet dans `0x05`.

- [ ] **Step 1: Préparer la session GATT et l'état initial**

Geoff installe ou ouvre nRF Connect, force l'arrêt de DOWAY et de dictee-app, puis connecte le MECHEN V05. Il active les notifications de la caractéristique `B0B2`, écrit sans réponse sur `B0B1`, effectue l'appairage avec `0x02` puis `0x03`, et lit `0x05`. Les trames réelles sont notées hors du dépôt avec l'état affiché par DOWAY. Si le client ne permet pas l'écriture sans réponse ou les notifications, ce palier s'arrête et le client est remplacé avant toute autre étape.

- [ ] **Step 2: Rejouer chaque valeur avec un état relu**

Pour chaque ligne du tableau ci-dessous, Geoff écrit la commande sur `B0B1`, note la réponse `00`, puis envoie `0x05` et note le payload reçu. Les changements sont espacés et une seule commande est en cours. L'ordre choisi termine sur les valeurs d'origine relevées à l'étape 1.

| Commande | Valeurs à rejouer |
|---|---|
| `0x1a` | `01`, puis `00` |
| `0x13` | `00`, puis `01` |
| `0x17` | `05`, `09`, `0f`, puis `00` |
| `0x16` | `03`, `01`, `00`, puis `02` |

Expected: chaque écriture reçoit la même commande avec le statut `00`, et une lecture `0x05` distincte montre un seul changement cohérent avec la valeur écrite. Un statut différent arrête le rejeu de cette commande. Si deux lectures successives sont strictement identiques alors que DOWAY affiche la nouvelle valeur, Geoff reconnecte le client et relit `0x05` avant toute conclusion. Si l'appairage ne peut pas être rejoué dans le client indépendant, aucune écriture de réglage n'est tentée et le palier reste bloqué.

- [ ] **Step 3: Documenter les commandes et les champs prouvés**

Ajouter au document les quatre réglages, leurs seules valeurs autorisées, le statut de réponse et les positions des octets confirmés dans le payload de 52 octets. Expliquer que la réponse ne reprend pas la valeur réglée et que `0x05` doit être relue. Utiliser uniquement les noms et valeurs fictifs du document pour les exemples.

- [ ] **Step 4: Créer le contrôleur jetable de trames**

Créer `/home/supergeoff/projects/v05-ble-dic22/check_frames.py` avec la seule bibliothèque standard. Le script extrait les trames préfixées `TX` et `RX`, vérifie l'en-tête `a00a01`, la longueur du payload et le CRC-16/ARC, lit le CRC en big-endian pour TX et en little-endian pour RX, puis signale toute trame hexadécimale nouvelle sans sens connu. Son autotest vérifie que les neuf octets ASCII `123456789` donnent `0xbb3d` et qu'un CRC inversé du mauvais côté est refusé.

- [ ] **Step 5: Contrôler les exemples et le périmètre**

Run: `python3 /home/supergeoff/projects/v05-ble-dic22/check_frames.py docs/protocole-v05.md`
Expected: toutes les trames ajoutées sont valides, l'autotest du CRC passe et les exemples ne contiennent aucun nom réel.

- [ ] **Step 6: Commit**

```bash
git add docs/protocole-v05.md
git commit -m "docs: documenter les réglages du V05"
```

### Task 2: Confirmer la batterie et le stockage

**Files:**
- Modify: `docs/protocole-v05.md`, section « Lecture de l'état avec 0x05 ».
- External: mesures dans `/home/supergeoff/projects/v05-ble-dic22/`.

**Interfaces:**
- Consumes: la procédure d'appairage et la structure du payload `0x05` de la tâche 1.
- Produces: la position, l'unité et le sens des champs batterie et stockage, ou un blocage explicite qui maintient DIC-22 ouverte.

- [ ] **Step 1: Corréler la batterie avec DOWAY**

Relever le pourcentage affiché par DOWAY et les payloads `0x05` et `0x0f` reçus au même moment. Répéter après une variation de charge si elle est disponible sans attendre artificiellement. Vérifier que l'octet candidat de la tâche 1 correspond à chaque pourcentage affiché. Si `0x0f` n'arrive pas, la batterie peut être validée sans cette notification.

- [ ] **Step 2: Mesurer une variation connue du stockage**

Relever les deux entiers candidats et les tailles des enregistrements présents. Créer un enregistrement d'essai dont la taille dépasse le pas d'arrondi constaté, relire `0x05`, puis comparer la variation du champ avec la taille ajoutée. Ne supprimer aucun enregistrement dans cette tâche. Si la capacité affichée par DOWAY et la variation utilisée ne désignent pas les mêmes champs ou la même unité, conserver les champs comme inconnus et demander une nouvelle mesure plutôt que choisir une interprétation.

- [ ] **Step 3: Rejouer la lecture et documenter les champs**

Depuis une nouvelle connexion du client indépendant, envoyer `0x05`, vérifier que la batterie et le stockage correspondent aux valeurs visibles dans DOWAY, puis documenter les positions, le big-endian, l'unité et l'arrondi éventuel. Décrire `0x0f` seulement si son champ batterie a été observé ; laisser les autres octets inconnus.

- [ ] **Step 4: Vérifier les exemples d'état**

Run: `python3 /home/supergeoff/projects/v05-ble-dic22/check_frames.py docs/protocole-v05.md`
Expected: les exemples fictifs de `0x05` ont 52 octets de payload, un CRC valide et des valeurs cohérentes avec les unités documentées.

- [ ] **Step 5: Commit**

```bash
git add docs/protocole-v05.md
git commit -m "docs: décoder l'état du V05"
```

### Task 3: Capturer et rejouer la suppression

**Files:**
- Modify: `docs/protocole-v05.md`, sections « Commandes » et « Suppression d'un enregistrement » à créer.
- External: capture HCI et notes dans `/home/supergeoff/projects/v05-ble-dic22/`.

**Interfaces:**
- Consumes: la lecture de liste `0x0a` et la session GATT déjà documentées.
- Produces: une commande de suppression avec payload, réponse et preuve de disparition dans `0x0a`.

- [ ] **Step 1: Créer et sauvegarder deux enregistrements d'essai**

Geoff crée deux enregistrements suffisamment courts pour l'essai, relève leurs noms dans `0x0a`, puis les récupère avec `0x0b` ou DOWAY avant toute suppression. Les noms et fichiers réels restent hors du dépôt. Sans deux fichiers sauvegardés et lisibles, la tâche s'arrête.

- [ ] **Step 2: Capturer la première suppression dans DOWAY**

Après avoir réinitialisé le journal HCI selon la procédure du rapport, Geoff supprime le premier enregistrement dans DOWAY, puis rouvre la liste. L'agent extrait les trames autour de l'action, vérifie le CRC et identifie la commande dont le payload contient le nom de 14 caractères. Si plusieurs commandes candidates restent possibles, créer et sauvegarder un enregistrement d'essai supplémentaire pour répéter la capture au lieu de choisir arbitrairement ; le second enregistrement reste réservé au rejeu indépendant.

- [ ] **Step 3: Demander l'accord puis rejouer la suppression**

Présenter à Geoff la commande candidate, le nom exact du second enregistrement et le résultat attendu. Attendre son accord explicite pour cette suppression. Geoff envoie ensuite la trame avec le client GATT indépendant, note le statut, exécute `0x18` avec `00` puis `0x0a`, et vérifie que seul l'enregistrement visé a disparu. Un statut non nul, une absence de réponse ou la disparition d'un autre nom est un échec à rapporter, pas une réussite.

- [ ] **Step 4: Documenter la commande vérifiée**

Ajouter le format de la demande et de la réponse, le délai de 5 secondes déjà commun aux commandes, et un exemple fictif utilisant `20260101120000`. Indiquer qu'il faut relire `0x0a` pour constater la suppression. Ne documenter aucun comportement de corbeille ou de récupération qui n'a pas été observé.

- [ ] **Step 5: Vérifier la trame et committer**

Run: `python3 /home/supergeoff/projects/v05-ble-dic22/check_frames.py docs/protocole-v05.md`
Expected: la demande et la réponse fictives ont des longueurs et des CRC valides, et le nom fictif fait exactement 14 octets ASCII.

```bash
git add docs/protocole-v05.md
git commit -m "docs: documenter la suppression sur le V05"
```

### Task 4: Relire et valider la documentation complète

**Files:**
- Modify: `docs/protocole-v05.md` uniquement si la relecture relève une incohérence.
- Verify: `docs/superpowers/specs/2026-11-16-dic22-protocole-design.md`.

**Interfaces:**
- Consumes: les trois paliers précédents et leurs comptes rendus de rejeu.
- Produces: la documentation DIC-22 prête pour DIC-9 et DIC-23, avec les limites restantes explicitement séparées des faits.

- [ ] **Step 1: Comparer le document au périmètre approuvé**

Vérifier la présence des quatre réglages, de la batterie, du stockage et de la suppression, ainsi que l'absence du gain, de la réinitialisation d'usine, du firmware et de tout changement dans `docs/specification.md`. Si un élément du périmètre n'a pas passé son rejeu, laisser DIC-22 ouverte et ne pas le présenter comme documenté.

- [ ] **Step 2: Vérifier toutes les trames et les fixtures**

Run: `python3 /home/supergeoff/projects/v05-ble-dic22/check_frames.py docs/protocole-v05.md`
Expected: toutes les trames préfixées TX/RX du document passent le contrôle de longueur et de CRC. Vérifier aussi que les exemples conservent `123456`, `SN00000001` et des dates de janvier 2026, sans identifiant ou nom réel.

- [ ] **Step 3: Contrôler le diff et l'absence de code modifié**

Run: `git diff --check main...HEAD && git diff --stat main...HEAD && git status --short`
Expected: aucune erreur d'espace ; seul `docs/protocole-v05.md` apparaît dans les commits de la branche d'exécution, la conception et le plan étant déjà sur `main`. Le fichier joint et les notes réelles restent non suivis et ne sont jamais ajoutés.

- [ ] **Step 4: Committer les éventuelles corrections de revue**

```bash
git add docs/protocole-v05.md
git commit -m "docs: fiabiliser les exemples du protocole V05"
```

Si aucune correction n'est nécessaire, ne pas créer de commit vide. Présenter ensuite le résultat, les rejeux effectivement observés et les éventuels champs restés inconnus. Demander un accord séparé avant toute clôture de DIC-22 ou action GitHub.
