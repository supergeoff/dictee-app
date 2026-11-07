# Dictée : spécification du produit

Statut : les versions 0.2.x livrent le lot 1, les lots 2 à 4 restent à réaliser. La maquette visuelle qui sert de référence aux écrans n'est pas publiée, et les numéros d'écran de ce document reprennent sa numérotation.

Le protocole Bluetooth du MECHEN V05 ne change pas. Il est décrit dans [`protocole-v05.md`](protocole-v05.md).

## Objectif

Dictée récupère les enregistrements du MECHEN V05 par Bluetooth et les range dans un dossier du téléphone. Elle les fait transcrire puis résumer par des endpoints au format OpenAI, et peut envoyer le tout dans un dossier Google Drive. La chaîne est automatique, mais elle s'arrête une fois pour une relecture : l'utilisateur valide la synthèse avant que le MD soit enregistré. Les lots 1 à 3 n'ont pas de diarisation. Le lot 4, à activer plus tard, ajoute la diarisation et un second arrêt pour nommer les intervenants avant l'enregistrement du VTT.

## Écart avec la version 0.1.5

| Sujet | Version 0.1.5 | Cible |
|---|---|---|
| Écrans | Un écran unique qui contient aussi le journal | Une liste des enregistrements, une fiche par enregistrement, une page Réglages et trois sous-écrans |
| Boîtier | Recherché depuis l'écran principal | Associé une fois au premier lancement, puis changé depuis Réglages |
| Fichiers | Copie interne, supprimée après le dépôt dans Drive | Dossier du téléphone choisi par l'utilisateur, où les fichiers restent |
| Noms | `<nom>.mp3` | `Note-<nom>` ou `Call-<nom>`, avec les extensions `.mp3`, `.vtt` et `.md` |
| Transcription et synthèse | Absentes | Automatiques, avec une relecture de la synthèse |
| Drive | Obligatoire, dossier choisi avec le sélecteur Google | Optionnel, dossier choisi dans un écran de l'app |
| Journal | Dernière synchronisation, sur l'écran principal | Écran dédié, sept jours conservés, export |
| Interface | Vues Android construites en code, boutons bleu marine | Jetpack Compose, design system noir et blanc |

La version 0.1.6 prévue (renouvellement du jeton Google, nouvel essai sur une erreur 401, préfixes Note- et Call-) n'est pas livrée seule : ses changements entrent dans le lot 1.

## Décisions

| Sujet | Décision | Raison |
|---|---|---|
| Organisation des écrans | Une liste centrale, une fiche par enregistrement et une page Réglages | Un enregistrement n'apparaît qu'à un endroit, avec un seul état lisible d'un coup d'œil. |
| Chaîne de traitement | Récupération, transcription, synthèse, puis validation du MD. La chaîne part seule après la récupération et un réglage la coupe. | La validation du MD reste un point d'arrêt. |
| Accès à Drive | Le droit `drive.file` est conservé. L'écran Dossier Drive liste les dossiers connus de l'app, et « Autre dossier… » ouvre le sélecteur Google. | Un accès complet à Drive imposerait l'écran d'application non validée et une autorisation qui expire au bout de sept jours en mode test. |
| Stockage sur le téléphone | Dossier choisi avec le sélecteur de dossiers d'Android (Storage Access Framework), avec une permission persistante | C'est la seule façon d'écrire des MP3, VTT et MD dans un dossier visible par l'utilisateur et de les relire après une réinstallation de l'app. |
| Noms de fichiers | `<Type>-<nom>.<extension>`, avec le type Note ou Call | Les noms sont identiques sur le téléphone et dans Drive, et ils correspondent aux exports de DOWAY. |
| Interface | Jetpack Compose, avec un thème construit sur les tokens du design system | La liste groupée, les onglets, la feuille du bas et le thème sombre demanderaient beaucoup plus de code en vues construites à la main. Ce choix remplace la règle « sans framework d'interface » de la version 0.1.x. |
| Travaux longs | Un service de premier plan, avec une notification qui reprend la barre d'avancement | À 15 Ko/s, un appel de 41 minutes met environ 11 minutes à arriver. L'utilisateur peut quitter l'app pendant ce temps. |
| Clés d'API | Chiffrées avec une clé du Keystore Android | Elles ne sont jamais stockées en clair ni écrites au journal. |
| Diarisation | Les lots 1 à 3 n'en ont pas : la transcription passe seule par l'endpoint au format OpenAI. La diarisation et la relecture des intervenants forment le lot 4, à activer plus tard. | La transcription avance avec un endpoint au format OpenAI, et la diarisation vient avec un endpoint dédié, branché plus tard. |
| Source de vérité | Le dossier du téléphone fait foi pour le MP3, le VTT et le MD. La liste du boîtier fait foi pour ce qui est sur le boîtier, et le dossier Drive pour ce qui est dans Drive. L'état local ne garde que ce qui ne se déduit pas de ces trois sources. | Une seule source de vérité par information. |
| Design system | Les tokens du design system de l'app, en thème clair, sombre ou système | Un design noir et blanc. |

## Parcours

1. Au premier lancement, aucun boîtier n'est associé. L'utilisateur touche « Rechercher le boîtier », accorde la permission Bluetooth, puis associe le boîtier trouvé. L'app lit aussitôt la liste du boîtier.
2. À chaque ouverture, l'app se connecte au boîtier associé et relit sa liste. La liste affiche les enregistrements du boîtier, du téléphone et de Drive, groupés par jour.
3. « Récupérer (N) » transfère les enregistrements absents du téléphone et de Drive. Au premier besoin, l'app demande le dossier du téléphone.
4. Chaque enregistrement récupéré part en transcription. Le VTT s'enregistre dès son retour, à côté du MP3, et la synthèse part aussitôt.
5. Quand la synthèse revient, l'enregistrement passe à « À relire ». L'utilisateur relit le MD dans l'onglet Synthèse, puis le valide ou le relance. L'enregistrement est alors « Prêt ».
6. Si Drive est activé, « Envoyer (N) » dépose dans le dossier choisi les fichiers qui y manquent.

## Écrans

Cette section ne reprend que les règles que la maquette ne montre pas.

### Écran 2, premier lancement, et écran 3, recherche du boîtier

- L'écran 2 s'affiche tant qu'aucun boîtier n'est associé. La pastille indique « Aucun boîtier » et Réglages reste accessible.
- La recherche dure 15 secondes et reconnaît un boîtier à son nom (`MECHEN…`) ou aux données fabricant de son annonce, comme en 0.1.5.
- Si un boîtier MECHEN est déjà connecté au téléphone par une autre app, le message d'aide le dit et propose de forcer l'arrêt de DOWAY.
- « Associer » enregistre l'adresse et le nom du boîtier, ferme la feuille et lance la lecture de la liste. Depuis Réglages, l'association d'un nouveau boîtier remplace l'ancien.

### Écran 4, enregistrements, et écran 5, sélection

- La liste réunit trois sources : la dernière liste lue sur le boîtier, le dossier du téléphone et le dossier Drive (quand Drive est activé). Elle est groupée par jour, du plus récent au plus ancien. Un enregistrement absent du boîtier reste listé tant qu'un de ses fichiers existe sur le téléphone ou dans Drive.
- La pastille du boîtier montre un point plein quand la liaison est ouverte et un point vide sinon. Un toucher relance la connexion. Tirer la liste vers le bas relit le boîtier, le dossier du téléphone et le dossier Drive.
- Les filtres sont « Tous », « À relire » et « Sur le boîtier », chacun avec son compteur.
- « Récupérer (N) » compte les enregistrements du boîtier qui ne sont ni sur le téléphone ni dans Drive. Il se grise si le boîtier est hors de portée ou si une récupération tourne déjà.
- « Envoyer (N) » n'apparaît que si Drive est activé. Il compte les enregistrements dont au moins un fichier du téléphone manque dans le dossier Drive. Il se grise pendant un envoi.
- Un appui long passe en sélection. Les deux boutons portent alors sur les lignes cochées. Récupérer une ligne cochée la transfère de nouveau depuis le boîtier, quel que soit son état. « Tout » coche les lignes du filtre en cours.
- La barre d'avancement affiche « Récupération i/N · p % » ou « Envoi i/N · p % ». Pour un envoi, N compte les fichiers (MP3, VTT et MD), et p est le pourcentage du fichier en cours. Les deux barres peuvent s'afficher l'une sous l'autre.

### Écrans 6 et 7, fiche d'un enregistrement

- Le titre donne le type, la date et l'heure. Le sous-titre donne la durée et l'état.
- Le menu propose « Récupérer à nouveau », « Relancer la transcription » et « Relancer la synthèse ». Une action sans objet est grisée, par exemple la synthèse tant qu'aucun VTT n'est enregistré.
- Le lecteur lit le MP3 du téléphone avec le lecteur média d'Android. Toucher un segment lance la lecture à son début, et le segment lu est surligné.
- Quand la chaîne automatique est coupée, la fiche propose un bouton « Transcrire » tant qu'aucune transcription n'existe, puis un bouton « Résumer » une fois le VTT enregistré.
- Onglet Transcription : il affiche les segments du VTT enregistré, avec leur horodatage et leur texte, sans nom d'intervenant (écran 6). Il n'y a rien à valider. Quand la transcription est relancée et qu'un MD existe déjà, l'app propose de relancer aussi la synthèse.
- Onglet Synthèse : il reste grisé tant qu'aucun VTT n'existe. Il affiche le Markdown mis en forme, le modèle utilisé et le nom du prompt. « Relancer » remplace le brouillon par une nouvelle réponse. « Valider et enregistrer » écrit le MD dans le dossier du téléphone.
- Bandeau d'erreur : il s'affiche sous les onglets avec la raison en clair et un bouton « Réessayer ».

### Écran 8, réglages

Les sections sont Boîtier, Stockage sur le téléphone, Automatisation, Transcription, Synthèse, Google Drive, Apparence et À propos, comme dans la maquette. Les règles suivantes s'y ajoutent.

- « Tester » appelle `GET {base}/models` avec la clé et affiche « Joignable · 420 ms » ou l'erreur reçue.
- Google Drive : « Déconnecter » révoque l'accès de l'app avec `AuthorizationClient.revokeAccess`, puis désactive l'envoi.
- À propos affiche la version installée et ouvre le journal.

### Écran 9, prompt et format

- Le prompt et le format vivent dans les réglages de l'app, pas dans son code.
- Les variables `{{type}}`, `{{date}}` et `{{duree}}` s'insèrent au curseur. La variable `{{intervenants}}` arrive avec le lot 4. Une variable inconnue reste telle quelle.
- La ligne de la page Réglages affiche « Compte rendu (par défaut) » tant que le texte d'origine est en place, et « Personnalisé » ensuite.

### Écran 10, dossier Drive

- La liste montre les dossiers connus : ceux que l'app a créés et ceux ouverts une fois avec le sélecteur Google. Un dossier devenu inaccessible (supprimé, dans la corbeille ou sans droit d'ajout) s'affiche grisé avec la mention « Inaccessible ».
- Un toucher choisit le dossier et revient aux réglages. « Nouveau dossier » demande un nom et crée le dossier à la racine du Drive.
- « Autre dossier… » ouvre le sélecteur Google, réglé pour les dossiers comme en 0.1.5. Le dossier retenu est contrôlé (`capabilities.canAddChildren`, hors corbeille), puis ajouté aux dossiers connus.

### Écran 11, journal

- Une ligne par événement : heure, source et message. Les sources sont APP, BLE, FS, STT, LLM et DRIVE.
- Le journal garde un fichier par jour sur sept jours. Les fichiers plus anciens sont supprimés à l'ouverture de l'app.
- « Copier » copie les lignes affichées avec le filtre en cours. « Exporter » écrit un fichier `.txt` des sept jours et ouvre le partage d'Android. « Vider » efface le journal après confirmation.

## Données

### Enregistrement

| Champ | Origine |
|---|---|
| Nom | 14 chiffres `AAAAMMJJhhmmss`, lus dans la liste du boîtier ou dans un nom de fichier |
| Type | Indicateur de la liste : `01` donne Note et `00` donne Call. Une autre valeur donne Note, avec une ligne au journal. Pour un enregistrement connu seulement par ses fichiers, le type vient du préfixe. |
| Taille sur le boîtier | Liste du boîtier |
| Durée | Taille divisée par 4 000 octets par seconde (MP3 à 32 kbit/s constant), affichée en minutes, ou en secondes sous une minute |
| Fichiers | `<Type>-<nom>.mp3`, `.vtt` et `.md` dans le dossier du téléphone |

### État affiché

L'état se calcule à chaque rafraîchissement à partir des trois sources, des travaux en cours, des brouillons et des erreurs. La première règle vraie l'emporte.

| Ordre | État | Condition |
|---|---|---|
| 1 | Erreur | La dernière tentative d'une étape a échoué et n'a pas été reprise avec succès. |
| 2 | Récupération p % | Le transfert Bluetooth de cet enregistrement est en cours. |
| 3 | Transcription…, Synthèse… ou Envoi… | L'étape correspondante est en cours. |
| 4 | À relire | Un brouillon de synthèse attend une validation. |
| 5 | Sur Drive | Drive est activé, le MP3 est dans le dossier Drive et chaque fichier du téléphone y est aussi. |
| 6 | Prêt | Le MP3, le VTT et le MD sont sur le téléphone. |
| 7 | Récupéré | Le MP3 est sur le téléphone, et l'étape suivante attend parce que la chaîne est coupée. |
| 8 | Sur le boîtier | L'enregistrement n'est que sur le boîtier. |

L'état « Récupéré » ne figure pas dans la maquette. Il s'ajoute aux états de la liste des enregistrements (écran 4).

### État local

Le fichier `state.json` du stockage interne ne garde que ce qui ne se déduit pas des trois sources :

- l'adresse et le nom du boîtier associé ;
- la dernière liste lue sur le boîtier, remplacée à chaque lecture et utilisée quand le boîtier est hors de portée ;
- l'adresse du dossier du téléphone (URI de l'arborescence choisie) ;
- les dossiers Drive connus (identifiant, nom, origine) et le dossier choisi ;
- la dernière erreur par enregistrement et par étape ;
- les réglages qui ne sont pas secrets.

Les brouillons de synthèse vivent dans `drafts/<Type>-<nom>.md`. Un brouillon est supprimé dès que le MD validé est écrit dans le dossier du téléphone. Les clés d'API sont dans un fichier à part, chiffré.

Au premier lancement de la cible, l'app reprend de l'ancien `state.json` l'adresse et le nom du boîtier, ainsi que le dossier Drive choisi, qui devient un dossier connu et reste choisi. Les états d'enregistrement de la version 0.1.5 sont abandonnés, puisqu'ils se déduisent désormais des sources.

## Récupération par Bluetooth

- La liaison s'ouvre à l'ouverture de l'app, au toucher sur la pastille et au geste de rafraîchissement. Elle reste ouverte tant que l'app est au premier plan ou qu'une récupération tourne, puis se ferme.
- La séquence de connexion et de lecture de la liste est celle de [`protocole-v05.md`](protocole-v05.md) : appairage rejoué, numéro de série, informations, mise à l'heure, `0x18` avec `00`, puis `0x0a`.
- Le transfert utilise `0x0b` comme en 0.1.5. Le flux est écrit dans `<nom>.mp3.part`, dans le cache de l'app, puis contrôlé (taille annoncée, en-tête MP3). Le fichier valide est copié dans le dossier du téléphone sous `<Type>-<nom>.mp3`, en remplaçant un fichier du même nom en cas de nouvelle récupération, puis le fichier partiel est supprimé.
- Les récupérations passent l'une après l'autre. Une coupure arrête la file, comme en 0.1.5.
- Le boîtier n'est jamais modifié : aucune suppression ni écriture d'enregistrement.

## Transcription

### Requête

`POST {base}/audio/transcriptions` en multipart, avec `file`, `model`, `response_format=verbose_json`, `timestamp_granularities[]=segment`, et `language` si une langue est fixée. La clé part dans l'en-tête `Authorization: Bearer`. Le délai de connexion est de 15 secondes et le délai de réponse de 30 minutes.

L'app lit les segments de la réponse (`start`, `end`, `text`), écrit le VTT dans le dossier du téléphone, puis lance la synthèse si la chaîne est active.

### VTT

Le VTT suit le format WebVTT, sans balise de voix :

```text
WEBVTT

00:11:48.000 --> 00:12:03.900
On peut reprendre le planning de la semaine prochaine ?

00:12:04.000 --> 00:12:18.700
Oui. Je propose de livrer la première version vendredi.
```

Le VTT est la seule source de la transcription. Relancer la transcription le remplace.

### À vérifier au début du lot 2, sur l'endpoint choisi

Le modèle de transcription doit renvoyer des segments horodatés en `verbose_json`. Si ce n'est pas le cas, le lot 2 s'arrête et le choix du modèle ou de l'endpoint est revu avant la suite.

## Synthèse

- `POST {base}/chat/completions` en JSON, avec `model` et deux messages. Le message `system` contient les instructions, suivies de « Format de la synthèse : » et du format. Le message `user` contient le VTT enregistré.
- Les variables sont remplacées dans les instructions et dans le format :

| Variable | Valeur | Exemple |
|---|---|---|
| `{{type}}` | Type de l'enregistrement | Call |
| `{{date}}` | Date et heure de l'enregistrement | 30 octobre 2026 à 16:05 |
| `{{duree}}` | Durée arrondie à la minute | 41 min |

- La réponse (`choices[0].message.content`) devient un brouillon de MD et l'enregistrement passe à « À relire ».
- Le délai de réponse est de 5 minutes.
- Le prompt par défaut (écran 9) donne des instructions en français, sans invention, et un format avec les sections Résumé, Décisions et Actions.

## Stockage sur le téléphone

- Au premier besoin d'écrire (première récupération), l'app ouvre le sélecteur de dossiers d'Android, placé sur Téléchargements. L'utilisateur y crée ou choisit un dossier, par exemple « Dictée ». L'app garde une permission persistante sur ce dossier.
- Réglages permet de changer de dossier. Les fichiers déjà écrits restent dans l'ancien dossier.
- L'app lit le dossier à l'ouverture, au rafraîchissement et après chaque écriture. Elle ne retient que les fichiers dont le nom suit `Note-` ou `Call-`, puis 14 chiffres et l'extension `.mp3`, `.vtt` ou `.md`.
- Si la permission est perdue ou si le dossier a disparu, les récupérations et les enregistrements se bloquent avec un message qui renvoie vers Réglages.

## Envoi vers Google Drive

- Le droit reste `drive.file`. L'app demande un jeton juste avant chaque opération Drive. Sur une erreur 401, elle efface le jeton en cache (`clearToken`), en redemande un et refait l'appel une seule fois.
- Dossier par défaut : quand Drive est activé sans dossier choisi, l'app cherche « Dictée » parmi les dossiers connus, puis dans Drive (requête sur le nom, limitée par Google aux dossiers accessibles à l'app). Sans résultat, elle crée « Dictée » à la racine.
- « Envoyer » lit une fois les noms et tailles du dossier, page par page. Pour chaque enregistrement concerné et chaque fichier du téléphone (MP3, VTT, MD), le fichier est présent s'il existe un fichier du même nom dans le dossier. Pour le MP3, le nom sans préfixe (`<nom>.mp3`), utilisé par la version 0.1.5, compte aussi.
- Un fichier absent part en téléversement reprenable (`audio/mpeg`, `text/vtt` ou `text/markdown`). Il est déposé si la taille renvoyée par Drive égale la taille locale.
- L'app ne remplace ni ne supprime jamais un fichier dans Drive. Un VTT exporté par DOWAY sous le même nom compte donc comme présent : le VTT de l'app n'est pas envoyé et le journal le signale.
- Les fichiers du téléphone restent en place après l'envoi.

## Travaux longs

- Un service de premier plan exécute les récupérations, les appels de transcription et de synthèse, et les envois. Il déclare le type `connectedDevice` pour le Bluetooth et `dataSync` pour le réseau.
- Sa notification reprend le texte de la barre d'avancement. Sur Android 13 et plus, l'app demande la permission de notification au premier travail long. Un refus n'arrête pas le travail : seule la notification manque.
- Trois files indépendantes tournent en parallèle : la file Bluetooth, la file de traitement (une transcription ou une synthèse à la fois) et la file Drive.

## Journal

- Chaque ligne a la forme `HH:mm:ss SOURCE message`.
- Les requêtes HTTP sont journalisées sans en-têtes ni corps : méthode, chemin, statut, durée et taille. Les clés d'API n'apparaissent jamais.
- Les trames Bluetooth restent journalisées en hexadécimal, comme en 0.1.5.

## Erreurs et reprise

| Situation | Comportement |
|---|---|
| Boîtier hors de portée à l'ouverture | La pastille passe au point vide, la liste s'appuie sur la dernière liste lue, et « Récupérer » se grise. |
| Coupure ou silence de 20 secondes pendant un transfert | Le fichier partiel est supprimé, l'enregistrement passe en erreur et la file s'arrête. |
| Refus de `0x0b` | L'enregistrement passe en erreur et la file continue avec le suivant. |
| Endpoint de transcription injoignable ou réponse en erreur | L'enregistrement passe en erreur avec le code reçu. Le MP3 reste en place, et « Réessayer » relance l'appel. |
| Endpoint de synthèse injoignable ou réponse en erreur | L'enregistrement passe en erreur. Le VTT reste en place, et « Réessayer » relance l'appel. |
| Erreur 401 de Drive après le nouvel essai | L'envoi s'arrête avec le message « Accès Google à renouveler ». Les fichiers restent à envoyer. |
| Dossier Drive inaccessible | L'envoi est annulé avec un message. L'app ne remplace jamais le dossier choisi sans action de l'utilisateur. |
| Dossier du téléphone inaccessible ou plein | L'écriture échoue, l'enregistrement passe en erreur et le message renvoie vers Réglages. |

## Architecture du code

### Unités en Kotlin pur, testées sans Android

| Fichier | Rôle |
|---|---|
| `Frame.kt`, `V05Session.kt` | Protocole et transfert, sans changement de fond |
| `RecordName.kt` | Lecture et écriture des noms, type, durée |
| `RecordingState.kt` | Calcul de l'état affiché à partir des sources, des travaux, des brouillons et des erreurs |
| `Transcript.kt` | Segments horodatés lus dans la réponse de l'endpoint |
| `Vtt.kt` | Écriture et lecture du WebVTT |
| `PromptTemplate.kt` | Remplacement des variables |
| `TranscriptionClient.kt`, `SummaryClient.kt` | Appels HTTP au format OpenAI, écrits contre l'interface `Http` existante |
| `DriveClient.kt` | Dossiers, listes, téléversement, renouvellement du jeton |
| `Journal.kt` | Journal sur sept jours |

### Unités Android

| Fichier | Rôle |
|---|---|
| `BleLink.kt` | Liaison Bluetooth, sans changement de fond |
| `PhoneFolder.kt` | Accès au dossier du téléphone par le Storage Access Framework |
| `SecretStore.kt` | Chiffrement des clés avec le Keystore |
| `WorkService.kt` | Service de premier plan et ses trois files |
| `AppState.kt` | État observé par l'interface |
| `ui/Theme.kt` | Tokens du design system en thème clair et sombre |
| `ui/…Screen.kt` | Un fichier par écran : liste, fiche, réglages, prompt, dossier Drive, journal, feuille d'association |

L'app garde une seule activité. Le passage d'un écran à l'autre suit une pile d'écrans tenue dans l'état, sans bibliothèque de navigation. Les dépendances ajoutées sont celles de Jetpack Compose (nomenclature, UI, foundation, material3 pour la feuille du bas et les interrupteurs, activity-compose). `play-services-auth` passe en 22.0.0, la version que cite la documentation de `revokeAccess`. Le lecteur audio utilise `MediaPlayer` d'Android.

### Design system

L'app suit le design system commun de la stack, Stack UI. Ses valeurs viennent de son fichier `tokens.json`, dont une copie fidèle se trouve dans `app/src/test/resources/stack-ui/tokens.json` : `TokensTest` compare les deux palettes, les rayons, les hauteurs, les opacités et l'échelle typographique à cette copie, et échoue dès qu'une valeur dérive ou qu'un jeton de couleur n'est ni repris ni ignoré volontairement. Les valeurs ci-dessous sont celles de `Tokens.kt`.

| Token | Clair | Sombre |
|---|---|---|
| Fond | `#FFFFFF` | `#0B0B0B` |
| Surface (carte) | `#FFFFFF` | `#151515` |
| Texte | `#0B0B0B` | `#ECECEC` |
| Texte secondaire | `#5C5C5C` | `#A3A3A3` |
| Bordure | `#E2E2E2` | blanc à 12 % |
| Discret (tuile, pastille neutre, encart) | `#F3F3F3` | `#1C1C1C` |
| Bouton principal | `#0B0B0B`, texte `#FFFFFF` | `#F3F3F3`, texte `#0B0B0B` |
| Bouton secondaire et champ | `#ECECEC` | `#242424` |
| Réussite | `#05713A` sur `#DCF3E5` | `#4CC38A` sur vert à 16 % |
| À relire (avertissement) | `#7C4A00` sur `#FFF1CC` | `#F5B83D` sur ambre à 16 % |
| Erreur | `#B3130B` sur `#FDE4E1` | `#FF7B72` sur rouge à 16 % |
| Focus clavier | `#1F5FE0` | `#6C9BFF` |

Les aplats gris remplacent les contours : un champ ou un bouton secondaire n'a pas de bordure, et seule une carte garde un trait, avec une ombre douce en clair. Un bouton pressé prend la teinte du survol du design system, puisqu'un survol n'existe pas sur mobile.

La police d'interface est Figtree (graisses 400, 500, 600 et 700, en police variable) et la police à chasse fixe JetBrains Mono, embarquées dans l'app. Les titres sont en 700, le texte courant est à 15 sp. Le rayon est de 10 dp pour les boutons et les champs, de 12 dp pour les lignes et les encarts, de 16 dp pour les cartes et les dialogues, et les pastilles sont en pilule. Les boutons font 44 dp de haut, 36 dp pour le bouton compact. L'icône de l'app est un rond noir avec un micro blanc. Dans l'en-tête en thème sombre, elle s'inverse.

## Lots

Chaque lot a ses tests et sa propre version.

| Lot | Version | Contenu |
|---|---|---|
| 1 | 0.2.0 | Design system et écrans en Compose, premier lancement et association, liste réunie, récupération (tout, sélection, à nouveau) vers le dossier du téléphone avec les préfixes, service de premier plan et barres d'avancement, fiche avec lecteur et menu, Réglages (boîtier, stockage, Drive, apparence, à propos), écran Dossier Drive, envoi vers Drive avec renouvellement du jeton, journal, reprise de l'état 0.1.5. La fiche n'a pas encore d'onglets, et les sections Transcription, Synthèse et Automatisation des Réglages arrivent avec leur lot. |
| 2 | 0.3.0 | Réglages de transcription, appel à l'endpoint, VTT écrit dès son retour, onglet Transcription en lecture, chaîne automatique, envoi du VTT vers Drive |
| 3 | 0.4.0 | Réglages de synthèse, écran Prompt et format, appel au LLM, relecture et enregistrement du MD, envoi du MD vers Drive |
| 4 | 0.5.0 | Diarisation, à activer plus tard (section suivante) |

## Lot 4 : diarisation, à activer plus tard

Ce lot regroupe tout ce que demande un endpoint de diarisation. Il ne démarre qu'une fois le contrat d'API de l'endpoint de diarisation arrêté. La description ci-dessous suit un endpoint `/v1/audio/diarization`. L'écran 13 est l'onglet Transcription de ce lot.

- **Réglages.** Un champ « Modèle de diarisation » s'ajoute à la section Transcription, par exemple `pyannote/speaker-diarization-community-1`.
- **Requête.** Après la transcription, l'app envoie le même fichier à `POST {base}/audio/diarization`, avec `file`, `model` et `response_format=json`. La réponse donne des segments `start`, `end` et `speaker`. Si les segments de la transcription portent déjà un champ `speaker`, l'app les utilise tels quels et saute cet appel.
- **Fusion.** Chaque segment de transcription reçoit l'intervenant dont les segments de diarisation le recouvrent le plus longtemps. Un segment sans recouvrement reprend l'intervenant du segment précédent. Les intervenants sont numérotés « Intervenant 1 », « Intervenant 2 », etc., dans leur ordre d'apparition.
- **Relecture.** La transcription devient un brouillon (`drafts/<Type>-<nom>.transcript.json`) et l'enregistrement passe à « À relire ». Dans l'onglet Transcription, le bloc « Intervenants » liste les voix avec leur temps de parole, et un nom saisi remplace « Intervenant N » partout. « Enregistrer le VTT » écrit le VTT, puis lance la synthèse. Le bouton devient ensuite « Modifier les noms », qui relit le VTT et le réécrit avec les nouveaux noms.
- **VTT.** Chaque segment porte une balise de voix, par exemple `<v Intervenant 1>Oui. Je propose de livrer la première version vendredi.`, où le nom saisi remplace « Intervenant 1 ».
- **Synthèse.** La variable `{{intervenants}}` revient. Elle donne les noms lus dans le VTT, dans leur ordre d'apparition.
- **Erreur.** Si la diarisation échoue, la transcription est gardée avec un seul intervenant. Le bandeau l'indique et le journal donne la raison.

## Vérifications

### Par les tests et la compilation, à chaque lot

1. Les tests existants du protocole restent verts.
2. Les noms se lisent et s'écrivent dans les deux sens, préfixes compris, et la durée se calcule à partir de la taille.
3. Le calcul de l'état affiché donne la valeur attendue pour chaque ligne du tableau « État affiché ».
4. Lot 1 : la détection des fichiers déjà présents dans Drive tient compte des noms préfixés et des noms de la version 0.1.5. Une erreur 401 déclenche un seul nouvel essai avec un jeton neuf.
5. Lot 2 : une réponse `verbose_json` enregistrée donne les segments attendus, et le VTT écrit puis relu redonne les mêmes segments.
6. Lot 3 : les variables sont remplacées, et la requête envoyée contient les instructions, le format et le VTT.
7. Lot 4 : la fusion transcription et diarisation attribue les intervenants attendus sur des réponses enregistrées, et le VTT relu redonne les mêmes noms.
8. L'APK se compile et le lint passe.

### Sur un téléphone

1. Lot 1 : l'association se fait une fois, et l'app s'ouvre ensuite sur la liste. La liste montre les mêmes enregistrements que DOWAY, avec le bon type. « Récupérer » écrit les MP3 préfixés dans le dossier choisi. « Envoyer » les dépose dans « Dictée » sans créer de doublon avec les fichiers de la version 0.1.5 ni avec les exports de DOWAY. Une récupération continue écran éteint, et un boîtier éteint pendant un transfert ne laisse aucun fichier partiel.
2. Lot 2 : un enregistrement revient avec un VTT horodaté, qui s'ouvre dans un lecteur de sous-titres.
3. Lot 3 : la synthèse suit le format réglé, et le MD validé arrive dans Drive à côté du MP3 et du VTT.
4. Lot 4 : un enregistrement à deux voix revient avec deux intervenants, et le VTT enregistré porte les noms saisis.

## Hors périmètre

- Suppression ou modification d'enregistrements sur le boîtier.
- Synchronisation en arrière-plan sans ouvrir l'app.
- Modification du texte de la transcription.
- Plusieurs prompts de synthèse.
- Modèle diarisé d'OpenAI (`gpt-4o-transcribe-diarize`), déprécié.
- Transfert des enregistrements par une autre liaison que le Bluetooth.
- Partage d'un enregistrement vers une autre app, en dehors de l'export du journal.
