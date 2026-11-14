package top.supergeoff.dictee

import java.io.File
import java.io.InputStream

/** Dossier du téléphone où sont écrits les fichiers des enregistrements. */
interface PhoneStore {
    /** Fichiers du dossier dont le nom est reconnu par [RecordName.parse], avec leur taille. Ne lève que des [PhoneStoreException]. */
    fun list(): List<PhoneFile>

    /** Crée le fichier [fileName] avec le contenu de [source], ou remplace le fichier de ce nom. Ne lève que des [PhoneStoreException]. */
    fun write(fileName: String, source: File)

    /**
     * Prépare la copie puis appelle une fois [promote] avec la fermeture synchrone de promotion.
     * Le coordinateur peut refuser en ne l'exécutant pas ; il sérialise son exécution et l'annulation persistée
     * sous un même verrou court. Ce verrou ne protège pas des autres processus ou fournisseurs SAF.
     * Appel bloquant à effectuer sur Dispatchers.IO. Ne lève que des [PhoneStoreException].
     */
    fun writeGuarded(fileName: String, source: File, replace: Boolean, promote: ((() -> Unit) -> Unit))

    /** Contenu du fichier [fileName]. Ne lève que des [PhoneStoreException], dont une s'il est absent. */
    fun open(fileName: String): InputStream
}

/**
 * Erreur du dossier du téléphone : dossier perdu, plein ou fichier absent.
 * [message] est celui que l'utilisateur lit ; [cause] garde l'exception d'origine, pour le journal.
 */
class PhoneStoreException(message: String, cause: Throwable? = null) : Exception(message, cause) {

    /** Le message, suivi de la classe et du message de la cause quand il y en a une : le texte de la ligne du journal. */
    val journalText: String
        get() {
            val origin = cause ?: return message.orEmpty()
            return "$message (cause : ${origin.javaClass.simpleName}${origin.message?.let { " : $it" } ?: ""})"
        }
}
