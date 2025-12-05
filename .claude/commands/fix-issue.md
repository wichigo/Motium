Analyse et corrige l'issue GitHub : $ARGUMENTS

## Étapes :

1. **Récupérer les détails de l'issue**
   ```bash
   gh issue view $ARGUMENTS
   ```

2. **Comprendre le problème**
   - Identifier le type : bug, feature, refactor
   - Localiser les fichiers concernés
   - Comprendre la cause racine

3. **Rechercher dans le codebase**
   - Trouver les fichiers pertinents
   - Analyser le code existant
   - Identifier les dépendances

4. **Implémenter la correction**
   - Faire les modifications nécessaires
   - Suivre les conventions du projet (voir CLAUDE.md)
   - Ajouter des commentaires si nécessaire

5. **Tester**
   ```bash
   ./gradlew testDebugUnitTest
   ```

6. **Vérifier le lint**
   ```bash
   ./gradlew lintDebug
   ```

7. **Créer le commit**
   - Format : `fix(scope): description (fixes #ISSUE_NUMBER)`
   - Exemple : `fix(auth): handle token refresh failure (fixes #42)`

8. **Créer la PR**
   ```bash
   gh pr create --title "Fix: [description]" --body "Fixes #$ARGUMENTS"
   ```

## À la fin, confirmer :
- ✅ Tests passent
- ✅ Lint OK
- ✅ Commit créé
- ✅ PR créée (ou prête à push)
