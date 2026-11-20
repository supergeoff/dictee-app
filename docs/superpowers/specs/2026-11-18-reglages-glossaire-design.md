# Réorganisation des réglages et glossaire complémentaire

## Intention

Séparer les réglages par usage et harmoniser leur présentation sur le design system existant, en prenant le formulaire de synthèse comme référence. Ajouter un glossaire local destiné uniquement à la transcription, transmis par le champ standard OpenAI `prompt`.

## Approches et choix

Deux organisations ont été considérées : des sections dépliables sur la page actuelle, ou des sous-écrans dédiés comme celui du prompt. L’utilisateur a validé les sous-écrans, avec le stockage et Google Drive réunis. Les sections dépliables maintiendraient une longue page et répondraient moins bien à la séparation demandée.

Pour le glossaire, un champ multiligne et une saisie transformée en bulles ont été proposés. L’utilisateur a choisi les bulles supprimables par une croix. La représentation persistée reste une chaîne unique, une entrée par ligne.

## Navigation

La page Réglages présente, dans cet ordre, les accès Boîtier, Stockage et Google Drive, Transcription, Synthèse. Elle conserve ensuite l’Apparence, le Journal et la Version avec leur présentation actuelle.

Chaque rubrique ouvre son propre écran avec un titre et un retour. Les accès existants depuis les messages d’erreur ou les enregistrements ouvrent directement la bonne rubrique, plutôt que de faire défiler la page principale.

- Boîtier : association et changement du boîtier, avec les protections actuelles pendant une récupération.
- Stockage et Google Drive : dossier du téléphone, activation Drive, compte et dossier distant. Les protections pendant les opérations restent inchangées.
- Transcription : traitement automatique, adresse, clé, langue, modèle et glossaire complémentaire, puis enregistrement et messages associés.
- Synthèse : adresse, clé, modèle, test et enregistrement, avec accès à l’écran existant Prompt et format.

## Présentation

Les écrans utilisent les composants existants `TopBar`, `Section`, `GroupCard`, `ListRow`, `DicteeTextField`, `PrimaryButton` et `SecondaryButton`, les couleurs `Dictee.colors`, les styles `Dictee.type` et les dimensions des tokens. Aucun champ ni bouton Material brut ne subsiste dans le formulaire de transcription.

Les bulles reprennent les couleurs neutres, les formes et la typographie du design system. Elles reviennent à la ligne selon la largeur disponible. Les entrées longues restent lisibles. La croix possède une zone tactile accessible et un libellé « Retirer [entrée] ». Les thèmes clair et sombre, le clavier et les grandes tailles de texte doivent rester utilisables.

## Édition du glossaire

Le réglage porte le nom « Glossaire complémentaire ». Un seul champ de saisie permet d’ajouter une entrée ; la validation transforme la ligne en bulle et vide la saisie. Un collage de plusieurs lignes crée une bulle par ligne non vide après validation. Les espaces internes, accents, parenthèses et variantes sont conservés, sans tri ni dédoublonnage automatique. Les lignes vides ne créent pas de bulle.

Chaque bulle contient la ligne complète, y compris la forme optionnelle `Terme (souvent entendu : a, b)`. Sa croix retire cette occurrence du brouillon local, sans requête au serveur. Le bouton Enregistrer sauvegarde les réglages et le glossaire. Une saisie non encore transformée en bulle est intégrée lors de l’enregistrement, afin de ne pas être perdue silencieusement.

Le texte envoyé est exactement la chaîne représentée par les bulles, jointes par `\n`, sans préambule ni consigne ajoutée. Les limites portent sur cette chaîne : au plus 500 lignes et 20 000 caractères Unicode. Un dépassement affiche une erreur et empêche l’ajout ou l’enregistrement concerné ; il ne tronque jamais le contenu et ne modifie pas la version déjà enregistrée.

Une aide explique le format et le fait que ce vocabulaire complète celui du serveur lorsque celui-ci possède un glossaire. Les lignes de plus de cinq mots ou terminées par `.`, `!` ou `?` donnent un avertissement informatif sur leur possible ignorance par certains services, sans filtrage ni réécriture. Il ne faut pas rejeter les variantes entre parenthèses au motif qu’elles contiennent plusieurs mots.

## Persistance et requêtes

Le glossaire est une propriété des réglages de transcription, vide par défaut. Les anciens états restent lisibles et prennent cette valeur vide. La chaîne est figée dans chaque nouvelle tâche de transcription, comme le modèle et la langue, et persistée avec elle. Un changement ultérieur ne modifie pas les tâches en cours ni leurs reprises.

Le client multipart ajoute uniquement le champ standard `prompt` lorsque la chaîne n’est pas vide. Une chaîne vide n’ajoute aucun champ. Aucun champ propriétaire, aucune détection de dictee-server, aucune lecture ou modification du glossaire distant n’est introduit. Le glossaire n’entre jamais dans les requêtes de synthèse ni dans les journaux.

L’application conserve les erreurs HTTP sans masquer une erreur 400. La saisie locale évite les dépassements documentés du serveur. L’emploi du champ standard assure la compatibilité du glossaire avec les services qui acceptent `prompt` comme indice de vocabulaire ; il ne garantit pas l’acceptation de 20 000 caractères par tous les modèles ou fournisseurs, dont les limites peuvent être inférieures. Aucun nouvel essai automatique sans glossaire ne doit changer silencieusement la requête.

Le client actuel utilise déjà `background=true` et le format VTT. Cette spec n’affirme pas que ce transport est compatible avec tous les modèles OpenAI : le chantier ne doit pas ajouter de dépendance supplémentaire à dictee-server. Une refonte générale du transport sort du périmètre.

## Realtime et hors périmètre

La recherche dans `app/src` ne trouve aucun client Realtime ou WebSocket. Ajouter la dictée en direct constitue une autre fonctionnalité et ne fait pas partie de ce chantier. Le contrat à respecter lors de son ajout est `session.audio.input.transcription.prompt`, avec omission si vide et même glossaire local. Aucun client fictif ni infrastructure inutilisée n’est ajouté ici.

Le serveur, le glossaire distant, les secrets existants, le comportement de synthèse et les fonctions de récupération ne sont pas modifiés.

## Vérification prévue

Les tests sont écrits avant le code : édition et suppression par occurrence, collage multiligne, préservation du contenu, limites exactes et dépassements, caractères Unicode, champ vide, persistance des réglages et tâches anciennes, contexte figé lors de la préparation et des reprises, contenu multipart exact et absence de champ propriétaire ajouté.

Les tests Compose couvrent les rubriques distinctes, le retour et les accès directs, les bulles et leurs croix, la validation et les composants de formulaire. Les tests unitaires, la compilation debug et le lint sont exécutés. La vérification visuelle des thèmes et des grandes polices se fait sur émulateur ou appareil disponible ; toute absence de recette graphique réelle est signalée.
