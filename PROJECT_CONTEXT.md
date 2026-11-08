# Nougat PDF : contexte du projet

Ce fichier décrit le projet pour tout développeur ou agent (Claude Code, Codex…) qui arrive dessus.
Les règles de travail sont dans [`AGENTS.md`](AGENTS.md). La liste des choses à faire est dans
[`TODO.md`](TODO.md).

## Le produit

**Nougat PDF** est un lecteur et éditeur de PDF **gratuit, sans publicité, sans compte**, pour
**Windows** et **Android** (iOS plus tard). Les documents restent sur l'appareil. Logiciel libre
sous **licence MIT** (`LICENSE`), en partenariat avec **ArtisanGo** (artisango.ch). Auteur : Dylan
(NeoJet / NeoJet69). Mascotte : Nougat, un chat.

Ce que fait l'app aujourd'hui :

- **Modifier le texte** d'un PDF au même endroit, dans la même police (mode Éditer).
- **Effacer vraiment** des lignes choisies (Ctrl + clic, Maj + clic, appui long) : le texte est
  retiré du PDF ; sur un scan, les pixels de l'image sont repeints.
- **Annoter** (stylo, surligneur, texte, rectangle « Masquer » qui retire vraiment ce qu'il couvre,
  gomme, couleur libre), **signer**,
  **remplir les formulaires** PDF.
- **Censurer** : par mots ou censure intelligente (noms, adresses, n° AVS, IBAN, téléphones…),
  vérification sur les pages puis barres noires et texte retiré.
- **Finaliser pour envoyer** : copie dont chaque page est une image (plus rien de caché), avec OCR
  facultatif pour rester cherchable.
- **Compresser**, **numéros de page et date**, **rogner** les marges, **redresser** un scan penché.
- **Organiser les pages** : pivoter, déplacer, supprimer, extraire, ajouter une page, un PDF, des
  images. **Créer** un PDF depuis des photos ou un document vierge.
- **Scanner** un document avec l'appareil photo (Android, ML Kit Document Scanner).
- **Reconnaître le texte des scans (OCR)** avec Tesseract 5 sur l'appareil, en 16 langues ; le texte
  est posé, invisible, sur l'image. Sur Android, **Gemini Nano** relit les lignes douteuses.
- **Modifier une ligne scannée** : l'image est effacée (papier reconstitué) et le texte réécrit
  dans la police la plus ressemblante.
- **Traduire** un document en gardant sa mise en page (affichage par-dessus les pages, ou copie
  traduite). Android : ML Kit Translation sur le téléphone (+ Gemini Nano). Windows : Claude.
- **Assistant Claude** (facultatif, avec la clé API Anthropic de l'utilisateur).
- **24 langues d'interface** : 16 langues et 8 dialectes (suisse allemand, romanche, québécois,
  alsacien, occitan, breton, corse… et le « Miaou »), dont l'arabe de droite à gauche.

## Technique

- **Kotlin 2.4**, **Compose Multiplatform 1.12** (interface commune), **Gradle 9.5**, **JDK 21**,
  Android Gradle Plugin 9.3 (`compileSdk 37`, `targetSdk 36`, `minSdk 26`).
- Moteur PDF : **Apache PDFBox 3** sur ordinateur ; **PdfRenderer** d'Android (affichage) +
  **PdfBox-Android 2** (modification) sur téléphone.
- OCR : **Tesseract 5** (JavaCPP sur ordinateur, Tesseract4Android sur Android), modèles
  `tessdata_best` entiers téléchargés à la construction (tâche `downloadTessdata`).
- IA : **Anthropic Java SDK** (Claude, assistant et traduction) ; **ML Kit GenAI Prompt API**
  (Gemini Nano), **ML Kit Translation** et **Language ID** sur Android.

### Organisation du code

```
shared/                     le cœur commun (bibliothèque Kotlin Multiplatform)
  src/commonMain/           code commun Windows + Android
    engine/                 modèle PDF indépendant de la plateforme
      PdfEngine.kt            interfaces PdfEngine, PdfDocument, PageText, NormRect
      PdfEditing.kt           EditableDoc (modifier un document), marques d'annotation, burn()
      TextEditing.kt          modification du texte : groupTextBlocks, TextEditPlanner
      Translation.kt          paragraphes, mise en page des traductions, couleurs, arabe lié
      Ocr.kt, Forms.kt, TextSearch.kt
    viewer/                 état de l'écran (pas d'interface ici)
      ViewerState.kt          état global : document ouvert, modifications, enregistrement, OCR
      DocumentSession.kt      un document ouvert : pages, zoom, recherche, sélection
      TranslationState.kt     la traduction affichée, page par page
      EditTools.kt, PlatformFiles.kt
    ui/                     écrans Compose (ViewerScreen, HomeScreen, EditOverlay, dialogues…)
    i18n/                   textes de l'app : une table par langue (Fr.kt, En.kt…) + Str.kt
    assistant/              outils de l'assistant Claude (NougatToolbox) et interfaces
    legal/                  écran Licences (composants tiers et textes de licence)
  src/jvmShared/            code commun JVM (ordinateur + Android) : client Claude, cœur de l'OCR,
                            réécriture des scans, ordre bidi, nettoyage d'images
  src/desktopMain/          implémentation PDFBox (Windows)
  src/androidMain/          implémentation Android (PdfRenderer, PdfBox-Android, ML Kit)
  src/commonTest/           tests sans plateforme
  src/desktopTest/          tests sur ordinateur (PDF réels, OCR, captures d'écran)
desktopApp/                 application Windows (Main.kt : fenêtre, raccourcis clavier)
androidApp/                 application Android (MainActivity, fichiers, scanner)
captures/                   captures d'écran générées par les tests (CaptureTest)
.github/workflows/build.yml compilation, tests, captures, release
```

Principes : la logique est écrite **une seule fois** dans `commonMain` (ou `jvmShared`) ; chaque
plateforme ne fournit que ce qui dépend d'elle (rendu, PDFBox, fichiers). Le fichier d'origine
n'est **jamais** modifié : chaque modification produit une copie de travail (`ViewerState.edit`),
ce qui permet « Annuler ».

### Modifier le texte d'un PDF (le cœur)

1. La plateforme analyse la page : chaque lettre visible est reliée à l'opérateur du flux de
   contenu qui la dessine (`PageTextAnalysis`, `CaptureStripper` dans `*TextEditing.kt`).
2. Les lettres sont regroupées en lignes (`groupTextBlocks`).
3. `TextEditPlanner` calcule les nouvelles instructions (texte retiré ou remplacé, décalages pour
   que le reste ne bouge pas) ; `rewriteContent` réécrit le flux.
4. Texte invisible d'OCR : la ligne est réécrite sur l'image (`ScanEditCore.kt`).

## Compilation, tests, release

- Une machine sans SDK Android peut construire la partie ordinateur :
  `./gradlew -Pnougat.desktopOnly=true :shared:desktopTest`.
- Tests : `./gradlew :shared:desktopTest` (télécharge d'abord les modèles OCR, environ 55 Mo).
  `I18nTest` vérifie que les 24 langues ont tous les textes. `CaptureTest` produit les captures.
- APK : `./gradlew :androidApp:assembleRelease` ; AAB Google Play :
  `./gradlew :androidApp:bundleRelease` ; installateur Windows :
  `./gradlew -Pnougat.desktopOnly=true :desktopApp:packageMsi :desktopApp:packageExe`.
- **GitHub Actions** (`build.yml`) à chaque poussée : APK, tests, captures (recopiées dans
  `captures/` sur les branches), installateur Windows, et l'AAB si la clé d'envoi est présente.
  Une **release** se lance à la main (« Run workflow », case `release`) ; version `1.0.<numéro de
  compilation>`, notes dans `.github/release-notes.md`.
- **Signature** : secrets GitHub `NOUGAT_KEYSTORE*` (clé de l'app, `nougat.jks`) et
  `NOUGAT_UPLOAD_*` (clé d'envoi Google Play). Les clés ne sont **jamais** dans le dépôt.
- Branche `main` **protégée** : seul le propriétaire pousse directement ; les autres passent par
  une demande de fusion approuvée.

## État actuel (novembre 2026)

- Dernière release publique : **v1.0.55** (scanner, couleur libre, OCR + Gemini Nano).
- Sur la branche de travail de Claude Code, pas encore en release : traducteur, effacement de
  lignes (y compris sur les scans), symboles natifs dans l'AAB, captures pour Google Play.
- **Google Play** : compte créé, signature (Play App Signing + clé d'envoi) en place, premier AAB
  accepté (version 64) en test fermé ; il faut 12 testeurs pendant 14 jours avant la publication.
- Pages légales : `https://artisango.ch/nougatpdf/terms-conditions`, `…/privacy-policy`,
  `…/impressum`.

## Limites connues

- Sur Windows, la traduction passe par Claude : il faut une clé API, pas de traduction hors ligne.
- Les fonctions Android (Gemini Nano, ML Kit, scanner, copie traduite) sont compilées et testées
  par la compilation, mais peu essayées sur de vrais téléphones.
