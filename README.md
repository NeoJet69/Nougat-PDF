# Nougat PDF 🐱

Un lecteur (et bientôt éditeur) de PDF **gratuit**, **hors ligne**, pour **Windows** et **Android**.

## Étape 1 : le lecteur (fait)

- Ouvrir un PDF : bouton « Ouvrir », glisser-déposer (Windows), « Ouvrir avec » (Windows et Android)
- Faire défiler les pages, zoomer (boutons, Ctrl + molette, pincer avec les doigts)
- Rechercher du texte, sans tenir compte des majuscules ni des accents, avec surlignage
- Aller directement à une page (toucher l'indicateur « Page x / n »)
- PDF protégés par mot de passe
- Thème clair / sombre selon le système

### Raccourcis clavier (Windows)

| Touche | Action |
|---|---|
| Ctrl + O | Ouvrir un PDF |
| Ctrl + F | Rechercher (Entrée = suivant, Maj + Entrée = précédent, Échap = fermer) |
| F3 | Résultat suivant |
| Ctrl + + / Ctrl + − / Ctrl + 0 | Zoomer / dézoomer / 100 % |
| Page suivante / précédente | Changer de page |
| Ctrl + Début / Ctrl + Fin | Première / dernière page |
| Ctrl + W | Fermer le document |

## Prochaines étapes

2. Organiser les pages : fusionner, découper, réordonner, pivoter, supprimer
3. Annoter et signer
4. Créer des PDF à partir de photos, compresser, protéger par mot de passe
5. Remplir les formulaires, OCR

## Installer

Chaque envoi sur GitHub construit l'app automatiquement (onglet **Actions** → dernier passage → **Artifacts**) :

- `nougat-pdf-windows` : l'installateur `.msi` (ou `.exe`) pour Windows
- `nougat-pdf-android` : le fichier `.apk` à installer sur le téléphone
  (autoriser « Installer des applis inconnues » quand Android le demande)

## Pour les développeurs

Kotlin + Compose Multiplatform. Le moteur PDF est [Apache PDFBox](https://pdfbox.apache.org/) sur
ordinateur, `PdfRenderer` d'Android + [PdfBox-Android](https://github.com/TomRoush/PdfBox-Android)
sur téléphone. Tout est sous licence Apache 2.0.

```
shared/       le cœur commun : interface, recherche, moteurs PDF
  commonMain    code partagé Windows + Android
  desktopMain   moteur PDFBox (Windows)
  androidMain   moteur PdfRenderer + PdfBox-Android
desktopApp/   l'app Windows (fenêtre, raccourcis, installateur)
androidApp/   l'app Android
```

Commandes utiles :

```bash
./gradlew :desktopApp:run                 # lancer la version ordinateur
./gradlew :shared:desktopTest             # tests
./gradlew :androidApp:assembleRelease     # APK Android
./gradlew :desktopApp:packageMsi          # installateur Windows (à lancer sous Windows)
```

Sans SDK Android, ajouter `-Pnougat.desktopOnly=true` pour ne construire que la version ordinateur.
