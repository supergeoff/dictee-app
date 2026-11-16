# DIC-21 : envoyer sur Drive avec ou sans le MP3

## Intention et périmètre

La version cible est 0.6.0. Chaque envoi manuel depuis la liste ou la fiche propose MP3 + VTT + MD ou VTT + MD. Le dernier choix confirmé est conservé dans state.json. Un VTT ou MD modifié remplace sa version dans le dossier Drive choisi, sans renvoyer les documents inchangés.

Les décisions déjà approuvées sont le remplacement d'un VTT DOWAY de même nom, sous réserve des droits Google, et le suivi par empreinte du contenu envoyé. Les règles complémentaires ci-dessous sont des propositions de conception, soumises à approbation.

## Approches

1. Renvoyer tous les VTT et MD à chaque appui est simple, mais réécrit les documents inchangés et ne résout pas le calcul de l'étape Drive.
2. Garder l'empreinte de chaque document envoyé permet de reconnaître les modifications sans dépendre du nom ou de la taille. Geoff a retenu cette approche.

Le travail prolonge SendJob, DriveClient, LocalState et le calcul des étapes existants. Il ne crée ni seconde file de traitement ni second dossier Drive.

## Choix des fichiers et interface

Les deux points d'entrée utilisent la même boîte de dialogue :

```text
Envoyer sur Drive
Dossier : <dossier choisi>
( ) MP3 + VTT + MD
( ) VTT + MD
Annuler                    Envoyer
```

Au premier usage, MP3 + VTT + MD est sélectionné pour conserver le comportement antérieur. Ensuite, le dernier choix confirmé est prérempli. Annuler ne change rien et ne lance aucun appel d'envoi.

Le choix vaut pour tout le lot, sans toucher aux enregistrements hors sélection. Le dialogue annonce le nombre d'enregistrements concernés. Un fichier absent du téléphone n'est ni créé ni récupéré par l'envoi. Le dialogue signale les documents manquants ; les fichiers disponibles peuvent être envoyés, mais une étape Drive incomplète ne devient pas faite.

Le compteur de la liste dépend du dernier choix confirmé. Une sélection explicite permet de rouvrir l'envoi pour changer ce choix, même si les fichiers de l'ancien choix sont déjà sauvegardés. Sur la fiche, une action secondaire « Envoyer sur Drive… » reste accessible lorsque l'étape Drive est faite ; le bouton principal conserve les règles du parcours DIC-26.

L'envoi reste manuel, sans déclenchement après VTT + MD. Le scope drive.file et le sélecteur de dossier existant restent inchangés.

## Détection des changements et preuve d'envoi

Pour VTT et MD, l'app calcule une empreinte SHA-256 du contenu local hors du fil d'affichage. Les lectures restent limitées aux documents, sans calcul systématique sur les MP3. Une empreinte différente, même à taille identique, remet le document à envoyer.

Après succès vérifié, state.json conserve, pour chaque compte Google, dossier et nom de document, l'identifiant Drive du fichier et l'empreinte locale envoyée. Le changement de compte ou de dossier ne réutilise pas une preuve d'une autre destination. La migration conserve les réglages existants et initialise ces preuves à vide.

Un VTT ou MD déjà présent mais sans preuve d'envoi est à vérifier par un premier envoi explicite : cet envoi remplace son contenu et établit la preuve. La simple présence du nom dans Drive ne suffit plus. Le MP3 conserve sa règle actuelle de présence, y compris la compatibilité des anciens noms, et n'est jamais remplacé dans cette carte.

La liste Drive expose les identifiants en plus des noms et tailles. Après rafraîchissement, un identifiant absent invalide la preuve correspondante. Une modification extérieure d'un fichier existant n'est pas suivie dans ce chantier ; l'app suit ses propres générations et envois, sans promettre une synchronisation bidirectionnelle.

La génération d'un VTT ou MD publie son nouveau contenu et son empreinte pour le calcul des étapes. Un échec de génération qui conserve l'ancien document n'invalide pas la sauvegarde de cet ancien contenu. Si le MD est signalé comme issu d'un ancien VTT, la sauvegarde de ce MD ne suffit pas à rendre le parcours complet.

## Création et remplacement dans Drive

SendJob détermine les fichiers requis par le choix, puis crée ceux qui manquent et remplace uniquement les VTT et MD non prouvés ou modifiés. DriveClient utilise l'identifiant existant pour une mise à jour reprenable par PATCH, sans suppression préalable ni création d'un doublon de même nom.

Un VTT DOWAY de même nom suit la même règle. Si drive.file ne donne pas accès au fichier, l'app signale la limite ; elle ne demande pas un accès complet et ne prétend pas l'avoir remplacé. Un fichier invisible pour ce droit ne peut pas être identifié avec certitude : cette limite doit être documentée dans la recette.

Si plusieurs fichiers visibles ont le même nom, l'app signale le conflit au lieu d'en choisir un arbitrairement. Elle ne supprime aucun doublon.

La preuve est enregistrée par fichier seulement après la réussite de son envoi et le contrôle de sa taille. Le contenu envoyé doit correspondre à l'empreinte conservée : les générations concurrentes sont exclues pour les documents concernés pendant leur capture et leur envoi, ou une copie temporaire stable est utilisée. Le plan retiendra le mécanisme le plus simple compatible avec la file existante.

Un échec conserve les fichiers locaux et n'établit aucune nouvelle preuve pour le fichier échoué. Les succès précédents du lot restent acquis. Une reprise n'envoie que les fichiers encore nécessaires. L'authentification et les erreurs conservent les règles existantes de SendJob.

## Calcul de l'étape Drive

Le dernier choix confirmé fixe les fichiers requis. VTT et MD doivent être présents et sauvegardés dans leur version actuelle ; le choix avec audio exige aussi le MP3 dans Drive. Sans audio, l'absence du MP3 dans Drive n'empêche pas l'étape d'être faite.

Une nouvelle version locale de VTT ou MD remet Drive à faire. Un changement de choix de « sans MP3 » vers « avec MP3 » peut également la remettre à faire. Un envoi partiel ou échoué ne termine pas l'étape. Le compteur de la liste et la fiche utilisent une même règle sans dépendance Android, testée avant son implémentation.

## Hors périmètre

Aucune suppression locale ou distante, aucun second dossier, aucun remplacement de MP3 et aucune synchronisation bidirectionnelle ne sont ajoutés. DIC-24 pourra réutiliser les preuves d'envoi, mais la suppression ne fait pas partie de DIC-21.

## Vérification et livraison

Les tests couvrent les deux choix, leur persistance et l'annulation ; les empreintes différentes à taille identique ; la migration sans preuve ; le changement de destination ; le remplacement par identifiant ; les conflits de noms ; les échecs et reprises partielles ; le calcul partagé de l'étape Drive et des compteurs.

Les vérifications locales sont testDebugUnitTest, assembleDebug et lint. Une recette sur téléphone vérifie les deux points d'entrée, les deux choix, le remplacement sans doublon et la conservation des fichiers locaux en cas d'échec. Le cas DOWAY doit être vérifié avec les droits réels, sans élargir le scope.

La livraison met à jour docs/specification.md et prépare la version 0.6.0. Tout envoi réel de recette, push, fusion ou publication de release attend un accord explicite distinct. L'accord sur cette conception autorise la préparation du plan, pas son exécution.
