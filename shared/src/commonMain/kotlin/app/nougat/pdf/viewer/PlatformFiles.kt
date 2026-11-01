package app.nougat.pdf.viewer

/** Ce que chaque plateforme fait avec les fichiers : boites de dialogue, copies, impression... */
interface PlatformFiles {
    /** Dossier des copies de travail (effacees a la fermeture du document). */
    val tempDir: String

    fun copy(src: String, dst: String)
    fun delete(path: String)

    /**
     * Remplace le fichier d'origine [sourceRef] par [path].
     * @return false si ce n'est pas possible (fichier en lecture seule...) : on proposera « Enregistrer sous ».
     */
    suspend fun overwrite(path: String, sourceRef: String): Boolean

    /** Demande ou enregistrer une copie de [path]. Null si l'utilisateur annule. */
    suspend fun saveAs(path: String, suggestedName: String): SavedFile?

    /** Choisit un autre PDF (pour l'ajouter a la fin). Null si annule. */
    suspend fun pickPdf(): SavedFile?

    /** Imprimer, si la plateforme sait le faire. */
    val canPrint: Boolean
    suspend fun print(path: String, password: String?, name: String)

    /** Partager (Android), si la plateforme sait le faire. */
    val canShare: Boolean
    suspend fun share(path: String, name: String)

    /**
     * Avant d'ecraser l'original pour la premiere fois : en garde une copie « Nom_miaou.pdf ».
     * Renvoie ou elle se trouve (pour le dire a l'utilisateur), ou null si rien n'a ete fait.
     */
    suspend fun backupOriginal(sourceRef: String, name: String): String?

    /** Petits reglages conserves sur l'appareil (cle API de l'assistant...). */
    fun loadSetting(key: String): String?
    fun saveSetting(key: String, value: String)
}

/** Un fichier choisi par l'utilisateur : [ref] pour y reecrire, [path] lisible tout de suite. */
data class SavedFile(val ref: String, val name: String, val path: String = ref)
