# DIC-20 : enchaîner récupération et VTT + MD

## Intention et périmètre

La version 0.5.0 ajoute un réglage désactivé par défaut : « Créer le VTT et le MD après récupération ». Un appui sur Récupérer, dans la liste ou la fiche, suffit alors à récupérer le MP3 puis à produire les fichiers manquants. Drive reste manuel. Aucun écran ni bouton supplémentaire n'est créé dans la liste ou la fiche.

L'activation ne traite jamais les MP3 déjà présents. La désactivation empêche les prochaines demandes automatiques sans annuler celles déjà programmées. Ces règles et l'approche ci-dessous ont été approuvées en conversation.

## Approches

- **Retenue : inscrire chaque récupération réussie dans la file persistante existante.** Le premier enregistrement peut commencer son traitement sans attendre la fin du lot. La sérialisation, les reprises et les remplacements restent ceux de DIC-19.
- **Écartée : attendre la fin du lot.** Cette approche retarde le traitement et exige de conserver une seconde liste de demandes entre récupération et programmation.
- Une file de traitement indépendante est exclue : elle dupliquerait la reprise et pourrait exécuter plusieurs appels simultanément.

## Comportement

Le réglage est enregistré dans l'état local et apparaît dans les réglages existants, avec cette explication : « Après chaque récupération, crée les fichiers manquants. L’envoi sur Drive reste manuel. » Un état ancien sans ce champ équivaut à désactivé.

Au début de chaque récupération individuelle, l'application capture la valeur du réglage. Une récupération échouée ne déclenche aucun traitement. Après validation et écriture du MP3, une récupération réussie crée une demande durable pour cet enregistrement seulement, si le réglage capturé était actif. Les MP3 déjà présents ne sont jamais recherchés pour remplir la file.

La demande utilise le comportement MISSING existant : elle produit le VTT absent puis le MD absent, sans remplacer les fichiers présents. Les reprises explicites restent les seules actions de remplacement. Une nouvelle récupération réussie avec l'option active est également concernée, sous réserve des protections existantes contre l'écrasement d'un MP3 utilisé.

Une transcription ou une synthèse s'exécute à la fois dans la file existante. La récupération Bluetooth peut continuer pendant le traitement réseau ; aucun appel de programmation ne doit attendre un verrou de transfert que son propre appelant détient. La ligne téléphone, VTT, MD, Drive et le rang d'attente existants rendent compte du traitement.

## Persistance et interruptions

L'intention automatique est persistée avant le transfert, avec l'enregistrement et la valeur capturée. Elle ne constitue pas encore une autorisation de transcrire un fichier dont la récupération n'est pas validée. Le succès durable de récupération rend cette demande programmable. La demande reste conservée jusqu'à la création durable de sa tâche dans la file existante ; sa consommation est idempotente, sans doublon pour un travail actif.

Au redémarrage, l'application réconcilie ces demandes et les tâches persistantes, sans parcourir les autres MP3 pour créer des travaux. Un transfert interrompu conserve l'état d'erreur de récupération et n'autorise pas de transcription. Une demande issue d'une récupération validée reprend même si le réglage a ensuite été désactivé. WorkManager assure la reprise des tâches déjà programmées selon les mécanismes existants et les limites Android.

Les tâches créées automatiquement démarrent le même suivi interactif unique que les demandes manuelles. Au premier plan, les tâches durables non suspendues reprennent ce suivi depuis leur phase enregistrée, sans soumettre à nouveau un MP3 déjà accepté ; le worker périodique reste le secours après interruption. Le service reste comptabilisé pendant le suivi.

Cette garantie concerne la file VTT + MD, pas la reprise automatique d'un transfert Bluetooth interrompu.

## Réglages manquants et erreurs

Les contrôles existants des services restent applicables. Une configuration de transcription absente ou invalide conserve une demande en attente de configuration, sans bloquer les récupérations suivantes. La fiche indique la raison et donne accès aux réglages. Une fois les réglages enregistrés, les demandes concernées sont réexaminées. Une synthèse non configurée conserve le comportement existant : le VTT peut être produit, puis le MD attend une action explicite après configuration.

Une erreur de transcription ou de synthèse concerne uniquement son enregistrement ; la file passe aux suivants. Les politiques existantes de reprise et de suspension restent inchangées. Une erreur de programmation ne transforme jamais une récupération réussie en échec Bluetooth.

Une notification signale la fin du VTT et du MD ou l'erreur pour l'enregistrement concerné. Une attente de configuration n'est pas annoncée comme une réussite. Les notifications respectent l'autorisation Android ; l'état reste consultable dans l'app si elles sont refusées. Aucun secret n'entre dans les messages ou le journal.

## Vérification et livraison

Les tests écrits avant le code couvrent : valeur par défaut et relecture de l'état ; absence de traitement rétroactif ; capture du réglage par récupération ; succès et échec ; désactivation avec demandes déjà programmées ; fichiers existants conservés ; demandes durables sans doublon après interruption ; réglages manquants ; poursuite de la file après erreur et absence d'envoi Drive automatique.

Les tests d'orchestration vérifient aussi l'absence d'interblocage entre récupération et programmation. Les contrôles de livraison sont testDebugUnitTest, assembleDebug et lint. La recette sur téléphone couvre un lot, une interruption, la ligne des étapes et les notifications. Un test local ne vaut pas validation sur téléphone.

La livraison met à jour docs/specification.md et porte versionName à 0.5.0 avec un versionCode supérieur à 15. Le plan détaille les fichiers et les tests. La publication de l'APK signé, les push et la fusion attendent des accords explicites séparés.

## Approbation

Geoff a approuvé ce document le 15 novembre 2026. Le plan d'implémentation doit être relu et approuvé séparément avant toute modification du code produit.
