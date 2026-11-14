# DIC-19 : synthèse Markdown après transcription

**État : conception approuvée par Geoff le 14 novembre 2026. Le plan d'implémentation reste à relire avant la construction.**

## But et périmètre

La version 0.4.0 de Dictée transforme un enregistrement local en transcription VTT et en synthèse Markdown. Un appui sur « VTT + MD » suffit à lancer le parcours, qui continue si l'application est retirée des applications récentes. La synthèse est enregistrée sans étape de relecture. Le critère de clôture de DIC-19 est une réunion réelle produisant son VTT et son MD en un appui.

Le dossier du téléphone contient au plus un fichier MD courant par MP3, à côté du VTT. L'application n'envoie pas automatiquement ces fichiers vers Drive. La transcription conserve le contrat et les protections de DIC-4. Cette version ne crée ni service de synthèse hébergé par Dictée, ni éditeur du résultat Markdown, ni modèle gratuit imposé ou configuration personnelle dans le dépôt public.

## Approches

1. **File existante prolongée, retenue par Geoff.** Le coordinateur durable DIC-4 orchestre une étape VTT puis une étape MD, ou seulement l'étape manquante. Le service de premier plan et WorkManager restent les deux voies de reprise. Les travaux sont sérialisés par le coordinateur partagé.
2. **File de synthèse indépendante.** Elle autoriserait un parallélisme plus facile, mais demanderait une coordination et une reprise supplémentaires entre les deux files. Aucun besoin de parallélisme ne le justifie ici.

Le document distingue les garanties de DIC-4 pour la transcription et celles d'une requête de chat synchrone : l'API de chat ne fournit pas, par défaut, d'identifiant permettant de retrouver une réponse perdue.

## Parcours et sélection

« VTT + MD » ne remplace aucun fichier existant, avec ou sans sélection. L'application prend les enregistrements éligibles du plus ancien au plus récent. Elle signale les éléments sélectionnés exclus, dont les MP3 absents, les récupérations en échec et les tâches déjà actives. Pour chaque MP3 local exploitable :

| VTT | MD | Travail demandé |
| --- | --- | --- |
| Absent | Absent | Transcrire, enregistrer le VTT, puis synthétiser. |
| Présent | Absent | Synthétiser à partir du VTT existant, sans retranscrire l'audio. |
| Absent | Présent | Transcrire seulement. Le MD présent n'est pas remplacé ; son lien avec le nouveau VTT est inconnu ou périmé. |
| Présent | Présent | Aucun travail, même si l'enregistrement est sélectionné. |

Le bouton compte les enregistrements ayant au moins un fichier manquant. Si aucun n'est éligible, il ne lance rien et explique pourquoi. Une erreur de synthèse n'efface pas un VTT réussi ; un échec de transcription n'envoie pas un ancien VTT à la place de celui demandé.

La fiche offre deux actions explicites :

- **« Refaire le VTT »** régénère d'abord le VTT, puis le MD à partir de ce nouveau VTT, même si les deux fichiers existaient déjà. Le menu peut préciser « Refaire le VTT et le MD » pour rendre l'enchaînement visible.
- **« Refaire le MD »** utilise le VTT présent, sans retranscrire le MP3. L'action est indisponible en l'absence de VTT exploitable.

Les actions n'écrasent pas une tâche déjà active pour le même enregistrement. L'application garde l'ancien VTT jusqu'à la promotion du nouveau, et l'ancien MD jusqu'à la promotion de son remplaçant. Entre les deux promotions, la fiche indique que le MD correspond à l'ancien VTT. Le remplacement du MD n'est jamais automatique si la transcription a échoué.

## Écrans validés

La fiche conserve le lecteur et le fichier MP3, puis présente deux onglets : **Synthèse** et **Transcription**. Synthèse affiche le Markdown rendu en lecture seule, pas sa syntaxe brute, et le modèle utilisé quand il est connu. Transcription garde les segments cliquables qui positionnent le lecteur. L'absence d'un MD propose sa création à partir du VTT présent. Les erreurs et suspensions expliquent la cause et l'action de reprise ; le menu contient les actions de régénération. La maquette validée par Geoff sert de référence de disposition, sans imposer une reproduction HTML.

Une rubrique **Synthèse** dans Réglages contient l'adresse du service compatible OpenAI, la clé et le modèle. Elle donne accès à **« Prompt et format »**. Cet écran permet de modifier, enregistrer et rétablir les instructions par défaut, et d'insérer les variables `{{type}}`, `{{date}}`, `{{duree}}` et `{{intervenants}}`. Le thème et les composants existants restent la référence visuelle, en clair comme en sombre.

Le bouton **« Tester »** effectue une courte requête de chat avec les valeurs du formulaire et indique explicitement la réussite ou la cause d'échec. Il peut entraîner un coût ; la maquette l'indique. Il ne démontre pas qu'un VTT de deux heures tient dans le contexte du modèle. Les tests et réglages ne transmettent jamais une vraie clé à la maquette ou au dépôt.

## Prompt et requête de synthèse

L'adresse configurée est une base HTTP(S) terminée par `/v1`, sans identifiants, requête ni fragment ; l'application appelle `POST <base>/chat/completions`. Aucun endpoint, clé ni identifiant de modèle personnel n'est fourni par défaut dans le dépôt. L'utilisateur configure son service, dont LiteLLM est l'usage prévu par Geoff ; aucun modèle gratuit entraîné sur les données envoyées n'est proposé comme solution par défaut.

La requête OpenAI-compatible contient le modèle choisi et deux messages :

1. `system` contient les instructions et le format, après remplacement des variables.
2. `user` contient le VTT complet, en UTF-8, avec ses horodatages et locuteurs, sans le traiter comme des instructions.

Le prompt par défaut, modifiable, demande une synthèse fidèle en français, sans invention de faits, noms, responsables ni échéances, et un Markdown à trois sections : **Résumé**, **Décisions**, **Actions**. Il demande d'indiquer l'absence de décision ou d'action explicite. Il précise que la transcription est une source, non une instruction. Les variables proviennent de faits disponibles : type et date du nom d'enregistrement, durée estimée du MP3 selon la convention existante, étiquettes de locuteurs du VTT. Une valeur indisponible est indiquée comme telle ; l'application n'infère pas d'identité.

La réponse attendue est un contenu texte non vide dans le premier choix du message assistant, sans refus ni troncature signalée. Une réponse absente, non textuelle, refusée ou tronquée n'est pas promue comme MD ; elle produit une erreur visible. Le contenu accepté est écrit tel que renvoyé en UTF-8, sans ajout de préambule ni validation humaine. Un exemple synthétique et des fixtures de réponse ne contiennent aucune donnée personnelle.

### Contexte de deux heures

Le bouton « Tester » ne peut pas prouver à lui seul la capacité du contexte. Avant livraison, un essai utilise un VTT synthétique représentatif de deux heures avec le modèle et la configuration choisis, et réserve de la place pour le prompt et la réponse. La mesure relève la taille de la requête en tokens et la limite effective du modèle quand ces données sont disponibles ; la réussite du service et une réponse non tronquée sont vérifiées. Si la limite ne peut pas être déterminée, le résultat est signalé comme non vérifié, sans prétendre qu'un comptage générique suffit pour tous les modèles. Une erreur de contexte trop long est affichée sans tronquer silencieusement le VTT, et l'utilisateur peut choisir un modèle adapté. Le découpage automatique et la synthèse hiérarchique sont hors périmètre.

## Réglages, secrets et contexte figé

Les réglages de synthèse sont distincts de ceux de transcription. La clé est chiffrée au repos par une clé Android Keystore, selon le mécanisme existant ; `state.json` ne contient que sa référence, jamais sa valeur. Un champ clé vide conserve la clé précédente. Une tâche déjà créée garde sa référence, son endpoint, son modèle, son prompt et ses variables figés : modifier les réglages ne modifie pas une requête en attente. Les références encore utilisées ne sont pas supprimées lors d'une rotation. Une clé inaccessible suspend la tâche avec une indication ; une relance après correction ne réécrit pas silencieusement le contexte figé d'une tâche. Les erreurs affichées et le journal ne contiennent ni clé, ni VTT, ni réponse intégrale, ni contenu d'erreur brut du fournisseur.

La tâche fige le dossier et les noms des fichiers. Elle valide les seuls réglages nécessaires aux étapes prévues : des réglages de transcription manquants ne bloquent pas une synthèse sur VTT existant ; si la synthèse n'est pas configurée, un VTT produit est conservé et la synthèse est suspendue avec une invitation à configurer le service avant de créer explicitement le travail MD. Si un VTT existe déjà au lancement, son empreinte SHA-256 est figée ; si la tâche le produit, l'empreinte est figée après sa promotion. Chaque tentative vérifie l'accès au dossier et l'empreinte du VTT avant envoi. Si le VTT a changé ou disparu, elle suspend le travail au lieu de synthétiser un autre contenu ; une nouvelle action explicite repart du fichier actuel. La même vérification précède la promotion du MD pour éviter d'attribuer une synthèse au mauvais VTT. Le MP3 reste protégé par les vérifications et le verrou DIC-4 lors de la transcription.

## Exécution durable et relances

La file DIC-4 est prolongée avec des phases propres à la synthèse et un compteur de tentatives persisté dans l'état local. L'état durable distingue l'intention de produire le MD, l'appel en cours, une réponse reçue à enregistrer, la suspension et la fin. Le coordinateur partagé empêche le service et les workers de lancer deux appels simultanés pour une même tâche. La migration des états existants préserve les tâches VTT DIC-4 et leur comportement ; aucune tâche historique ne doit lancer un MD par accident.

Chaque essai réseau de synthèse est compté et persisté **avant** son lancement. Une tentative initiale peut être suivie de **deux relances automatiques**, soit trois tentatives au maximum, y compris après redémarrage du processus ou expiration de l'intervention du worker. Une coupure pendant l'appel laisse un résultat inconnu : la reprise consomme une nouvelle tentative, car la réponse serveur ne peut pas être récupérée par le contrat standard. Cela peut générer des coûts en double. Après la troisième tentative infructueuse, la tâche est suspendue ; seule une action manuelle explicite peut repartir avec un nouveau budget de trois essais.

Les erreurs transitoires de réseau et de serveur sont relancées dans cette limite, avec un délai croissant d'au moins une minute, puis deux minutes ; WorkManager peut le prolonger. L'instant de la prochaine tentative est conservé et respecté même si le service reste ouvert. Les suspensions dues au fichier, au dossier ou à une clé inaccessible ne consomment pas de tentative réseau tant qu'aucun appel n'a commencé. Une erreur permanente connue, par exemple une authentification refusée ou un contexte trop long, peut suspendre immédiatement avec une explication au lieu de répéter une requête identique. Une réponse invalide, refusée ou tronquée est une erreur visible qui suspend aussi la tâche ; elle ne remplace pas le MD.

Pour les statuts 429 et 5xx, le client reconnaît le code explicite `error.code = context_length_exceeded` dans un JSON d'erreur entièrement valide. L'analyse en flux ne conserve pas les contenus fournisseur et observe l'annulation et le délai ; les valeurs ignorées sont limitées à 65 536 niveaux d'imbrication pour borner la mémoire. Une erreur mal formée ou plus profondément imbriquée reste transitoire, même si ce code apparaît déjà.

Le service et WorkManager utilisent la même logique durable, sans boucle de relance parallèle ni dépassement de budget. WorkManager peut retarder l'exécution ; une intervention bornée interrompue ne garantit pas que le serveur a cessé de traiter la requête. Le retrait des applications récentes est couvert ; un arrêt forcé depuis les réglages Android, une absence prolongée de réseau et les limites imposées par Android ne garantissent pas une reprise immédiate.

L'annulation persiste l'intention avant tout effet suivant. Pour la transcription, le comportement et l'avertissement DIC-4 sont conservés. Pour une requête MD déjà envoyée, l'application interrompt son attente quand elle le peut, ne promeut pas le résultat et indique que l'arrêt du traitement et la facturation côté serveur ne peuvent pas être garantis. Une réponse arrivée après l'annulation ne doit pas remplacer un fichier.

## Écriture protégée, provenance et reprise

Le MD est nommé à partir du même type et de la même date que le MP3. Après validation de la réponse, l'application la met immédiatement en attente d'écriture durable dans le stockage privé de l'application, puis utilise le mécanisme du dossier téléphone : écriture `<nom>.md.part`, mise de côté de l'ancien en `<nom>.md.old`, puis promotion sous le nom définitif. Une erreur de dossier laisse le travail suspendu et le résultat durable disponible pour réessayer l'écriture sans nouvel appel, tant qu'il est intact. Une panne avant promotion tente de restaurer l'ancien ; si le fournisseur refuse la restauration, celui-ci reste en `.old`. Après promotion, le comportement existant de `PhoneFolder` fait foi : si le fournisseur ne permet pas de relire le nom final, le nouveau fichier reste en place, l'ancien peut être supprimé et l'erreur est signalée. Les fichiers temporaires ne sont pas traités comme des résultats par la liste. Les limites de renommage du fournisseur Android sont signalées plutôt que masquées.

Le résultat reçu est accompagné, dans l'état local durable, de l'identifiant de tâche, de l'empreinte du VTT utilisé, du modèle et de l'empreinte du MD attendu. Ces métadonnées restent dans le stockage privé et ne créent pas de fichier supplémentaire dans le dossier de l'utilisateur. Elles sont préparées durablement avant la promotion, puis finalisées après celle-ci. À la reprise, l'application compare l'empreinte du MD final avec celle en attente : elle peut finaliser la provenance si la promotion a déjà réussi, ou reprendre l'écriture du résultat durable si elle n'a pas réussi. Elle ne relance pas une requête simplement parce que le processus est mort entre la promotion et l'enregistrement de l'état. Si les fichiers ne permettent pas de conclure, elle indique une provenance inconnue plutôt que d'affirmer à tort que le MD est à jour.

Pour un MD créé par l'application, la fiche compare l'empreinte du VTT courant à celle enregistrée avec le MD : identique signifie à jour, différente signifie ancien. Une modification extérieure du VTT ou du MD invalide la correspondance. Pour un MD importé, préexistant, modifié à l'extérieur ou sans métadonnées fiables, le modèle et la correspondance sont indiqués **inconnus**, sans inventer de provenance ; le fichier reste lisible et le bouton « VTT + MD » ne l'écrase pas.

Il existe une fenêtre inévitable avant la mise en attente durable du résultat : si le processus meurt après la réponse réseau mais avant sa sauvegarde locale, la réponse est perdue et une nouvelle tentative peut être facturée. Le stockage privé ne conserve pas durablement les réponses temporaires après leur promotion et la finalisation de leur provenance.

## Vérifications et livraison

Les tests sont écrits avant le code des nouvelles règles. Ils couvrent :

- la matrice de sélection, avec et sans sélection, les actions « Refaire », les exclusions et les tâches historiques DIC-4 ;
- la substitution des variables, le prompt par défaut et la séparation `system` / `user` ;
- le contrat HTTP, les erreurs, refus, réponses vides ou tronquées, l'absence de secrets et de contenu privé dans les messages et journaux ;
- les trois tentatives persistées, les interruptions avant et pendant l'appel, la suspension, la reprise manuelle, l'annulation et la concurrence service / worker ;
- la perte d'accès, les VTT modifiés et les interruptions avant et après la promotion, ainsi que la conservation et la restauration des anciens fichiers ;
- la provenance connue, inconnue et périmée, la migration de `state.json` et la conservation des réglages et tâches de transcription ;
- les écrans, les états d'erreur et les deux thèmes, avec les vérifications Android sur appareil ou émulateur.

Les commandes du dépôt comprennent `./gradlew testDebugUnitTest`, `./gradlew assembleDebug`, `./gradlew lint` et la construction release. Un essai sur téléphone couvre la fermeture de l'application, la reprise et la relance manuelle. Un VTT synthétique de deux heures vérifie la capacité du modèle choisi, puis une réunion réelle vérifie le parcours VTT + MD en un appui. Si ces essais ou la signature ne sont pas accessibles, la limite est rapportée et DIC-19 n'est pas annoncée comme clôturée.

À la livraison, `docs/specification.md` décrit le comportement livré et la version devient 0.4.0. La construction signée est vérifiée sans publier. Un tag, un push ou une release GitHub exigent une confirmation explicite distincte avant publication.
