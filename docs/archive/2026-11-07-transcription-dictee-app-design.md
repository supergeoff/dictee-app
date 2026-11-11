# Transcription manuelle dans dictee-app

Statut : abandonnée le 11 novembre 2026. La transcription passe en tâche de fond et sera conçue dans sa propre spec (carte Kaneo DIC-4). Ce document est gardé comme archive et ne sert pas de base au code.

## Intention

Depuis la fiche d'un enregistrement MECHEN déjà récupéré sur le téléphone, lancer sa transcription et obtenir un fichier VTT horodaté avec les locuteurs. Cette première étape doit rester simple ; le suivi d'une tâche serveur en arrière-plan viendra ensuite.

## Périmètre

- L'utilisateur lance la transcription en appuyant sur « Transcrire ». Rien ne part automatiquement après la récupération.
- L'app utilise le MP3 présent dans le dossier du téléphone et ne le modifie pas.
- Le serveur produit le VTT et les étiquettes de locuteurs. L'app conserve le corps reçu sans le reconstruire.
- Le résultat est enregistré dans le même dossier et sous le même nom que le MP3, avec l'extension `.vtt`, par exemple `Note-20261107120000.vtt` pour `Note-20261107120000.mp3`.
- Le nouveau VTT remplace celui qui existe déjà, mais seulement après réception complète et vérification de la réponse. Une erreur avant le remplacement ne touche pas à l'ancien fichier.
- Le renommage des locuteurs, la synthèse, le lancement automatique et les tâches persistantes sont hors périmètre. L'envoi ou le remplacement du VTT sur Drive ne fait pas partie de cette étape.

## Approches

1. **Appel synchrone, retenu.** L'app envoie le MP3 et attend la réponse dans la même requête. C'est le chemin le plus court vers un VTT utilisable, avec une limite connue si l'app ou la connexion s'interrompt.
2. **Tâche serveur en arrière-plan.** L'app envoie `background=true`, garde l'identifiant de tâche, puis interroge le serveur et reprend après interruption. Cette solution répond mieux aux longues réunions, mais demande un suivi local qui vient dans une étape ultérieure.

## Parcours

La fiche d'un enregistrement dont le MP3 est disponible sur le téléphone propose « Transcrire ». Sans MP3 ou lorsque sa récupération est en erreur, l'action est indisponible. L'app vérifie le dossier et les réglages avant l'envoi ; elle indique ce qui manque si elle ne peut pas démarrer.

Pendant la requête, la fiche affiche « Transcription en cours » sans pourcentage inventé. Elle empêche une deuxième transcription du même enregistrement et le changement de dossier pendant l'opération. Elle ne lit pas un MP3 pendant sa récupération ou son remplacement. Une fois le VTT écrit, la fiche indique la réussite et actualise la liste des fichiers du dossier.

**Proposition de fonctionnement :** quitter la fiche n'annule pas la requête tant que le processus de l'app reste actif ; en rouvrant la fiche, l'utilisateur retrouve son état. L'app ne crée pas de service de premier plan pour maintenir la requête et Android peut interrompre le processus.

En cas d'erreur, la fiche affiche un bandeau qui explique la raison et propose « Réessayer ». Une relance repart de l'envoi du MP3. Elle ne se produit jamais automatiquement. Les erreurs de récupération, d'envoi et de transcription gardent chacune leur étape, de sorte que « Réessayer » relance l'étape du message affiché.

## Service de transcription

Le service reçoit le MP3 et les réglages, puis envoie une requête multipart `POST` à l'adresse de base configurée, suivie de `/audio/transcriptions` :

- `file` : le MP3 lu depuis le dossier du téléphone, sans le charger entièrement en mémoire ;
- `model` : `dictee-diarize` ;
- `response_format` : `vtt` ;
- `language` : la langue configurée, `fr` par défaut ;
- `Authorization` : `Bearer` suivi de la clé d'API.

L'adresse de base comprend le préfixe `/v1`. L'app retire une éventuelle barre finale avant d'ajouter le chemin, sans ajouter un second `/v1`.

Le service traite une réponse HTTP réussie comme un résultat utilisable seulement après la fin normale de sa réception et un contrôle minimal du format WebVTT : texte UTF-8 qui commence par l'en-tête `WEBVTT`. Un VTT sans séquence parlée reste acceptable. Une erreur de transport ou une réponse vide ou qui n'est pas du VTT ne remplace pas le fichier existant. Ce contrôle ne détecte pas toutes les troncatures ou erreurs de contenu si le serveur termine normalement sa réponse. Les balises de locuteur présentes, telles que `<v A>`, restent inchangées.

**Proposition à confirmer :** porter le délai de lecture sans réponse à deux heures pour cette requête. Ce n'est pas une garantie de durée totale : une limite du proxy, une coupure réseau ou Android peuvent interrompre la connexion plus tôt. La mesure disponible d'environ 51 minutes pour une réunion de deux heures ne suffit pas à garantir ce délai pour tous les enregistrements.

## Écriture dans le dossier

La réponse complète est d'abord conservée dans un fichier temporaire privé à l'app, puis contrôlée avant toute modification du VTT du dossier. L'app réutilise le mécanisme de `PhoneFolder.write` : elle écrit un `.part`, met l'ancien document de côté en `.old`, puis renomme le nouveau document sous le nom définitif. Elle ne supprime pas le MP3.

Si l'écriture échoue avant le renommage final, l'app tente de remettre l'ancien VTT sous son nom et signale l'erreur. Si le fournisseur de documents refuse aussi cette restauration, l'ancien contenu reste dans le `.old` et le message indique comment le récupérer. Le fournisseur peut aussi échouer après le renommage final : l'app signale alors que l'état du fichier doit être vérifié, sans promettre que l'ancien VTT est toujours à sa place. Les tests doivent couvrir ces limites plutôt que supposer un remplacement atomique garanti par tous les fournisseurs.

## Réglages et clé

Une rubrique « Transcription » contient l'adresse de base du serveur, la langue et la clé d'API. La langue proposée par défaut est `fr` ; l'adresse et la clé sont saisies par l'utilisateur, sans valeur personnelle dans le dépôt public. La clé est masquée à l'écran et chiffrée au repos avec une clé protégée par Android Keystore. Elle ne figure ni dans le journal, ni dans les messages d'erreur, ni dans les tests ou les fichiers du dépôt. L'utilisateur peut la remplacer ou l'effacer.

Le choix d'un bouton de test et de sa route n'est pas arrêté ici : l'existence de `GET /v1/models` n'a pas été vérifiée. Il ne conditionne pas la transcription de cette première étape.

## Découpage et évolution

Le service réseau, l'écriture dans le dossier et l'écran restent séparés. Le service présente à l'app une opération « MP3 vers VTT » sans exposer les détails HTTP à l'écran. Une étape ultérieure pourra remplacer son transport synchrone par l'envoi d'une tâche, l'interrogation de son identifiant et la reprise locale ; cette évolution pourra aussi demander des changements d'état et d'interface, qui ne sont pas conçus ici.

La requête de cette étape n'est pas une tâche persistante. La fermeture du processus, la perte du réseau ou une limite intermédiaire peuvent faire perdre le résultat : il faut alors relancer. Aucun traitement en arrière-plan ni reprise après redémarrage n'est garanti.

## Vérification prévue

- Tester la requête multipart, les paramètres, le chemin construit avec ou sans barre finale et l'en-tête d'authentification, sans clé réelle.
- Tester une réponse VTT avec locuteurs, un VTT sans séquence parlée, une réponse vide, tronquée ou invalide, ainsi que les erreurs HTTP et réseau, dont `429 long_audio_busy`.
- Tester la création du VTT et le remplacement d'un ancien résultat, ainsi que sa conservation si l'appel ou la validation échoue. Vérifier les échecs de restauration et les limites du fournisseur de documents.
- Tester la disponibilité de l'action, les états en cours et en erreur, le bon aiguillage de « Réessayer », le retour sur la fiche et le blocage du changement de dossier pendant le travail.
- Vérifier le stockage et l'effacement de la clé sans exposition dans le journal, puis exécuter `./gradlew testDebugUnitTest` et `./gradlew assembleDebug`.
- Avant de conclure que le parcours fonctionne, l'essayer sur Android avec un court MP3 et `dictee-server`. Le comportement sur un enregistrement long reste à mesurer séparément.
