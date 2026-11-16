# DIC-22 : compléter le protocole du MECHEN V05

## Intention et périmètre

DIC-22 complète `docs/protocole-v05.md` pour que DIC-9 puisse afficher la batterie et le stockage, lire et modifier les réglages du boîtier, et pour que DIC-23 puisse supprimer un enregistrement. Le travail produit uniquement de la documentation et des exemples synthétiques rejouables. Il ne modifie aucun code de dictee-app.

Le périmètre couvre le rétroéclairage, le support USB, la réduction du bruit, la durée maximale d'enregistrement, la batterie, le stockage et la suppression. Geoff a confirmé que DOWAY ne propose aucun réglage de gain : cette commande est donc exclue. La réinitialisation d'usine et les mises à jour du firmware sont également exclues.

## Approches

- **Retenue : compléter les preuves existantes.** La capture DOWAY du 11 novembre 2026 établit déjà les commandes `0x1a`, `0x13`, `0x17` et `0x16`. Les nouvelles recherches ciblent la suppression et les champs encore ambigus de `0x05`, puis un outil local rejoue chaque commande retenue.
- **Écartée : repartir d'une décompilation complète de DOWAY.** Cette approche répéterait le travail déjà réalisé sur les quatre réglages, sans garantir de décoder les champs d'état que la capture seule peut relier à des valeurs connues.
- **Écartée : documenter sans rejeu.** Une trame observée dans DOWAY ne prouve pas qu'un autre client saura la reproduire. Le ticket exige un rejeu par commande retenue.

## Sources et règles de preuve

Le rapport fourni par Geoff sert de point de départ. Les valeurs des quatre réglages et leurs réponses y sont déjà associées à des trames dont le CRC a été vérifié. La batterie est probablement à l'octet 9 de `0x05`, mais cette position et celle des autres champs doivent être confirmées par variation contrôlée.

La documentation distingue les faits confirmés des éléments laissés inconnus. Elle ne déduit ni une commande ni une unité d'une seule ressemblance de valeur. Chaque commande retenue possède une demande et une réponse observées, un exemple synthétique dont le CRC est recalculé, puis un rejeu réussi avec l'outil local. Une commande qui ne satisfait pas ces conditions reste hors du protocole documenté et empêche la clôture du ticket si elle appartient au périmètre.

Les captures réelles, les noms d'enregistrements réels, le code décompilé et l'outil de rejeu restent hors du dépôt public. Le document conserve les valeurs fictives déjà annoncées dans son introduction.

## Déroulé de la recherche

### 1. Consolider les réglages connus

Le travail reprend les trames du rapport pour le rétroéclairage (`0x1a`), le support USB (`0x13`), la réduction du bruit (`0x17` avec `00`, `05`, `09`, `0f`) et la durée (`0x16` avec `00`, `01`, `02`, `03`). L'outil local les rejoue une à une et vérifie le statut d'acceptation. Une lecture `0x05` suit chaque changement pour identifier le champ qui porte la nouvelle valeur. Les réglages d'origine sont rétablis à la fin.

Entrée : le boîtier appairé et le rapport du 11 novembre. Si une valeur diffère du rapport, la nouvelle observation prévaut et la divergence est consignée. La sortie est un tableau de correspondance entre commande, valeur, réponse et champ d'état. Le palier est validé quand chaque réglage est relu avec la valeur écrite. Il est abandonné pour une commande que le boîtier refuse deux fois, sans élargir le périmètre à du code d'application.

### 2. Identifier la batterie et le stockage

Les lectures `0x05` sont comparées entre elles et avec les informations affichées par DOWAY. La batterie est confirmée par sa correspondance avec le pourcentage affiché et, si possible, avec la notification `0x0f`. Les champs de stockage sont comparés avant et après une variation connue de l'espace occupé, en tenant compte de l'unité et de l'arrondi du boîtier.

Entrée : des lectures `0x05` et des valeurs de référence visibles dans DOWAY. Si l'arrondi empêche de trancher, une variation plus grande est nécessaire ; aucune unité n'est inventée. La sortie donne la position, le type, l'unité et le sens des champs confirmés. Le palier est validé quand batterie et stockage sont relus avec les valeurs attendues. À défaut, les champs non résolus restent explicitement inconnus et DIC-22 reste ouverte.

### 3. Capturer puis rejouer la suppression

Un enregistrement créé pour l'essai est sauvegardé avant toute suppression. Une capture Bluetooth ciblée enregistre la suppression effectuée depuis DOWAY, puis la disparition du fichier est vérifiée dans la liste `0x0a`. L'échange permet d'identifier la commande, le payload et le statut de réponse. L'outil local répète ensuite l'opération sur un second enregistrement d'essai, après accord explicite de Geoff pour chaque suppression déclenchée par l'agent.

Entrée : deux enregistrements d'essai identifiés et sauvegardés. Si DOWAY ne permet pas la suppression ou si la capture est inexploitable, aucune commande n'est devinée et une nouvelle capture ciblée est préparée. La sortie est un exemple synthétique complet et la preuve que l'enregistrement supprimé ne figure plus dans `0x0a`. Le palier est validé uniquement après ce rejeu indépendant.

### 4. Documenter et contrôler

`docs/protocole-v05.md` reçoit les commandes, les valeurs, les réponses, le décodage des champs confirmés de `0x05` et les exemples fictifs. La notification `0x0f` n'est documentée que dans la mesure prouvée. `docs/specification.md` n'est pas modifié, puisque le comportement livré de dictee-app ne change pas.

Les exemples sont contrôlés par recalcul du CRC et par vérification des longueurs. La relecture vérifie aussi qu'aucune donnée réelle n'entre dans le dépôt. La clôture de DIC-22 et toute modification de son statut dans Kaneo attendent la fin des rejeux et un accord explicite séparé.

## Approbation

Geoff a approuvé cette conception le 16 novembre 2026. Le plan d'exécution doit être relu et approuvé séparément avant toute recherche sur le boîtier ou modification du protocole documenté.

## Vérification finale

La vérification comprend la relecture du document, le contrôle automatisé des exemples et le compte rendu des rejeux sur le boîtier. Aucun test Gradle n'est nécessaire puisqu'aucun code ni fichier de construction n'est modifié. Le résultat indique séparément ce qui a été prouvé par la capture DOWAY, ce qui a été rejoué par l'outil local et ce qui reste inconnu.
