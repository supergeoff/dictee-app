# Dictée : spécification du produit

Statut : ce document décrit le comportement visé pour la version 0.7.0. La recette sur appareil, la vérification Drive réelle et celle de l'APK signé restent à faire.

Le protocole Bluetooth du MECHEN V05 est décrit dans [`protocole-v05.md`](protocole-v05.md).

## Objectif

Dictée récupère les enregistrements du MECHEN V05 par Bluetooth et les range dans un dossier du téléphone choisi par l'utilisateur. Elle peut ensuite envoyer dans un dossier Google Drive les fichiers qui n'y sont pas encore. La suppression sur le boîtier exige une demande explicite ; la récupération ne supprime jamais automatiquement les enregistrements.

## Décisions

| Sujet | Décision | Raison |
|---|---|---|
| Organisation des écrans | Une liste centrale, une fiche par enregistrement et une page Réglages | Un enregistrement n'apparaît qu'à un endroit, avec un seul état lisible d'un coup d'œil. |
| Accès à Drive | Le droit `drive.file` est conservé. L'écran Dossier Drive liste les dossiers connus de l'app, et « Autre dossier… » ouvre le sélecteur Google. | Un accès complet à Drive imposerait l'écran d'application non validée et une autorisation qui expire au bout de sept jours en mode test. |
| Stockage sur le téléphone | Dossier choisi avec le sélecteur de dossiers d'Android (Storage Access Framework), avec une permission persistante | C'est la seule façon d'écrire des fichiers dans un dossier visible par l'utilisateur et de les relire après une réinstallation de l'app. |
| Noms de fichiers | `<Type>-<nom>.<extension>`, avec le type Note ou Call | Les noms sont identiques sur le téléphone et dans Drive, et ils correspondent aux exports de DOWAY. |
| Interface | Jetpack Compose, avec un thème construit sur les tokens du design system | La liste groupée, la feuille du bas et le thème sombre demanderaient beaucoup plus de code en vues construites à la main. |
| Travaux longs | Un service de premier plan, avec une notification qui reprend la barre d'avancement | À 15 Ko/s, un appel de 41 minutes met environ 11 minutes à arriver. L'utilisateur peut quitter l'app pendant ce temps. |
| Source de vérité | Le dossier du téléphone fait foi pour les fichiers du téléphone, la liste du boîtier pour ce qui est sur le boîtier, et le dossier Drive pour ce qui est dans Drive. L'état local ne garde que ce qui ne se déduit pas de ces trois sources. | Une seule source de vérité par information. |

## Parcours

1. Au premier lancement, aucun boîtier n'est associé. L'utilisateur touche « Rechercher le boîtier », accorde la permission Bluetooth, puis associe le boîtier trouvé. L'app lit aussitôt la liste du boîtier.
2. À chaque passage au premier plan, l'app se connecte au boîtier associé et relit sa liste. La liste affiche les enregistrements du boîtier, du téléphone et de Drive, groupés par jour.
3. « Récupérer (N) » transfère les enregistrements absents du téléphone et de Drive. Au premier besoin, l'app demande le dossier du téléphone.
4. Si l'option de création après récupération est active, chaque MP3 récupéré et validé déclenche la création des fichiers VTT et MD manquants. Si l'envoi vers Drive est activé, « Envoyer (N) » ouvre le choix des fichiers à envoyer manuellement.

## Écrans

L'app garde une seule activité. Le passage d'un écran à l'autre suit une pile d'écrans tenue dans l'activité, sans bibliothèque de navigation. Les écrans de la pile sont l'accueil, la fiche, les réglages, le prompt de synthèse, le dossier Drive et le journal. La feuille d'association s'affiche par-dessus l'écran en cours. Les messages courts de l'app s'affichent en toast.

### Premier lancement et recherche du boîtier

- L'accueil affiche la liste des enregistrements dès qu'un boîtier est associé. Sans boîtier, il affiche l'écran « Associe ton enregistreur », dont la pastille indique « Aucun boîtier », et Réglages reste accessible.
- « Rechercher le boîtier » ouvre une feuille du bas. La liaison de l'app est fermée avant la recherche, qui dure 15 secondes. Un boîtier est reconnu à son nom (`MECHEN…`) ou aux identifiants fabricant `0x1116` et `0xA724` de son annonce.
- Le message d'aide rappelle de vérifier que le boîtier est allumé et que DOWAY est fermée. Si rien n'est trouvé alors qu'un boîtier MECHEN est déjà connecté au téléphone, le message dit qu'une autre app le tient, sans doute DOWAY, et propose d'en forcer l'arrêt dans les réglages Android.
- « Associer » enregistre l'adresse et le nom du boîtier, ferme la feuille et lance la lecture de la liste. Depuis Réglages, l'association d'un nouveau boîtier remplace l'ancien.

### Liste des enregistrements

- La liste réunit trois sources : la dernière liste lue sur le boîtier, le dossier du téléphone et les MP3, VTT et MD reconnus du dossier Drive, quand Drive est activé. Elle est groupée par jour (« Aujourd'hui », « Hier », puis par exemple « Jeudi 29 octobre »), du plus récent au plus ancien. Un enregistrement absent du boîtier reste listé tant qu'un de ses fichiers existe sur le téléphone ou dans Drive.
- La pastille du boîtier montre un point plein quand la liaison est ouverte et un point vide sinon. Un toucher relance la connexion, et un échec affiche « Boîtier hors de portée. ». Tirer la liste vers le bas relit le dossier du téléphone, le dossier Drive et le boîtier.
- Les filtres sont « Tous », « À relire » et « Sur le boîtier », chacun avec son compteur. « Sur le boîtier » retient les enregistrements du boîtier qui n'ont aucun fichier sur le téléphone. « À relire » ne retient aucun enregistrement et affiche toujours 0.
- « Récupérer (N) » compte les enregistrements du boîtier qui ne sont ni sur le téléphone ni dans Drive. Il n'est actif que si la liaison est ouverte, qu'aucune récupération ne tourne et que N n'est pas nul. Il affiche « Récupération… » pendant le travail.
- « Envoyer (N) » n'apparaît que si Drive est activé. Il compte les enregistrements sans erreur de récupération dont au moins un fichier local requis par le dernier choix confirmé est à envoyer ou en conflit : fichier absent, document sans preuve correspondant à son empreinte actuelle, ou homonymes visibles. Sans catalogue ou destination connue, tout fichier local requis rend l'enregistrement candidat ; en documents seuls, un MP3 seul ne suffit pas. Il affiche « Envoi… » pendant le travail.
- Les boutons suivent l'ordre « Récupérer », « VTT + MD », « Envoyer ». Un compteur nul désactive son bouton, sauf « Envoyer » en sélection explicite lorsqu'un fichier local est disponible pour rouvrir le choix. « VTT + MD » compte les MP3 exploitables auxquels il manque un VTT ou un MD, hors travaux actifs ; en sélection, il se limite aux lignes cochées. Il crée les fichiers manquants sans remplacer ceux qui existent et n'envoie rien vers Drive. Les exclusions de la sélection sont signalées ; une action collective ne reprend aucune tâche suspendue exclue du compteur.
- Un appui long passe en sélection. La barre affiche le nombre de lignes cochées, une croix pour sortir et « Tout », qui coche les lignes du filtre en cours. Les actions portent alors sur les lignes cochées : récupérer une ligne cochée la transfère de nouveau depuis le boîtier, quel que soit son état ; « VTT + MD » ne remplace aucun fichier. La sélection se vide après l'action.
- « Supprimer du téléphone » est proposé en sélection ; après une suppression partielle, les cibles qui ont encore des fichiers locaux restent cochées. Les lignes sélectionnées hors cibles restent cochées si elles sont encore présentes.
- Chaque ligne affiche un badge et quatre points pour les étapes Téléphone, VTT, MD et Drive. Le badge indique l'erreur, le travail en cours, l'attente ou la dernière étape faite. Une tâche en attente affiche son rang dans la file.
- La barre d'avancement affiche le type de travail, « Transcription » ou « Synthèse » pour la file de production. Les barres de récupération et d'envoi peuvent s'afficher simultanément.

### Fiche d'un enregistrement

- Le titre donne le type, la date et l'heure, par exemple « Call · 30 oct. 16:05 ». Le sous-titre donne la durée et l'état.
- Le menu propose « Récupérer à nouveau » et « Supprimer du téléphone ». La récupération n'est active que si l'enregistrement est sur le boîtier, que la liaison est ouverte et qu'aucune récupération ne tourne. Le lecteur est libéré avant confirmation de la suppression.
- Le lecteur lit le MP3 du téléphone avec `MediaPlayer` : lecture, pause, barre de position et temps en minutes et secondes. Sans MP3 sur le téléphone, la fiche affiche « Le MP3 n'est pas encore sur le téléphone. ». La lecture se met en pause quand l'app passe en arrière-plan.
- La fiche affiche le nom du fichier et son dossier. Elle se ferme d'elle-même si l'enregistrement disparaît de la liste.
- La carte du parcours affiche les quatre étapes, la première raison utile et une seule action principale. Les onglets ne portent aucune action. En attente de configuration, « Ouvrir les réglages » mène à Transcription, ou à Stockage sur le téléphone si le dossier a changé ; pour une synthèse non configurée, il mène à Synthèse.
- Les onglets « Synthèse » et « Transcription » montrent le MD rendu en lecture seule et les segments du VTT. Toucher un segment place le lecteur à son début et le lance ; un VTT illisible ou sans parole n'affecte que cette carte. La fiche affiche le modèle et la correspondance au VTT quand la provenance est connue, sinon elle les indique inconnus.
- « Créer le MD » utilise le VTT présent et ne remplace pas un MD apparu entre-temps. Le menu propose « Refaire le MD » et « Refaire le VTT et le MD » : ce sont des remplacements explicites, indisponibles pour une tâche déjà active. Après chaque promotion de VTT ou de MD, le dossier est relu pour actualiser la fiche et les compteurs, y compris avant la fin de la synthèse.
- La fiche distingue la synthèse en cours, l'attente d'une relance automatique et l'enregistrement du MD ; une première requête en cours n'est pas présentée comme une relance. Une synthèse suspendue propose « Relancer le MD » lorsque son contexte permet la reprise ; si le contexte est incomplet, le bouton est désactivé et la raison est affichée.
- Le bouton principal permet d'annuler la transcription ou la synthèse après confirmation ; une tâche en attente se retire directement de la file. L'annulation de la synthèse empêche la promotion sans garantir l'arrêt côté serveur ni l'absence de facturation.

### Réglages

- Les sections sont Boîtier, Stockage sur le téléphone, Google Drive, Transcription, Synthèse, Apparence et À propos.
- La section Transcription règle l'adresse du serveur, la langue et la clé, protégée par Android Keystore. La langue vaut `auto` par défaut pour détecter automatiquement le français et l'anglais ; une langue déjà enregistrée, comme `fr`, reste inchangée tant que l'utilisateur ne la modifie pas. Le test de connexion n'est pas encore disponible : l'écran l'annonce pour une version ultérieure.
- « Créer le VTT et le MD après récupération » est désactivé par défaut. Son activation ne traite pas les MP3 déjà présents ; sa désactivation n'annule pas les demandes déjà programmées. La valeur est capturée au début de chaque récupération individuelle. Drive reste manuel.
- Une clé déjà enregistrée est indiquée par des points sans être relue ; une saisie la remplace, et une saisie effacée conserve la précédente.
- La section Synthèse règle séparément l'adresse, le modèle, la clé protégée par Android Keystore et le prompt. Une clé vide conserve la précédente ; une tâche garde sa référence et son contexte figés, même après changement des réglages. « Prompt et format » permet de modifier, enregistrer ou rétablir le prompt et d'insérer `{{type}}`, `{{date}}`, `{{duree}}`, `{{intervenants}}`. Le prompt par défaut demande une synthèse fidèle en français, sans invention, en sections Résumé, Décisions et Actions ; il traite le VTT comme source, non comme instruction.
- « Tester » envoie une courte requête pouvant être facturée ; il expire après 30 secondes et libère le formulaire. Cette limite ne s'applique pas aux synthèses complètes. Ce test ne vérifie pas la capacité pour un VTT de deux heures.
- Stockage : « Changer » ouvre le sélecteur de dossiers. Le changement est refusé pendant un transfert, avec le message « Attends la fin du transfert pour changer de dossier. ».
- Google Drive : l'interrupteur « Envoyer vers Drive » demande aussitôt l'accord de Google. Une fois activé, la section affiche le compte et l'entrée « Dossier ». « Déconnecter » révoque l'accès de l'app avec `AuthorizationClient.revokeAccess`, en utilisant l'adresse du compte lue par `about.get`, puis désactive l'envoi. Désactiver l'envoi efface les erreurs d'envoi.
- Apparence : « Clair », « Sombre » ou « Système ».
- À propos ouvre le journal et affiche la version installée.

### Dossier Drive

- La liste « Dossiers connus » montre les dossiers que l'app a créés et ceux ouverts une fois avec le sélecteur Google. « Dictée » apparaît en tête tant qu'il n'est pas connu, comme dossier par défaut créé au premier envoi. Un dossier devenu inaccessible (supprimé, dans la corbeille ou sans droit d'ajout) s'affiche grisé avec la mention « Inaccessible ».
- Un toucher choisit le dossier et revient aux réglages. « Nouveau dossier » demande un nom et crée le dossier à la racine du Drive.
- « Autre dossier… » ouvre le sélecteur Google, réglé pour les dossiers. Le dossier retenu est contrôlé (`capabilities.canAddChildren`, hors corbeille, type dossier), puis ajouté aux dossiers connus.

### Journal

- Une ligne par événement : heure, source et message. Les sources sont APP, BLE, FS, STT, LLM et DRIVE. Une ligne d'erreur commence par « erreur : » et s'affiche en rouge.
- Les filtres sont « Tout », « Erreurs », « Bluetooth », « Drive », « Transcription » et « Synthèse ».
- Le journal garde un fichier par jour sur sept jours. Les fichiers plus anciens sont supprimés au démarrage de l'app.
- « Copier » copie les lignes affichées avec le filtre en cours. « Exporter » partage un fichier `.txt` des sept jours par le partage d'Android. « Vider » efface le journal après confirmation.

## Données

### Enregistrement

| Champ | Origine |
|---|---|
| Nom | 14 chiffres `AAAAMMJJhhmmss` formant une date valide, lus dans la liste du boîtier ou dans un nom de fichier |
| Type | Indicateur de la liste : `01` donne Note et `00` donne Call. Une autre valeur, ou une valeur absente, donne Note, avec une ligne au journal. Pour un enregistrement connu seulement par ses fichiers, le type vient du préfixe. |
| Taille sur le boîtier | Liste du boîtier |
| Durée | Taille divisée par 4 000 octets par seconde (MP3 à 32 kbit/s constant), affichée en minutes arrondies, ou en secondes sous une minute |
| Fichiers | `<Type>-<nom>.mp3`, `.vtt` et `.md` dans le dossier du téléphone ; les VTT et MD créés par l'app ou déposés par un autre outil sont reconnus. |

### État affiché

L'état se calcule à chaque rafraîchissement à partir des trois sources, des travaux en cours et des erreurs. La première règle vraie l'emporte.

| Ordre | État | Condition |
|---|---|---|
| 1 | Erreur | La dernière tentative de récupération ou d'envoi a échoué et n'a pas été reprise avec succès. |
| 2 | Récupération p % | Le transfert Bluetooth de cet enregistrement est en cours. |
| 3 | Envoi… | Un envoi de cet enregistrement vers Drive est en cours. |
| 4 | Transcription… ou Transcription p % | Une transcription de cet enregistrement est en cours. |
| 5 | Sur Drive | Le MP3 est dans le dossier Drive et chaque fichier du téléphone y est aussi. |
| 6 | Prêt | Le MP3, le VTT et le MD sont sur le téléphone. |
| 7 | Récupéré | Au moins un fichier de l'enregistrement est sur le téléphone. |
| 8 | Sur le boîtier | L'enregistrement n'est que sur le boîtier. |

### État local

Le fichier `state.json`, version 7, du stockage interne ne garde que ce qui ne se déduit pas des trois sources :

- l'adresse et le nom du boîtier associé ;
- la dernière liste lue sur le boîtier (nom, taille, indicateur), remplacée à chaque lecture et utilisée quand le boîtier est hors de portée ;
- l'adresse du dossier du téléphone (URI de l'arborescence choisie) ;
- l'activation de Drive, le compte Google autorisé, les dossiers Drive connus (identifiant, nom, origine) et le dossier choisi ;
- le dernier choix d'envoi Drive confirmé et les preuves d'envoi des VTT/MD (destination, nom, identifiant Drive et empreinte SHA-256) ;
- la dernière erreur par enregistrement et par étape (récupération ou envoi), gardée après un redémarrage ;
- les réglages de transcription et de synthèse, avec des références aux clés chiffrées, jamais les clés en clair ;
- l'option de traitement après récupération et les intentions durables associées aux récupérations individuelles ;
- les tâches durables, leurs contextes figés, phases, tentatives et éventuelles suspensions, ainsi que les empreintes de provenance des synthèses ;
- les sujets des enregistrements et le marqueur de tentative de sujet par tâche ;
- le thème.

La version 4 est lue sans sujet. Après le VTT, une demande courte au service de synthèse peut produire un sujet ; un échec ne bloque pas le MD. Le sujet remplace le titre de date dans la liste et la fiche.

Le fichier est écrit dans un fichier temporaire puis renommé. Un fichier illisible est mis de côté sous `state.json.bad`.

L'app reprend l'état de la version 0.1.5 au premier lancement : elle garde l'adresse et le nom du boîtier, et le dossier Drive choisi devient un dossier connu qui reste choisi. Les MP3 laissés par la 0.1.5 dans le stockage interne sont seulement comptés au journal.

## Récupération par Bluetooth

- La liaison s'ouvre au passage au premier plan, au toucher sur la pastille, au geste de rafraîchissement et après une association. Elle se ferme quand l'app passe en arrière-plan, sauf pendant une récupération, et à la fin d'une récupération faite en arrière-plan.
- La séquence de connexion et de lecture de la liste est celle de [`protocole-v05.md`](protocole-v05.md) : appairage rejoué, numéro de série, informations, mise à l'heure, `0x18` avec `00`, puis `0x0a`.
- Le transfert utilise `0x0b`. Le flux est écrit dans `<nom>.mp3.part`, dans le cache de l'app, puis contrôlé (taille annoncée et en-tête MP3). Le fichier valide est copié dans le dossier du téléphone sous `<Type>-<nom>.mp3`, en remplaçant un fichier du même nom en cas de nouvelle récupération, puis le fichier du cache est supprimé.
- Les récupérations passent l'une après l'autre, de la plus ancienne à la plus récente. Pour une récupération dont l'option capturée est active, une intention est persistée avant transfert puis rendue programmable seulement après validation durable du MP3. Sa consommation est idempotente ; au redémarrage, les intentions validées sont réconciliées sans parcourir les anciens MP3. Une récupération interrompue ne lance ni transcription ni reprise Bluetooth automatique. Une erreur de programmation ne transforme pas une récupération réussie en échec et ne bloque pas les suivantes.
- Aucune écriture d'enregistrement ni suppression automatique ne modifie le boîtier. Seule la suppression manuelle confirmée ci-dessous envoie une commande de suppression.

## Suppression du boîtier

- Depuis la fiche ou la sélection, « Supprimer du boîtier » ne cible que les enregistrements présents sur le boîtier. Un dialogue indique leur nombre, la conservation des copies du téléphone et de Drive et le nombre d'audios sans autre copie confirmée. Cet avertissement ne bloque pas ; annuler n'émet aucune commande. Avant toute commande, les copies connues sont revérifiées sous réservation : si une cible supplémentaire n'a plus de copie audio confirmée, la demande est annulée et doit être redemandée pour afficher un avertissement à jour.
- Une cible occupée ou en attente bloque toute la sélection. Une demande de traitement admise protège ses cibles dès sa réception et jusqu'à la fin du parcours interactif ; sans sélection explicite, la suppression est bloquée pendant ce parcours. Une demande de traitement chevauchant une suppression réservée est refusée.
- Après confirmation, chaque nom est envoyé par `0x0d` avec ses 14 octets ASCII ; une relecture complète par `0x18 00` puis `0x0a` confirme sa disparition. Une coupure, un délai dépassé ou une liste incomplète arrête le lot sans renvoi automatique. Le bilan distingue les cibles confirmées, refusées, non vérifiées et non traitées. Les copies du téléphone et de Drive restent intactes ; aucune récupération ni aucun traitement n'est lancé. La fiche d'un enregistrement sans copie connue revient à la liste.

## Stockage sur le téléphone

- Au premier besoin d'écrire (première récupération), l'app ouvre le sélecteur de dossiers d'Android, placé sur Téléchargements. L'utilisateur y crée ou choisit un dossier, par exemple « Dictée ». L'app garde une permission persistante sur ce dossier.
- Réglages permet de changer de dossier. L'app rend la permission de l'ancien dossier, et les fichiers déjà écrits y restent.
- L'app lit le dossier à l'ouverture, au rafraîchissement et après chaque écriture. Elle ne retient que les fichiers dont le nom suit `Note-` ou `Call-`, puis 14 chiffres et l'extension `.mp3`, `.vtt` ou `.md`.
- Une écriture passe par un fichier `.part`. L'ancien fichier du même nom est mis de côté en `.old`, le nouveau prend le nom définitif, puis les restes sont nettoyés. Le dossier doit accepter le renommage.
- Si la permission est perdue ou si le dossier a disparu, l'app affiche « Dossier du téléphone inaccessible. Choisis-le de nouveau dans Réglages. ».

## Suppression du téléphone

- Depuis le menu de la fiche ou la sélection, un dialogue confirme le nombre d'enregistrements ayant des fichiers locaux. Seuls leurs MP3, VTT et MD reconnus dans le dossier choisi sont supprimés ; ni Drive ni le boîtier ne sont modifiés. Annuler ne fait rien et une confirmation ne sert qu'une fois.
- Le dialogue avertit si la sauvegarde des documents sur Drive ne peut pas être vérifiée, ou si aucune autre copie de l'audio n'est connue sur Drive ou le boîtier. Ces avertissements ne bloquent pas. Un nom distant seul ne prouve pas la version courante d'un document : les reçus et la destination comptent, et `drive.file` ne révèle pas tous les fichiers Drive.
- Une cible occupée ou en attente bloque toute la sélection. Le dossier et les travaux sont revalidés avant suppression. Une récupération visant une cible en cours de suppression est refusée ; une cible d'une récupération déjà préparée est revalidée avant son transfert si une suppression est intervenue entre-temps. Une erreur sur un fichier n'annule pas les succès précédents : les autres fichiers sont tentés, les refus sont nommés et le dossier est relu ; une relecture impossible est signalée.
- Après suppression, un fichier local reste « Fait », sinon une copie distante confirmée affiche « Sur Drive » et une copie inconnue « Absent ». L'étape Drive dépend du choix d'envoi et des reçus de la destination courante. La ligne demeure si le boîtier ou Drive contient encore un fichier reconnu, même uniquement VTT ou MD ; sinon elle disparaît et la fiche revient à la liste. Un audio encore sur le boîtier reste récupérable. Rien n'est automatiquement récupéré ni produit ; les documents uniquement distants ne sont pas téléchargeables dans la fiche.

## Envoi vers Google Drive

- Le droit est `drive.file`. L'app demande un jeton avant chaque requête Drive. Sur une erreur 401, elle efface le jeton en cache (`clearToken`), en redemande un et refait l'appel une seule fois. Les délais sont de 30 secondes pour la connexion et de 120 secondes pour la lecture.
- Dossier par défaut : quand Drive est activé sans dossier choisi, l'app cherche « Dictée » parmi les dossiers connus, puis dans Drive (requête sur le nom, limitée par Google aux dossiers accessibles à l'app). Sans résultat, elle crée « Dictée » à la racine. Ce choix se fait au premier envoi.
- Liste et fiche ouvrent le même dialogue : « MP3 + VTT + MD » ou « VTT + MD », avec le compte, le dossier, le nombre d'enregistrements et les documents manquants. Le dernier choix confirmé est conservé dans `state.json` ; annuler ne change pas ce choix et ne lance aucun envoi. Les anciens états migrent avec le choix « MP3 + VTT + MD » et sans preuve d'envoi. La sélection explicite et l'action secondaire d'une fiche terminée permettent de changer de choix. Les reprises repassent par le dialogue.
- Les entrées Drive accessibles à l'application sont lues page par page avec leur identifiant. Le droit `drive.file` ne donne pas une vue générale du Drive : un fichier existant mais non visible à l'application peut ne pas être détecté. Un MP3 existant n'est jamais remplacé ; son ancien nom sans préfixe (`<nom>.mp3`) compte aussi. Un MP3, VTT ou MD reconnu présent seulement dans Drive crée une ligne dans la liste.
- Les VTT et MD sont comparés par empreinte SHA-256 et preuve d'envoi, propre au compte, au dossier et à l'identifiant Drive. Un document sans preuve ou modifié est créé ou remplacé par téléversement reprenable, y compris un VTT DOWAY visible du même nom : il n'y a pas de règle « ne jamais remplacer » pour les VTT/MD envoyés. Les homonymes visibles sont signalés comme conflit plutôt que de choisir arbitrairement. La preuve est enregistrée après contrôle de taille et une erreur d'écriture arrête le lot ; une erreur d'envoi ne crée pas de preuve et les succès précédents restent acquis.
- Les fichiers locaux restent en place. Les documents absents ne sont pas créés par l'envoi. Le parcours Drive exige les VTT et MD courants prouvés et, selon le choix, le MP3 distant ; une synthèse issue d'un ancien VTT ne termine pas le parcours.

## Travaux longs

- Un service de premier plan exécute les récupérations, les envois et le suivi des transcriptions et synthèses. Il déclare le type `dataSync`, et `connectedDevice` quand la permission Bluetooth est accordée. Il garde le processeur éveillé une heure au plus.
- Sa notification, sur le canal « Travaux en cours », reprend le texte de la barre d'avancement. Sur Android 13 et plus, l'app demande la permission de notification au premier travail long de chaque lancement. Un refus n'arrête pas le travail : seule la notification manque.
- Le canal distinct « Résultats des traitements » annonce, même sans écran ouvert, la création du VTT et du MD ensemble, ou une interruption avec sa raison. Un VTT seul n'est pas annoncé comme réussite globale. Une notification rejouée pour la même tâche remplace la précédente ; un refus de permission ou une erreur de notification ne modifie pas la file ni l'état durable.
- Une récupération et un envoi peuvent tourner en même temps, mais jamais deux récupérations ni deux envois. Les transcriptions et synthèses partagent une file et un coordinateur avec la reprise WorkManager ; les tâches DIC-4 déjà persistées restent VTT uniquement. La création d'une tâche ne tient aucun verrou pendant les appels réseau (DIC-25) : une intervention à la fois par tâche, et la file traitée reste la plus ancienne tâche active, du plus ancien enregistrement au plus récent.

## Transcription

- Le MP3 part vers `POST /v1/audio/transcriptions` (`dictee-diarize`, `response_format=vtt`, `background=true`). Le contexte (dossier, clé, adresse, empreinte SHA-256 du MP3) est figé dans `state.json` avant tout envoi, et l'identifiant serveur y est écrit dès sa réception. L'app suit ensuite la tâche par `GET`.
- Une reprise périodique WorkManager (15 minutes au plus court, réseau requis) est programmée avant le premier envoi et couvre le retrait de l'app des récents. Un seul enregistrement est traité à la fois, du plus ancien au plus récent, par le service de premier plan ou par le worker, qui partagent le même coordinateur. Les tâches automatiques et manuelles démarrent le même suivi interactif unique. Après expiration ou interruption de ce suivi, la reprise programmée prend le relais ; au retour au premier plan, les tâches durables non suspendues sont à nouveau suivies, sans renvoyer un MP3 déjà accepté. Une réconciliation lancée en arrière-plan ne démarre pas le service interactif ; une récupération déjà engagée continue son enchaînement. Une nouvelle demande pendant une expiration est suivie sans relancer la tâche confiée au worker. Android peut retarder la reprise, et un arrêt forcé depuis les réglages Android n'est pas couvert.
- Le serveur garde le résultat 24 heures après sa fin. Une réponse 404 peut aussi venir d'un autre compte : l'app suspend la tâche sans la renvoyer.
- Si la réponse à l'envoi est perdue, la tâche est suspendue et la relance est désactivée : l'envoi a peut-être été reçu et un nouvel appel risquerait une double soumission. Un MP3 modifié ou absent suspend aussi la tâche.
- Une réponse 429, 502, 503 ou 504 à l'envoi signale un serveur occupé : la tâche reste en attente et le MP3 repart au plus trois fois, après 60 puis 120 secondes, puis se suspend avec le message « Le serveur de transcription est occupé. Relance quand il est disponible. ». Renvoyer le même MP3 rejoint la tâche existante côté serveur, sans doublon.
- Le VTT reçu est validé en entier (UTF-8, en-tête `WEBVTT`, horodatages), puis écrit tel quel par le mécanisme `.part` / `.old`. Un VTT invalide n'est jamais écrit. Une annulation demandée avant la promotion l'empêche. Une récupération ne peut pas écraser un MP3 suivi par une tâche active.
- Si le serveur refuse explicitement le champ `background`, l'app l'indique ; le mode synchrone ne permet pas de reprendre une réponse perdue.

## Synthèse

### Fichiers manquants et régénération

L'option après récupération utilise le même traitement des fichiers manquants que « VTT + MD » ; le premier enregistrement peut progresser pendant le transfert du suivant. Une configuration de transcription manquante laisse la demande en attente de réglages et sa réconciliation est relancée après enregistrement des réglages. Une erreur de traitement reste propre à l'enregistrement et la file passe aux suivants.

« VTT + MD » traite les MP3 locaux exploitables du plus ancien au plus récent, sans remplacer de fichier existant, avec ou sans sélection. Les enregistrements sans MP3, en erreur de récupération ou déjà actifs sont exclus. En sélection, les lignes exclues pour MP3 absent, erreur de récupération ou travail actif sont signalées ; hors sélection, un message n'apparaît que si aucun candidat ne reste. Les fichiers créés ne partent pas automatiquement vers Drive.

| VTT | MD | Travail demandé |
|---|---|---|
| Absent | Absent | Transcription, promotion du VTT, puis synthèse. |
| Présent | Absent | Synthèse depuis le VTT présent, sans transcription. |
| Absent | Présent | Transcription seule ; le MD reste intact, sa correspondance au nouveau VTT n'est pas présumée. |
| Présent | Présent | Aucun travail. |

« Refaire le VTT et le MD » demande la transcription puis la synthèse sur le nouveau VTT ; un échec de transcription ne synthétise pas l'ancien. « Refaire le MD » utilise le VTT présent sans retranscrire. Ces actions exigent un MP3 local exploitable ; la seconde exige aussi un VTT. Les anciens fichiers restent disponibles jusqu'à la promotion de leurs remplaçants.

### Réglages, requête et confidentialité

L'adresse de synthèse est une base HTTP(S) dont le chemin est `/v1`, sans identifiants, requête ni fragment. Aucun service ou modèle personnel n'est préconfiguré. La clé est chiffrée dans les préférences privées à l'aide d'Android Keystore ; `state.json` ne contient que sa référence. Les anciennes références restent disponibles pour les tâches qui les utilisent. Seuls les réglages nécessaires aux étapes demandées sont requis : une synthèse sur VTT existant ne nécessite pas de réglages de transcription. Si la synthèse manque de configuration, aucune tâche de MD seul n'est créée : l'app affiche « Réglages de synthèse à compléter. Ouvre Réglages, rubrique Synthèse. » et note une ligne LLM par enregistrement concerné. Quand la transcription a déjà produit le VTT, celui-ci reste disponible et l'app invite à configurer le service puis à créer explicitement le travail MD.

L'app envoie `POST <base>/chat/completions` au service choisi, avec le modèle figé, le prompt résolu dans `system` et le VTT complet UTF-8 dans `user` : ni découpage ni troncature silencieuse. Le type et la date viennent du nom de l'enregistrement, la durée est estimée selon la taille et les intervenants proviennent des étiquettes du VTT ; une valeur indisponible est indiquée comme telle, sans inférer d'identité. Les synthèses complètes ont un délai de deux heures. Le premier choix doit contenir un texte assistant non vide, sans refus, appel d'outil ni indication de fin autre que `stop` si elle est présente ; une réponse invalide ou tronquée signalée ne remplace pas le MD. Le texte accepté est enregistré tel quel.

Le service reçoit donc le contenu complet de la transcription : son choix et ses conditions de confidentialité relèvent de l'utilisateur. Les erreurs affichées sont catégorisées ; le journal ne contient ni clé, ni VTT, ni réponse complète, ni contenu d'erreur brut du fournisseur. La capacité effective pour deux heures et la réception complète par le modèle choisi restent non vérifiées ; le test court ne les établit pas. Il n'y a pas de découpage automatique en cas de contexte trop long.

### Reprise, coût et provenance

Le dossier, les noms, le serveur, le modèle, la référence de clé et le prompt sont figés par tâche. L'empreinte du VTT existant est fixée à la création ; pour un VTT produit par la tâche, elle est fixée après promotion. Avant l'appel et avant la promotion du MD, un VTT modifié ou inaccessible suspend le travail ; une nouvelle action explicite utilise le fichier courant. Une clé ou un dossier inaccessible suspend aussi le travail, sans appel réseau tant que le contrôle préalable échoue. Une relance ne modifie pas silencieusement le contexte figé.

Chaque tentative réseau est persistée avant l'appel : une tentative initiale et au plus deux relances automatiques, avec des délais persistés d'au moins 60 puis 120 secondes. Les erreurs permanentes et les réponses invalides suspendent immédiatement ; après épuisement du budget, une relance manuelle repart avec trois tentatives. Une requête interrompue peut avoir été traitée et facturée : sa reprise peut coûter une seconde fois. WorkManager peut retarder la reprise ; le retrait des récents et l'arrêt forcé depuis les réglages Android ne sont pas équivalents, et ce dernier n'est pas couvert.

La réponse reçue est conservée temporairement en stockage privé avant écriture via `.part` / `.old`. Une erreur d'écriture peut laisser le résultat disponible pour une reprise sans nouvel appel. En cas de panne avant promotion, la restauration de l'ancien fichier est tentée ; si le fournisseur la refuse, l'ancien peut rester en `.old`. Si le fournisseur ne permet pas de relire le nom final après promotion, le nouveau peut rester en place, l'ancien peut avoir été supprimé et l'erreur est signalée. Le dossier doit accepter le renommage ; les fichiers temporaires ne sont pas listés comme résultats.

Les empreintes du VTT et du MD et le modèle sont conservés en privé pour un MD dont la promotion a été observée. Un VTT changé rend cette synthèse ancienne ; un MD modifié, importé ou sans métadonnées fiables a une provenance et un modèle inconnus. Après interruption, si le MD final est déjà identique au résultat en attente sans promotion observée, sa provenance reste inconnue et aucun nouvel appel n'est nécessaire. Une mort du processus après la réponse mais avant sa sauvegarde privée peut perdre la réponse et entraîner une nouvelle tentative facturée.

## Journal

- Chaque ligne a la forme `HH:mm:ss SOURCE message`.
- Les demandes reçues ou écartées, les tâches créées avec leur rang dans la file, les changements de phase, les envois de MP3, les progressions serveur à chaque pourcentage entier, les appels et réponses de synthèse, les écritures de VTT et de MD, les nouvelles tentatives, les suspensions, les échecs et les annulations sont journalisés en source STT (transcription) ou LLM (synthèse), précédés du nom de l'enregistrement quand il existe. Seuls y figurent les codes HTTP, durées et pourcentages : jamais de clé, d'adresse complète, de contenu de fichier, de corps d'erreur ni de texte de prompt. Une attente de verrou de plus de deux secondes est aussi notée.
- Les requêtes Drive sont journalisées sans en-têtes ni corps : méthode, chemin, statut, durée et taille.
- Les trames Bluetooth sont journalisées en hexadécimal, précédées de TX ou RX.

## Erreurs et reprise

| Situation | Comportement |
|---|---|
| Boîtier hors de portée à l'ouverture | La pastille passe au point vide, la liste s'appuie sur la dernière liste lue, et « Récupérer » se grise. |
| Coupure ou silence de 20 secondes pendant un transfert | Le fichier partiel est supprimé, l'enregistrement passe en erreur et la file s'arrête. |
| Aucune réponse à `0x0b` en 5 secondes | L'enregistrement passe en erreur et la file s'arrête. |
| Refus de `0x0b`, taille inattendue ou en-tête MP3 absent | L'enregistrement passe en erreur et la file continue avec le suivant. |
| Erreur 401 de Drive après le nouvel essai | L'envoi s'arrête avec le message « Accès Google à renouveler ». Les fichiers restent à envoyer. |
| Dossier Drive inaccessible | L'envoi est annulé avec un message. L'app ne remplace jamais le dossier choisi sans action de l'utilisateur. |
| Réponse perdue à l'envoi d'une transcription, 404 du serveur, MP3 modifié | La tâche est suspendue avec un message, sans renvoi automatique. |
| Réponse 429, 502, 503 ou 504 à l'envoi | La tâche attend 60 puis 120 secondes et renvoie le MP3, au plus trois tentatives, puis se suspend avec un message. |
| Synthèse interrompue ou erreur transitoire | Reprise bornée à trois tentatives au total, avec délais persistés ; un appel interrompu peut être facturé. |
| Synthèse refusée ou invalide, VTT changé, clé ou dossier inaccessible | Suspension visible ; aucun MD invalide ne remplace le fichier existant. |
| Dossier du téléphone inaccessible ou plein | L'écriture échoue, l'enregistrement passe en erreur, la file s'arrête et le message renvoie vers Réglages. |

## Architecture du code

### Unités en Kotlin pur, testées sans Android

| Fichier | Rôle |
|---|---|
| `Frame.kt`, `V05Session.kt` | Trames du protocole, session et transfert |
| `RecordName.kt` | Lecture et écriture des noms, type, durée, types MIME |
| `RecordingState.kt` | Liste réunie, compteurs et état affiché à partir des sources, des travaux et des erreurs |
| `RecoveryJob.kt`, `SendJob.kt` | File de récupération et file d'envoi |
| `PhoneDeletion.kt`, `PhoneDeletionOrchestrator.kt`, `PhoneDeletionPresentation.kt` | Préparation, coordination et présentation de la suppression locale |
| `DriveClient.kt` | Dossiers, listes, téléversement, renouvellement du jeton |
| `Journal.kt` | Journal sur sept jours et ses filtres |
| `LocalState.kt` | Lecture, écriture et reprise de `state.json` |
| `WebVtt.kt`, `TranscriptionState.kt`, `TranscriptionPreparation.kt` | Validation du VTT, état et préparation de la transcription |
| `SynthesisSelection.kt`, `SynthesisPrompt.kt`, `SynthesisState.kt`, `SynthesisStorage.kt` | Sélection, prompt, état durable et stockage privé de la synthèse |

### Unités Android

| Fichier | Rôle |
|---|---|
| `BleLink.kt`, `BoxConnection.kt` | Liaison Bluetooth, recherche et connexion au boîtier |
| `PhoneFolder.kt`, `PhoneStore.kt` | Accès au dossier du téléphone par le Storage Access Framework |
| `GoogleAuth.kt` | Autorisation Google, jeton et révocation |
| `WorkService.kt`, `TranscriptionWorker.kt`, `TranscriptionCoordinator.kt` | Service de premier plan, reprise WorkManager et coordinateur des tâches |
| `TranscriptionClient.kt`, `SynthesisClient.kt`, `TranscriptionSettings.kt`, `SynthesisSettings.kt` | Clients des services, réglages et protection des clés |
| `AppState.kt` | État observé par l'interface et actions de l'app |
| `DicteeApp.kt`, `MainActivity.kt` | Application, activité unique et pile d'écrans |
| `ui/Theme.kt`, `ui/Tokens.kt` | Tokens du design system en thème clair et sombre |
| `ui/…Screen.kt`, `ui/AssociateSheet.kt` | Un fichier par écran : accueil, liste, fiche, réglages, dossier Drive, journal, feuille d'association |
| `ui/Components.kt`, `ui/Rows.kt`, `ui/Dialogs.kt`, `ui/Modifiers.kt`, `ui/Playback.kt` | Composants partagés et lecteur audio |

Les dépendances comprennent Jetpack Compose (nomenclature, UI, foundation, material3, activity-compose), les coroutines Kotlin, WorkManager, Markwon pour le Markdown et `play-services-auth` 22.0.0, la version que cite la documentation de `revokeAccess`. Le lecteur audio utilise `MediaPlayer` d'Android.

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
| Interrupteur éteint | `#8A8A8A` | `#8A8A8A` |
| Réussite | `#05713A` sur `#DCF3E5` | `#4CC38A` sur vert à 16 % |
| À relire (avertissement) | `#7C4A00` sur `#FFF1CC` | `#F5B83D` sur ambre à 16 % |
| Erreur | `#B3130B` sur `#FDE4E1` | `#FF7B72` sur rouge à 16 % |
| Focus clavier | `#1F5FE0` | `#6C9BFF` |

Les aplats gris remplacent les contours : un champ ou un bouton secondaire n'a pas de bordure, et seule une carte garde un trait, avec une ombre douce en clair. Un bouton pressé prend la teinte du survol du design system, puisqu'un survol n'existe pas sur mobile.

La police d'interface est Figtree (graisses 400, 500, 600 et 700, en police variable) et la police à chasse fixe JetBrains Mono, embarquées dans l'app. Les titres sont en 700, le texte courant est à 15 sp. Le rayon est de 10 dp pour les boutons et les champs, de 12 dp pour les lignes et les encarts, de 16 dp pour les cartes et les dialogues, et les pastilles sont en pilule. Les boutons font 44 dp de haut, 36 dp pour le bouton compact (texte de 13 sp, zone tactile de 48 dp). L'icône de l'app est un D blanc sur fond noir, dessiné pour le lanceur dans la zone sûre des icônes adaptatives, avec la même forme en couche monochrome pour les icônes thématiques. Dans l'en-tête, l'icône est un carré noir incliné de 10° portant ce D, et elle s'inverse en thème sombre : le carré devient clair et le D sombre, ce qui tient lieu du liseré que le design system demande pour un carré presque noir sur fond sombre.

## Vérifications

### Par les tests et la compilation

1. Les tests du protocole passent.
2. Les noms se lisent et s'écrivent dans les deux sens, préfixes compris, et la durée se calcule à partir de la taille.
3. Le calcul de l'état affiché donne la valeur attendue pour chaque ligne du tableau « État affiché ».
4. La détection des fichiers déjà présents dans Drive tient compte des noms préfixés et des noms de la version 0.1.5. Une erreur 401 déclenche un seul nouvel essai avec un jeton neuf.
5. Les tokens de `Tokens.kt` correspondent à la copie de `tokens.json`.
6. L'APK se compile et le lint passe.

### Sur un téléphone

L'association se fait une fois, et l'app s'ouvre ensuite sur la liste. La liste montre les mêmes enregistrements que DOWAY, avec le bon type. « Récupérer » écrit les MP3 préfixés dans le dossier choisi. « Envoyer » les dépose dans « Dictée » sans créer de doublon avec les fichiers de la version 0.1.5 ni avec les exports de DOWAY. Une récupération continue écran éteint, et un boîtier éteint pendant un transfert ne laisse aucun fichier partiel.

Pour la 0.7.0, la recette physique reste à faire avec des fichiers d'essai sauvegardés : suppression simple et multiple, avertissements sans sauvegarde, refus d'une cible occupée, maintien des documents distants et disparition des fichiers locaux. Les tests Android doivent aussi être exécutés sur appareil ; la compilation ne les exécute pas.

Pour la 0.4.0, restent à vérifier sur appareil le retrait des récents pendant l'appel et l'écriture, la reprise après redémarrage, les interruptions répétées jusqu'à suspension, la relance manuelle, l'annulation, le maintien des anciens fichiers et les thèmes clair et sombre. Un essai explicitement autorisé d'un VTT synthétique représentatif de deux heures doit vérifier la réception et une réponse non tronquée, et relever les limites et tokens lorsqu'ils sont exposés. Un essai distinct autorisé sur une réunion réelle doit confirmer VTT puis MD en un appui. Sans ces essais et la vérification d'une construction signée, DIC-19 reste ouverte et la 0.4.0 n'est pas annoncée comme livrée.
