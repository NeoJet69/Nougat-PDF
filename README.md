# Nougat PDF 🐱

Un lecteur et éditeur de PDF **gratuit**, **hors ligne**, pour **Windows** et **Android**.

## Modifier un PDF

Bouton **Modifier** en haut à droite, puis choisir un outil dans la barre du bas :

- **Éditer** : modifier le texte déjà présent dans le PDF. Les lignes modifiables sont
  encadrées ; toucher une ligne ouvre un champ posé dessus (Entrée pour valider, Échap pour annuler).
  Le texte est réécrit au même endroit, dans la même taille et la même couleur, avec **la police
  d'origine** quand elle contient les lettres tapées. Sinon : la même police si elle est installée
  sur l'ordinateur (Windows), ou la police standard la plus proche (Helvetica, Times, Courier,
  en gras / italique selon l'original). Le reste de la page ne bouge pas.
- **Stylo** et **Surligneur** (plusieurs couleurs)
- **Texte** : toucher la page pour écrire ; toucher un texte ajouté pour le modifier
- **Masquer** : tracer un rectangle blanc pour cacher une zone (utile pour le texte qu'« Éditer »
  ne peut pas modifier : texte dans une image scannée, dans un formulaire...)
- **Signature** : la dessiner une fois, puis toucher la page pour la poser
- **Gomme** : toucher une annotation pour l'effacer
- **Annuler** (Ctrl+Z) : annule la dernière annotation ou la dernière opération

**Pages** (icône grille) : pivoter, déplacer, supprimer, extraire dans un nouveau fichier,
ajouter une page blanche ou un autre PDF à la fin.

Menu **⋮** : enregistrer, enregistrer sous, imprimer, partager (Android),
protéger par mot de passe ou retirer le mot de passe.

Tout est gravé dans le PDF à l'enregistrement : il s'ouvre ensuite dans n'importe quel lecteur.

## Assistant Claude (intelligence artificielle)

Bouton ✨ en haut : Claude lit le document ouvert et agit dessus. Exemples : « résume ce document »,
« corrige les fautes de la page 2 », « traduis cette page en anglais », « remplace Dupont par Martin
partout », « surligne les montants », « supprime la page 3 ». Il modifie le texte avec le même moteur
que l'outil Éditer (même police, même place), lit les documents scannés en regardant l'image de la
page, et tout s'annule avec le bouton Annuler.

**Chacun utilise sa propre clé API Anthropic** : l'auteur de l'app ne paie rien et ne voit rien.
1. Créer un compte sur [console.anthropic.com](https://console.anthropic.com)
2. Ajouter un moyen de paiement (Billing) : la consommation est facturée à l'usage
   (c'est séparé d'un abonnement Claude.ai, qui ne peut pas être utilisé par une autre app)
3. API Keys → Create Key, puis coller la clé dans les réglages de l'assistant (⚙)

La clé reste sur l'appareil. Modèles proposés : Claude Opus 5.5 (par défaut), Sonnet 5.5, Haiku 5.5.
L'assistant est la seule partie de Nougat qui utilise Internet.

## Lire

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
| Ctrl + S / Ctrl + Maj + S | Enregistrer / Enregistrer sous |
| Ctrl + E | Mode modification |
| Ctrl + Z | Annuler |
| Ctrl + P | Imprimer |
| Ctrl + F | Rechercher (Entrée = suivant, Maj + Entrée = précédent, Échap = fermer) |
| F3 | Résultat suivant |
| Ctrl + + / Ctrl + − / Ctrl + 0 | Zoomer / dézoomer / 100 % |
| Page suivante / précédente | Changer de page |
| Ctrl + Début / Ctrl + Fin | Première / dernière page |
| Ctrl + W | Fermer le document |

## Prochaines idées

- Créer un PDF à partir de photos, compresser
- Remplir les formulaires PDF, OCR
- Déplacer / redimensionner une annotation après l'avoir posée

## Installer

Chaque envoi sur GitHub construit l'app automatiquement (onglet **Actions** → dernier passage → **Artifacts**) :

- `nougat-pdf-windows` : l'installateur `.msi` (ou `.exe`) pour Windows
- `nougat-pdf-android` : le fichier `.apk` à installer sur le téléphone
  (autoriser « Installer des applis inconnues » quand Android le demande)

## Clé de signature Android (une seule fois)

Sans clé fixe, chaque APK est signé avec une clé de test différente : il faut désinstaller
l'ancienne version avant d'installer la nouvelle. Avec la clé, les mises à jour s'installent
par-dessus. Sur le Mac, dans le Terminal :

1. Créer la clé (remplacer `MOT_DE_PASSE` par un vrai mot de passe, sans espace) :
   ```bash
   keytool -genkeypair -keystore nougat.jks -alias nougat -keyalg RSA -keysize 4096 -validity 36500 -storepass MOT_DE_PASSE -keypass MOT_DE_PASSE -dname "CN=Nougat PDF"
   ```
   (si `keytool` est introuvable : `"/Applications/Android Studio.app/Contents/jbr/Contents/Home/bin/keytool"` à la place de `keytool`)
2. Copier la clé encodée dans le presse-papiers :
   ```bash
   base64 -i nougat.jks | pbcopy
   ```
3. Sur GitHub : dépôt **Nougat-PDF** → **Settings** → **Secrets and variables** → **Actions** →
   **New repository secret**, quatre fois :

   | Nom | Valeur |
   |---|---|
   | `NOUGAT_KEYSTORE` | coller (Cmd+V) le contenu copié à l'étape 2 |
   | `NOUGAT_KEYSTORE_PASSWORD` | le mot de passe |
   | `NOUGAT_KEY_ALIAS` | `nougat` |
   | `NOUGAT_KEY_PASSWORD` | le même mot de passe |

4. Garder `nougat.jks` et le mot de passe en lieu sûr (gestionnaire de mots de passe, clé USB) :
   sans eux, impossible de publier une mise à jour qui s'installe par-dessus.

La compilation suivante signe l'APK avec cette clé. Désinstaller une dernière fois l'ancienne
version (signée avec la clé de test), puis les mises à jour s'installeront normalement.

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
