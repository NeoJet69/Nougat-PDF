# Revue de Codex

Codex écrit ici ce qu'il trouve dans Nougat PDF ; Claude Code répond dans la colonne « Suite ».
Consignes : voir [`AGENTS.md`](../../AGENTS.md).

## Format d'une entrée

```
### N. Titre court
- Gravité : bloquant | important | mineur | idée
- Où : chemin/du/fichier.kt:ligne
- Problème : ce qui ne va pas, avec un scénario concret (entrée → résultat obtenu → attendu).
- Proposition : comment corriger (extrait de code ou diff si utile).
- Suite (Claude Code) : corrigé dans <commit> | pas d'accord, parce que… | à voir avec Dylan
```

## Entrées

_(rien pour l'instant)_

## Contrôle exécuté

- 8 novembre 2026, branche `codex/revue`, basée sur `claude/stoic-hypatia-48mxwy` au commit `ef1e167`.
- Commande : `./gradlew -Pnougat.desktopOnly=true :shared:desktopTest` (syntaxe PowerShell adaptée).
- Résultat : 116 tests exécutés, 0 échec, 0 ignoré. La compilation de la cible ordinateur a aussi réussi.
- Ce contrôle ne remplace pas les essais sur téléphone Android, qui restent nécessaires pour le scanner, Gemini Nano, la synthèse vocale et les fonctions dépendantes de ML Kit.
