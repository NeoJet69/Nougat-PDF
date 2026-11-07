# À faire

## Google Play

- [ ] Compte développeur Google Play (25 $, vérification d'identité). Compte personnel récent :
      test fermé obligatoire avec 12 testeurs pendant 14 jours. Compte d'entreprise (ArtisanGo) :
      numéro D-U-N-S, sans ce test.
- [ ] Play App Signing : importer la clé `nougat.jks` (outil PEPK donné par la Play Console),
      pour que l'app du Store et l'APK de GitHub aient la même signature et se mettent à jour
      l'une par-dessus l'autre.
- [ ] Créer la clé d'envoi `upload.jks`, enregistrer son certificat dans la Play Console, et
      l'ajouter aux secrets GitHub (jamais dans le dépôt) :
      `NOUGAT_UPLOAD_KEYSTORE` (fichier en base64), `NOUGAT_UPLOAD_KEYSTORE_PASSWORD`,
      `NOUGAT_UPLOAD_KEY_ALIAS`, `NOUGAT_UPLOAD_KEY_PASSWORD`. La compilation produit alors
      l'AAB (`Nougat-PDF-….aab`), joint aux releases.
- [ ] Bouton « Signaler une réponse » sous les réponses de Claude (règle de Google Play sur
      l'IA générative).
- [ ] Formulaire « Sécurité des données » : rien n'est collecté ; le texte part chez Anthropic
      seulement avec Claude et la clé de l'utilisateur ; OCR, scanner et traduction ML Kit sur
      le téléphone.
- [ ] Fiche du Store : icône 512 × 512, image 1024 × 500, captures (dossier `captures/`),
      description, lien vers la page de confidentialité.
- [ ] Page de confidentialité (artisango.ch) : ajouter la traduction (modèles ML Kit
      téléchargés depuis Google, texte envoyé à Anthropic avec Claude).

## Prochaines fonctions (choisies, pas encore commencées)

- [ ] QR-facture suisse : la lire (montant, IBAN, référence, copier pour l'e-banking) et en
      ajouter une à un devis ou une facture.
- [ ] Métré sur plan : étalonner l'échelle, mesurer longueurs et surfaces au doigt.
- [ ] « Ce que je dois faire » : résumé simple d'une lettre et échéances vers le calendrier
      (Gemini Nano, sur le téléphone).
- [ ] Remplir un formulaire papier scanné (champs détectés, profil enregistré sur l'appareil).
- [ ] Lecture à voix haute, aussi dans la langue traduite.

## Outils demandés (prochaine série)

- [ ] Censurer : par mot ou expression (recherche dans le texte), ou censure intelligente qui
      propose les noms de personnes et d'entreprises, numéros AVS et pièces d'identité, dates de
      naissance, adresses, téléphones, e-mails, numéros de compte, de police d'assurance et de
      dossier médical, et demande s'il y a autre chose à cacher. Tout sélectionner / tout
      désélectionner, Annuler / Confirmer ; barres noires et texte vraiment retiré ; OCR proposé
      d'abord pour les scans.
- [ ] Outil « Masquer » (rectangle blanc ou noir du mode Annoter) : il pose seulement un rectangle,
      le texte dessous reste copiable (Ctrl + A, copier-coller). Le faire retirer aussi le texte
      et les pixels sous le rectangle, comme « Effacer » du mode Éditer.
- [ ] Bouton « Finaliser pour envoyer » : transformer les pages en images (PDF aplati), pour être
      sûr que plus rien ne peut être repris (texte, calques, annotations, métadonnées). En option,
      garder une copie cherchable en refaisant l'OCR sur les images.
- [ ] Compresser un PDF (images réduites et recompressées, plusieurs niveaux).
- [ ] Rogner une page, et redresser un scan un peu penché.
- [ ] Numéros de page et date (position, format, première page ou non).
- [ ] PDF vers Markdown (titres, listes, tableaux, avec l'OCR pour les scans) et Markdown vers PDF.

## Autres idées

- [ ] Comparer deux versions d'un document.
- [ ] Envoyer un PDF du téléphone au PC par le Wi-Fi local (QR code, sans cloud).
- [ ] Notes de frais : tickets scannés, montant, date et TVA vers un PDF et un tableau.
- [ ] Traduire sur Windows sans Internet.

## Release

- [ ] Fusionner le traducteur dans main et publier une release (plus tard).
