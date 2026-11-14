# DIC-25 : langue automatique par défaut

La demande précise le comportement et les vérifications. Le changement reste limité aux valeurs par défaut, à leur aide et aux tests ; aucune migration des langues enregistrées ne sera faite. Le champ multipart reste explicite. Une langue existante, dont `fr`, reste inchangée.

## Plan

1. Ajouter les tests des deux valeurs par défaut, de la persistance de `fr`, du contexte `auto` et du multipart ; observer les échecs avant le changement.
2. Remplacer les deux valeurs par défaut et préciser l'aide dans les réglages ; mettre à jour la spécification.
3. Exécuter `testDebugUnitTest`, `assembleDebug` et `lint`, puis relire le diff.
4. Contrôler le déploiement du serveur avant toute recette sur téléphone. La recette réelle reste distincte des tests unitaires.

Le nettoyage du worktree DIC-19 attend la confirmation de suppression après contrôle de son intégration dans main et de son état propre. Aucun push, fusion ni release ne fait partie de ce travail.
