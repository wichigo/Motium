Exécute une vérification complète du projet Motium :

1. **Clean & Build**
   ```bash
   ./gradlew clean assembleDebug
   ```

2. **Lint**
   ```bash
   ./gradlew lintDebug
   ```

3. **Unit Tests**
   ```bash
   ./gradlew testDebugUnitTest
   ```

4. **Résumé**
   - Liste les erreurs de compilation s'il y en a
   - Liste les warnings lint importants (severity: Error ou Warning)
   - Liste les tests échoués s'il y en a
   - Donne un statut global ✅ ou ❌

Si tout passe, confirme que le projet est prêt pour commit.
