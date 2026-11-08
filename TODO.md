# À faire

## Google Play

- [x] Compte développeur Google Play (25 $, vérification d'identité). Compte personnel récent :
      test fermé obligatoire avec 12 testeurs pendant 14 jours. Compte d'entreprise (ArtisanGo) :
      numéro D-U-N-S, sans ce test.
- [x] Play App Signing : importer la clé `nougat.jks` (outil PEPK donné par la Play Console),
      pour que l'app du Store et l'APK de GitHub aient la même signature et se mettent à jour
      l'une par-dessus l'autre.
- [x] Créer la clé d'envoi `upload.jks`, enregistrer son certificat dans la Play Console, et
      l'ajouter aux secrets GitHub (jamais dans le dépôt) :
      `NOUGAT_UPLOAD_KEYSTORE` (fichier en base64), `NOUGAT_UPLOAD_KEYSTORE_PASSWORD`,
      `NOUGAT_UPLOAD_KEY_ALIAS`, `NOUGAT_UPLOAD_KEY_PASSWORD`. La compilation produit alors
      l'AAB (`Nougat-PDF-….aab`), joint aux releases.
- [x] Bouton « Signaler une réponse » sous les réponses de Claude (règle de Google Play sur
      l'IA générative).
- [ ] Formulaire « Sécurité des données » : rien n'est collecté ; le texte part chez Anthropic
      seulement avec Claude et la clé de l'utilisateur ; OCR, scanner et traduction ML Kit sur
      le téléphone.
- [x] Fiche du Store : icône 512 × 512, image 1024 × 500, captures (dossier `captures/`),
      description, lien vers la page de confidentialité.
- [ ] Page de confidentialité (artisango.ch) : ajouter la traduction (modèles ML Kit
      téléchargés depuis Google, texte envoyé à Anthropic avec Claude).

## Prochaines fonctions (choisies)

- [x] QR-facture suisse : la lire (montant, IBAN, référence, copier pour l'e-banking) et en
      ajouter une à un devis ou une facture.
- [x] Métré sur plan : étalonner l'échelle, mesurer longueurs et surfaces au doigt.
- [x] « Ce que je dois faire » : résumé simple d'une lettre et échéances vers le calendrier
      (Gemini Nano, sur le téléphone).
- [x] Remplir un formulaire papier scanné (champs détectés, profil enregistré sur l'appareil).
- [x] Lecture à voix haute, aussi dans la langue traduite.

## Outils demandés (prochaine série)

- [x] Censurer : par mot ou expression (recherche dans le texte), ou censure intelligente qui
      propose les noms de personnes et d'entreprises, numéros AVS et pièces d'identité, dates de
      naissance, adresses, téléphones, e-mails, numéros de compte, de police d'assurance et de
      dossier médical, et demande s'il y a autre chose à cacher. Tout sélectionner / tout
      désélectionner, Annuler / Confirmer ; barres noires et texte vraiment retiré ; OCR proposé
      d'abord pour les scans.
- [x] Outil « Masquer » (rectangle blanc ou noir du mode Annoter) : il pose seulement un rectangle,
      le texte dessous reste copiable (Ctrl + A, copier-coller). Le faire retirer aussi le texte
      et les pixels sous le rectangle, comme « Effacer » du mode Éditer.
- [x] Bouton « Finaliser pour envoyer » : transformer les pages en images (PDF aplati), pour être
      sûr que plus rien ne peut être repris (texte, calques, annotations, métadonnées). En option,
      garder une copie cherchable en refaisant l'OCR sur les images.
- [x] Compresser un PDF (images réduites et recompressées, plusieurs niveaux).
- [x] Rogner une page, et redresser un scan un peu penché.
- [x] Numéros de page et date (position, format, première page ou non).
- [x] PDF vers Markdown (titres, listes, tableaux, avec l'OCR pour les scans) et Markdown vers PDF.

## Retours de Dylan après la 1.0.79 (à faire plus tard)

- [x] Échange par Wi-Fi, cause trouvée : le Pixel 9 Pro de Dylan bloque le réseau local (sécurités
      du téléphone) ; avec un OnePlus, la page marche. Aide ajoutée dans la fenêtre.
- [ ] Échange par Wi-Fi (suite) : le téléphone trouve bien l'adresse (192.168.0.39:54193) mais Chrome dit
      « Ce site est inaccessible, ERR_TOO_MANY_RETRIES » : la connexion est bloquée. Causes probables :
      pare-feu Windows (accès refusé ou réseau réglé en « Public ») ou VPN sur le téléphone. À faire :
      aide dans la fenêtre (pare-feu, réseau privé, VPN, Wi-Fi invité), port fixe pour une règle de
      pare-feu stable, et proposer d'ajouter la règle de pare-feu à l'installation.
- [ ] Android : bouton « Recevoir du PC » avec la caméra dans Nougat (scanner le QR code du PC) qui
      télécharge le fichier directement dans l'app, et « Envoyer au PC » du document ouvert.
- [ ] Censurer sur les scans : le texte est bien trouvé, mais les barres ne cachent pas bien
      (placement ou taille des barres sur l'image à revoir).
- [ ] Sélectionner du texte en mode Lire, à la souris ou au doigt, pour le copier directement.
- [ ] Déplacer (et redimensionner) les barres noires de censure, les rectangles Masquer et les
      annotations après les avoir posés.
- [ ] Barre du bas plus claire : regrouper les outils au lieu du menu ⋮ (Censurer, Remplir un
      formulaire papier…), et dans Éditer faire apparaître les outils de pages (Compresser, Numéros
      de page, Rogner, Redresser), comme Annoter montre ses outils.
- [ ] Rogner à la main (cadre à tirer à la souris ou au doigt), en plus du rognage automatique.
- [ ] Redresser à la main (faire tourner la page à la souris ou au doigt) si l'automatique se trompe.

## Autres idées

- [ ] Comparer deux versions d'un document.
- [x] Envoyer un PDF du téléphone au PC par le Wi-Fi local (QR code, sans cloud).
- [ ] Notes de frais : tickets scannés, montant, date et TVA vers un PDF et un tableau.
- [ ] Traduire sur Windows sans Internet.
- [ ] IA sur l'appareil à télécharger (pas prioritaire) : l'utilisateur choisit un modèle GGUF
      d'environ 3 Go (licence à vérifier), le télécharge et peut le désinstaller ; moteur llama.cpp
      (MIT) sur Windows puis Android. Sert à traduire hors ligne, « Ce que je dois faire », censure,
      assistant sans clé Claude.

## Release

- [ ] Fusionner le traducteur dans main et publier une release (plus tard).
