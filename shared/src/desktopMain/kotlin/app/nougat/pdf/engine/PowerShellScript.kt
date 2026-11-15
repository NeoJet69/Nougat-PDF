package app.nougat.pdf.engine

import java.io.File

/**
 * Lance un petit script PowerShell ecrit en clair dans un fichier (.ps1), et non en commande codee
 * (-EncodedCommand) : les antivirus (Norton...) bloquent les commandes codees, une methode des
 * logiciels malveillants. Le script reste lisible par qui veut verifier ce que fait Nougat.
 */
internal object PowerShellScript {
    private val folder: File by lazy {
        File(System.getenv("LOCALAPPDATA") ?: System.getProperty("java.io.tmpdir"), "Nougat PDF Scripts").apply { mkdirs() }
    }

    /** Ecrit [script] dans [name].ps1 (UTF-8 avec BOM, pour les accents) et prepare PowerShell. */
    fun process(name: String, script: String, vararg args: String): ProcessBuilder {
        val file = File(folder, "$name.ps1")
        val bytes = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + script.replace("\n", "\r\n").toByteArray(Charsets.UTF_8)
        if (!file.isFile || !file.readBytes().contentEquals(bytes)) file.writeBytes(bytes)
        return ProcessBuilder(listOf("powershell.exe", "-NoProfile", "-ExecutionPolicy", "Bypass", "-File", file.absolutePath) + args)
    }
}
