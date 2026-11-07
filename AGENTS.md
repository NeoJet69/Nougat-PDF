# Consignes pour les agents (Codex, Claude Code…)

Lis d'abord [`PROJECT_CONTEXT.md`](PROJECT_CONTEXT.md) : le produit, l'architecture, la
compilation. Ce fichier-ci dit **comment travailler** sur le dépôt.

## Qui fait quoi

- **Claude Code** développe l'app, sur la branche `claude/stoic-hypatia-48mxwy`, et publie les
  releases quand Dylan le demande.
- **Codex** fait pour l'instant de la **revue et des essais** : il lit le code, lance les tests,
  cherche les bugs, les failles et les incohérences, et **note ce qu'il trouve** dans
  `docs/codex/REVUE.md`. Claude Code lit ce fichier et y répond.

### Règles pour Codex

1. Travaille sur ta propre branche, par exemple `codex/revue`, créée depuis la branche de
   Claude Code. Ne pousse jamais sur `main` (protégée) ni sur la branche de Claude Code.
2. N'écris que dans `docs/codex/`, sauf si Dylan te demande expressément de modifier du code.
   Une correction proposée va dans `REVUE.md` (extrait de code ou diff), pas dans les sources.
3. Une trouvaille = une entrée dans `REVUE.md`, au format indiqué en haut de ce fichier.
   Vérifie avant d'écrire : un bug doit avoir un scénario concret (entrée → mauvais résultat).
   Une impression vague ou une préférence de style n'est pas un bug.
4. Ne touche à aucun secret : clés de signature (`*.jks`), mots de passe, clés API. Ils ne sont
   jamais dans le dépôt ; s'il y en avait un, signale-le sans le recopier.
5. Écris en français.

## Compiler et tester

```bash
./gradlew -Pnougat.desktopOnly=true :shared:desktopTest
```
- `-Pnougat.desktopOnly=true` : sans SDK Android (la partie Android ne se compile alors pas).
- Les tests téléchargent d'abord les modèles OCR (environ 55 Mo, depuis registry.npmjs.org).
- Compilation complète (APK, Windows, captures) : GitHub Actions, `.github/workflows/build.yml`.

## Règles du code

- **Une seule logique** : ce qui ne dépend pas de la plateforme va dans `shared/src/commonMain`
  (ou `jvmShared` si c'est du Java/JVM commun à l'ordinateur et à Android). `desktopMain` et
  `androidMain` ne contiennent que l'adaptation à PDFBox, PdfBox-Android, Android, Windows.
- **Ne jamais modifier le fichier d'origine** de l'utilisateur : toute modification passe par
  `ViewerState.edit` / `PdfEngine.edit`, qui écrit une copie de travail (et permet « Annuler »).
- **Textes de l'interface** : jamais en dur dans le code. Chaque texte a une clé dans
  `shared/src/commonMain/kotlin/app/nougat/pdf/i18n/` : la clé s'ajoute dans **les 24 tables**
  (`Fr.kt`, `En.kt`, … `Miaou.kt`) et dans l'énumération `Str.kt`. Les `{0}`, `{1}` doivent être
  les mêmes dans toutes les langues ; les pluriels s'écrivent `forme1|forme2|…`. `I18nTest`
  vérifie tout ça. Pas de `$` dans les tables (chaînes Kotlin brutes).
- **Pas de tiret long (—) ni demi-long (–)** dans les textes visibles par l'utilisateur, dans le
  README et les notes de version (choix de Dylan). Les deux-points, virgules et parenthèses
  font l'affaire.
- Commentaires du code en français (souvent sans accents), noms de code en anglais.
- Les fonctions Android (ML Kit, Gemini Nano, scanner) ne s'essaient que sur un téléphone :
  le dire quand un changement n'a pas pu être vérifié.
- Une nouvelle bibliothèque fermée ou sous une autre licence va dans l'écran Licences
  (`legal/ThirdParty.kt`).

## Commits

- Messages en français : une ligne de titre, puis le pourquoi.
- Jamais de clé, de mot de passe ni de fichier de signature dans un commit.
- Pas de demande de fusion sans que Dylan l'ait demandé.
