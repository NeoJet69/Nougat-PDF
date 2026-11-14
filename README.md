# Nougat PDF 🐱

Un lecteur et éditeur de PDF **gratuit** et **sans pub**, pour **Windows** et **Android**.

## Modifier un PDF

Le **dock** en bas de l'écran choisit le mode : **Lire**, **Éditer**, **Annoter**, **Signer**,
**Pages** et **Claude**.

- **Éditer** : modifier le texte déjà présent dans le PDF. Les lignes modifiables sont
  encadrées ; toucher une ligne ouvre un champ posé dessus sur ordinateur (Entrée pour valider,
  Échap pour annuler), une fenêtre sur téléphone (le bouton OK du clavier valide aussi).
  Le texte est réécrit au même endroit, dans la même taille et la même couleur, avec **la police
  d'origine** quand elle contient les lettres tapées. Sinon : la même police si elle est installée
  sur l'ordinateur (Windows), ou la police standard la plus proche (Helvetica, Times, Courier,
  en gras / italique selon l'original). Le reste de la page ne bouge pas.
- **Formulaires** : en mode Lire, les champs à remplir d'un formulaire PDF sont teintés. Toucher
  un champ pour l'écrire (Tab ou « Suivant » passe au champ suivant), cocher une case, choisir un
  bouton radio ou une valeur dans une liste. Les valeurs sont enregistrées dans le formulaire
  lui-même : il reste modifiable dans les autres lecteurs.
- **Annoter** : **stylo** et **surligneur** (plusieurs couleurs), **texte** (toucher la page pour
  écrire, toucher un texte ajouté pour le modifier), **masquer** (rectangle blanc pour cacher une
  zone), **gomme** (toucher une annotation pour l'effacer)
- **Signer** : dessiner sa signature une fois, puis toucher la page pour la poser
- **Annuler** (Ctrl+Z) : annule la dernière annotation ou la dernière opération
- Zoom : pincer avec deux doigts (téléphone), Ctrl + molette ou Ctrl +/− (ordinateur)

## Reconnaître le texte d'un scan (OCR), sur Windows et Android

Un PDF scanné ou photographié n'est qu'une image : on ne peut ni y chercher un mot, ni le copier,
ni le modifier. Nougat le détecte à l'ouverture et propose **Reconnaître le texte** (aussi dans
le menu ⋮). Tout se fait **sur l'appareil** (moteur Tesseract 5, modèles les plus précis,
16 langues : choisir celles du document, plusieurs à la fois si besoin).

- **Invisible** : le texte reconnu est posé mot par mot exactement sur l'image, en texte invisible
  (comme Acrobat). La page ne change pas à l'œil, mais on peut **chercher**, **sélectionner**,
  **copier** et demander à **Claude** de lire ou résumer le scan.
- **Pages redressées** : une page couchée ou à l'envers est remise droite automatiquement ; les
  pages un peu penchées sont suivies ligne par ligne.
- **Photos** : l'éclairage est corrigé avant la lecture (ombre de la main, coin sombre, papier
  jauni).
- **Rapide** : plusieurs pages reconnues en parallèle, avec une barre de progression et un
  bouton **Arrêter** (les pages déjà faites sont gardées).
- **Sans doublon** : les pages qui contiennent déjà du texte sont laissées telles quelles ;
  « Refaire » remplace l'ancienne reconnaissance (autre langue…) au lieu d'en ajouter une.
- **Modifier un scan** : après la reconnaissance, le mode **Éditer** fonctionne sur les
  lignes scannées. Nougat efface la ligne de l'image en reconstituant le papier (sa teinte, son
  grain), puis écrit le nouveau texte **dans la police la plus ressemblante** (comparée lettre à
  lettre avec l'image : Arial, Times, Calibri, Georgia, Courier… en gras ou italique), à la même
  taille, la même couleur d'encre et la même inclinaison.

**Sur Android** : le français et l'anglais sont livrés dans l'app ; les autres langues (marquées ↓)
se téléchargent une seule fois (2 à 6 Mo), ensuite tout fonctionne sans Internet. Sur les
téléphones compatibles avec **Gemini Nano**, l'IA de Google intégrée au téléphone (Pixel 9 et
suivants, Galaxy S26…), l'option **Relecture par Gemini Nano** fait relire les lignes où Tesseract
doute : Gemini Nano voit l'image de la ligne et corrige la lecture (taches, plis, photo floue), et
sait même lire une **écriture manuscrite**. Tesseract garde la place exacte de chaque mot ; une
correction n'est gardée que si elle est vraisemblable. Tout reste sur le téléphone.

## Traduire un document

Bouton **Traduire** (barre du bas, et menu ⋮) : Nougat traduit le document **en gardant sa mise en
page**, comme DeepL ou Google Traduction pour les documents. Le fichier d'origine n'est jamais
modifié.

- **Lire traduit** : la traduction s'affiche par-dessus chaque page, à la place du texte, dans le
  même cadre, la même couleur d'encre et un style proche (un peu plus petite si elle est plus
  longue). L'interrupteur **Original / Traduction** permet de comparer, et toucher un paragraphe
  montre son original. La page affichée est traduite en premier.
- **Par paragraphe** : les lignes sont d'abord regroupées en paragraphes (un mot coupé en fin de
  ligne est recollé), pour traduire des phrases entières. Les adresses et les lignes « Date : »
  restent ligne par ligne ; les montants, dates et numéros ne bougent pas.
- **Enregistrer la traduction** : une copie traduite (`Facture_DE.pdf`), où le texte d'origine
  est retiré et la traduction écrite à sa place ; elle reste cherchable et copiable. Les scans se
  traduisent aussi, après la reconnaissance du texte.
- **31 langues**, dont l'arabe (écrit de droite à gauche, lettres liées), le chinois, le japonais
  et le coréen.

**Sur Android** : la traduction se fait **sur le téléphone** (ML Kit de Google), gratuitement et
sans envoyer le texte ; chaque langue se télécharge une seule fois (environ 30 Mo). Sur les
téléphones compatibles, **Gemini Nano** relit la traduction pour la rendre plus naturelle (une
relecture qui change un chiffre est refusée). On peut aussi choisir **Claude** pour la meilleure
qualité. **Sur Windows** : la traduction passe par **Claude**, avec votre clé API Anthropic (le
texte est alors envoyé à Anthropic).

## Cacher des informations pour de bon

Dans beaucoup de lecteurs PDF, un rectangle noir ne fait que cacher : Ctrl + A puis copier-coller
fait réapparaître le texte. Dans Nougat, ce qui est caché est vraiment retiré du fichier.

- **Effacer** (mode Éditer) : Ctrl + clic pour choisir plusieurs lignes, Maj + clic pour toute une
  zone (une colonne de prix), appui long sur téléphone, puis Suppr ou « Effacer ».
- **Masquer** (mode Annoter) : le rectangle retire aussi le texte et les pixels qu'il couvre.
- **Censurer** (menu ⋮) : par mots choisis, ou censure intelligente qui repère les noms de
  personnes et d'entreprises, n° AVS et pièces d'identité, dates de naissance, adresses,
  téléphones, e-mails, n° de compte et IBAN, n° de police d'assurance et de dossier médical
  (l'IA, Claude ou Gemini Nano, peut aider pour les noms). On vérifie les trouvailles sur les
  pages (Tout, Aucun, toucher pour garder ou retirer), puis des barres noires les remplacent.
- **Finaliser pour envoyer** (menu ⋮) : une copie dont chaque page est une image, sans texte,
  calque, annotation, formulaire ni métadonnée cachés ; en option, la reconnaissance du texte la
  rend de nouveau cherchable.

**Outils de pages** : **Compresser** le document (trois niveaux, taille avant et après),
**Numéros de page et date** (format, position, premier numéro, sans la première page), et dans
l'écran Pages, **Rogner** les marges blanches et **Redresser** un scan un peu penché.

**Pages** : pivoter, déplacer, supprimer, extraire dans un nouveau fichier, ajouter une page
blanche, un autre PDF ou des images à la fin.

**Créer un PDF** (accueil) : **Photos → PDF** (une page par photo ou image, remise droite et
centrée sur une page A4), **Document vierge** (une page A4 à remplir avec Texte, Signature...)
ou **Markdown → PDF** (un fichier `.md` mis en page : titres, listes, citations, code, tableaux).

**QR-facture** (menu ⋮) : Nougat cherche le code QR d'une facture suisse et affiche le montant,
le compte, le bénéficiaire et la référence, avec un bouton **Copier** pour chacun (à coller dans
l'e-banking) ; un avertissement s'affiche si l'IBAN ou la référence ne passe pas le contrôle.
**Créer une QR-facture** ajoute la section paiement (récépissé et code QR, 210 × 105 mm, en
français, allemand, italien ou anglais) sur une nouvelle page ou en bas de la dernière page d'un
devis ou d'une facture. Vos coordonnées restent sur l'appareil pour la fois suivante, et le
montant total trouvé dans le document est proposé.

**Déplacer des lignes** (mode Éditer) : après avoir choisi des lignes (Ctrl + clic, Maj + clic,
appui long), on les glisse à la souris ou au doigt, ou on les règle avec les flèches ; A− et A+
changent leur taille ; on peut glisser dans toutes les directions, y compris en diagonale. Le
texte ne se chevauche jamais : si la nouvelle place touche un autre texte, l'aperçu devient rouge et
Nougat refuse. **Placer ici** les écrit à leur nouvelle place : l'ancienne place est
effacée (pixels du scan repeints), l'encre est recollée et le texte reste cherchable. Sur Windows,
les flèches du clavier (Maj pour aller plus loin), + et - et Entrée font la même chose. Pratique
après la reconnaissance du texte d'un scan.

**Scanner sur Windows** (accueil ou menu ⋮) : avec le scanner ou l'imprimante multifonction branchés
à l'ordinateur, par le service de numérisation de Windows (WIA). Une page à la fois ; Nougat
demande s'il faut scanner une autre page, puis toutes les pages forment un PDF.

**Depuis le téléphone** (accueil, ou menu ⋮ « Échanger avec le téléphone », sur Windows) :
Nougat affiche un code QR. On le scanne avec l'appareil photo du téléphone, une page s'ouvre dans
le navigateur, on choisit des PDF ou des photos et ils arrivent sur l'ordinateur (dossier
Téléchargements). Le téléphone peut aussi télécharger le document ouvert sur l'ordinateur. Tout
passe par le Wi-Fi de la maison, rien par Internet, et ça marche avec n'importe quel téléphone
(Android ou iPhone, sans installer d'appli). La page n'existe que pendant que la fenêtre est
ouverte, à une adresse secrète. La première fois, Windows demande d'autoriser le réseau : choisir
« réseaux privés ».

**Remplir un formulaire papier** (menu ⋮) : sur un formulaire scanné (texte reconnu) ou un PDF
sans champs, Nougat repère les champs de la page (un libellé suivi de « ____ », de « …… » ou de
deux-points) et les remplit avec votre profil : nom, prénom, adresse, NPA et localité, date de
naissance, téléphone, e-mail, n° AVS, IBAN, « Lieu et date ». Vous corrigez, puis le texte est
écrit sur la page. Le profil reste sur l'appareil, pour le formulaire suivant.

**Lire à voix haute** (menu ⋮) : la voix de l'appareil lit le document paragraphe par paragraphe
depuis la page affichée, en surlignant ce qu'elle lit (pause, paragraphe précédent ou suivant,
trois vitesses). Quand la traduction est affichée, c'est la traduction qui est lue, dans sa
langue. Sur Android, la synthèse vocale du téléphone ; sur Windows, les voix installées
(Paramètres, Heure et langue, Voix).

**Ce que je dois faire** (menu ⋮) : le résumé d'une lettre en deux ou trois phrases simples et
la liste de ce qu'il faut faire (payer, répondre, envoyer un papier, aller à un rendez-vous), avec
les dates. Chaque date s'ajoute au calendrier d'un toucher (rappel la veille). Sur Android,
Gemini Nano le fait sur le téléphone ; sur Windows, avec une clé Claude. Sans IA, Nougat liste les
dates et les délais trouvés dans le texte.

**Métré sur plan** (menu ⋮) : choisir l'échelle du plan (1:50, 1:100..., ou celle écrite sur le
plan, reprise toute seule), ou l'étalonner en traçant une cote connue (pour un scan ou un plan
imprimé réduit). Puis toucher le plan point par point : **Longueur** (ligne brisée) ou
**Surface** (toucher le premier point pour fermer, avec le périmètre). Les totaux s'affichent,
**Copier** donne la liste des mesures, **Ajouter au plan** les dessine sur le document.

**Exporter en Markdown** (menu ⋮) : le texte du document devient un fichier `.md`, avec les
titres reconnus à la taille du texte, les listes et les tableaux. Pour un scan, lancer d'abord
la reconnaissance du texte.

Menu **⋮** : enregistrer, enregistrer sous, imprimer, partager (Android), protéger par mot de
passe ou retirer le mot de passe, thème clair / sombre / automatique.

Tout est gravé dans le PDF à l'enregistrement : il s'ouvre ensuite dans n'importe quel lecteur.

**Enregistrer** : la première fois, Nougat demande **Remplacer l'original** ou **Enregistrer une
copie…** (proposée sous le nom `Nom_miaou.pdf`, à l'endroit de votre choix). Ensuite, les
enregistrements vont dans le fichier choisi sans redemander.

## 24 langues

Bouton 🌐 de l'accueil, ou menu ⋮ → Langue. Par défaut, la langue de l'appareil.

- **16 langues** : français, English, Deutsch, italiano, español, português, Nederlands, polski,
  Türkçe, русский, українська, العربية (de droite à gauche), 中文, svenska, ελληνικά, română
- **8 dialectes** : Schwiizerdütsch, rumantsch, français québécois, Elsässisch (alsacien),
  occitan, brezhoneg (breton), corsu (corse) et **Miaou 🐱**, la langue de Nougat

Les dialectes ont été écrits avec soin mais sans relecture par des locuteurs natifs : les
corrections sont les bienvenues (fichiers `shared/src/commonMain/kotlin/app/nougat/pdf/i18n/`).

## Assistant Claude (intelligence artificielle)

Bouton ✨ **Claude** du dock : Claude lit le document ouvert et agit dessus. Exemples : « résume ce document »,
« corrige les fautes de la page 2 », « traduis cette page en anglais », « remplace Dupont par Martin
partout », « surligne les montants », « supprime la page 3 », « remplis ce formulaire avec mes coordonnées ». Il modifie le texte avec le même moteur
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

- Traduire sur Windows sans Internet
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

Le contexte du projet est dans [`PROJECT_CONTEXT.md`](PROJECT_CONTEXT.md), les consignes pour les
agents de code (Codex, Claude Code) dans [`AGENTS.md`](AGENTS.md), la liste des choses à faire dans
[`TODO.md`](TODO.md).

Kotlin + Compose Multiplatform. Le moteur PDF est [Apache PDFBox](https://pdfbox.apache.org/) sur
ordinateur, `PdfRenderer` d'Android + [PdfBox-Android](https://github.com/TomRoush/PdfBox-Android)
sur téléphone. L'OCR utilise [Tesseract](https://github.com/tesseract-ocr/tesseract) 5 (via
[JavaCPP Presets](https://github.com/bytedeco/javacpp-presets) sur ordinateur,
[Tesseract4Android](https://github.com/adaptech-cz/Tesseract4Android) sur Android) avec les modèles
`tessdata_best`, téléchargés à la construction (tâche `downloadTessdata`). La logique de l'OCR est
commune aux deux (`shared/src/jvmShared/.../engine/OcrCore.kt`). La relecture sur Android passe par
l'[API Prompt de ML Kit](https://developer.android.com/ai/gemini-nano) (Gemini Nano). La traduction
regroupe les lignes en paragraphes et calcule la mise en page dans le code commun
(`shared/src/commonMain/.../engine/Translation.kt`) ; elle traduit avec ML Kit Translation sur
Android, ou avec Claude ; la copie traduite est écrite par PDFBox sur Windows et dessinée par
Android lui-même sur téléphone (toutes les écritures).

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
./gradlew :shared:desktopTest             # tests (télécharge d'abord les modèles OCR, ~55 Mo)
./gradlew :androidApp:assembleRelease     # APK Android
./gradlew :desktopApp:packageMsi          # installateur Windows (à lancer sous Windows)
```

Sans SDK Android, ajouter `-Pnougat.desktopOnly=true` pour ne construire que la version ordinateur.

## Licence

Nougat PDF est un logiciel libre sous **licence MIT** (voir le fichier [LICENSE](LICENSE)) :
chacun peut l'utiliser, le copier, le modifier et le redistribuer, y compris dans un autre
projet, à condition de garder la mention de copyright et le texte de la licence.

Les bibliothèques tierces livrées avec l'app (PDFBox, Tesseract, Compose…) gardent **leurs
propres licences** (Apache 2.0, BSD, MIT…). La liste complète, avec leurs avis obligatoires
(fichiers NOTICE), est dans l'app : menu ⋮ → **À propos** → **Licences**.

## Auteurs

- **NeoJet** : idée, direction et tests
- **Nougat** 🐱 : mascotte et testeur en chef
- **Caramel** ✨ : code et design (Claude, une IA d'Anthropic)

Partenaire : [ArtisanGo](https://artisango.ch).
[Conditions d'utilisation](https://artisango.ch/nougatpdf/terms-conditions) ·
[Confidentialité](https://artisango.ch/nougatpdf/privacy-policy) ·
[Mentions légales](https://artisango.ch/nougatpdf/impressum)
