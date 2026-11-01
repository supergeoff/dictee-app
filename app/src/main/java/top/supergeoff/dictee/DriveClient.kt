package top.supergeoff.dictee

import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.common.api.Scope
import org.json.JSONArray
import org.json.JSONObject
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpRetryException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** Source du jeton d'accès Drive. */
interface TokenSource {
    /** Donne un jeton, tiré du cache s'il y en a un. */
    fun token(): String

    /** Efface ce jeton du cache (`clearToken` sur Android) : l'appel suivant à [token] en donne un nouveau. */
    fun invalidate(token: String)
}

sealed class HttpBody(val contentType: String) {
    class Bytes(val bytes: ByteArray, contentType: String) : HttpBody(contentType)

    /** Corps de [length] octets lu dans un flux. [open] est rappelé à chaque envoi, [onProgress] reçoit le total envoyé après chaque bloc. */
    class Stream(
        val length: Long,
        val open: () -> InputStream,
        contentType: String,
        val onProgress: (sent: Long) -> Unit = {},
    ) : HttpBody(contentType)
}

class HttpRequest(val method: String, val url: String, val headers: Map<String, String>, val body: HttpBody? = null)
class HttpResponse(val code: Int, val body: String, val headers: Map<String, String>)

/** Petite interface HTTP, remplacée par un faux client dans les tests. */
interface Http {
    fun call(request: HttpRequest): HttpResponse
}

class DriveException(val code: Int, message: String) : Exception(message)

data class DriveFolder(val id: String, val name: String)

/**
 * Appels à l'API REST Drive v3 avec le droit `drive.file` (docs/specification.md, section « Envoi vers Google Drive »).
 * Chaque appel demande son jeton à [tokens]. Sur une réponse 401, le jeton est invalidé puis l'appel refait une seule fois.
 */
class DriveClient(private val http: Http, private val tokens: TokenSource) {

    /** Contrôle un dossier choisi dans le sélecteur : un dossier hors corbeille où l'app peut ajouter des fichiers. */
    fun checkFolder(id: String): DriveFolder {
        val fields = enc("id,name,mimeType,trashed,capabilities(canAddChildren)")
        val json = JSONObject(ok(send("GET", "$API/files/$id?fields=$fields")))
        val name = json.optString("name", id)
        if (json.optString("mimeType") != FOLDER_MIME) throw DriveException(0, "« $name » n'est pas un dossier")
        if (json.optBoolean("trashed", false)) throw DriveException(0, "le dossier « $name » est dans la corbeille")
        if (json.optJSONObject("capabilities")?.optBoolean("canAddChildren", false) != true) {
            throw DriveException(0, "Drive n'autorise pas l'app à ajouter des fichiers dans « $name »")
        }
        return DriveFolder(id, name)
    }

    /** Premier dossier de ce nom hors corbeille, n'importe où dans le Drive, parmi ceux que l'app peut voir. */
    fun findFolder(name: String): DriveFolder? {
        val q = "name = '${escape(name)}' and mimeType = '$FOLDER_MIME' and trashed = false"
        val url = "$API/files?q=${enc(q)}&fields=${enc("files(id,name)")}&spaces=drive"
        val found = JSONObject(ok(send("GET", url))).optJSONArray("files")
        if (found == null || found.length() == 0) return null
        val folder = found.getJSONObject(0)
        return DriveFolder(folder.getString("id"), folder.optString("name", name))
    }

    /** Crée un dossier de ce nom à la racine du Drive. */
    fun createFolder(name: String): DriveFolder {
        val meta = JSONObject()
            .put("name", name)
            .put("mimeType", FOLDER_MIME)
            .put("parents", JSONArray().put("root"))
        val created = JSONObject(ok(send("POST", "$API/files?fields=${enc("id,name")}", jsonBody(meta))))
        return DriveFolder(created.getString("id"), created.optString("name", name))
    }

    /** Taille de chaque fichier non supprimé du dossier, par nom, toutes pages comprises. Un document sans taille vaut 0. */
    fun listFiles(folderId: String): Map<String, Long> {
        val sizes = mutableMapOf<String, Long>()
        var pageToken: String? = null
        do {
            val q = "'${escape(folderId)}' in parents and trashed = false"
            var url = "$API/files?q=${enc(q)}&fields=${enc("nextPageToken,files(name,size)")}&pageSize=1000"
            if (pageToken != null) url += "&pageToken=${enc(pageToken)}"
            val json = JSONObject(ok(send("GET", url)))
            val files = json.optJSONArray("files") ?: JSONArray()
            for (i in 0 until files.length()) {
                val file = files.getJSONObject(i)
                sizes[file.getString("name")] = file.optString("size").toLongOrNull() ?: 0L
            }
            pageToken = json.optString("nextPageToken").ifEmpty { null }
        } while (pageToken != null)
        return sizes
    }

    /**
     * Adresse du compte Google de l'accès, lue par `about.get` : le droit `drive.file` suffit. Null si Drive ne la donne pas.
     * Google Play Services ne la donne pas avec le jeton, même avec la portée `email`.
     */
    fun accountEmail(): String? {
        val user = JSONObject(ok(send("GET", "$API/about?fields=${enc("user(emailAddress)")}"))).optJSONObject("user")
        return user?.takeUnless { it.isNull("emailAddress") }?.getString("emailAddress")?.ifBlank { null }
    }

    /** Téléversement reprenable de [length] octets lus dans [open]. Renvoie la taille enregistrée par Drive, -1 si elle est absente. */
    fun upload(
        folderId: String,
        fileName: String,
        mime: String,
        length: Long,
        open: () -> InputStream,
        onProgress: (sent: Long) -> Unit = {},
    ): Long {
        val meta = JSONObject()
            .put("name", fileName)
            .put("mimeType", mime)
            .put("parents", JSONArray().put(folderId))
        val init = send(
            "POST",
            "$UPLOAD/files?uploadType=resumable&fields=${enc("id,size")}",
            jsonBody(meta),
            mapOf("X-Upload-Content-Type" to mime, "X-Upload-Content-Length" to length.toString()),
        )
        ok(init)
        val location = init.headers.entries.firstOrNull { it.key.equals("Location", ignoreCase = true) }?.value
            ?: throw DriveException(init.code, "adresse de téléversement absente")
        val done = ok(send("PUT", location, HttpBody.Stream(length, open, mime, onProgress)))
        return JSONObject(done).optString("size").toLongOrNull() ?: -1L
    }

    /**
     * Envoie une requête avec le jeton courant. Sur une réponse 401, invalide ce jeton, en redemande un et refait
     * la requête une seule fois ; une seconde réponse 401 demande de renouveler l'accès.
     * Le corps est réutilisé tel quel au second essai : un corps [HttpBody.Stream] rouvre alors son flux.
     */
    private fun send(
        method: String,
        url: String,
        body: HttpBody? = null,
        headers: Map<String, String> = emptyMap(),
    ): HttpResponse {
        fun attempt(token: String) = http.call(HttpRequest(method, url, headers + ("Authorization" to "Bearer $token"), body))
        val token = tokens.token()
        val first = attempt(token)
        if (first.code != 401) return first
        tokens.invalidate(token)
        val second = attempt(tokens.token())
        if (second.code == 401) throw DriveException(401, RENEW_MESSAGE)
        return second
    }

    private fun jsonBody(json: JSONObject) = HttpBody.Bytes(json.toString().toByteArray(), "application/json; charset=UTF-8")

    private fun ok(r: HttpResponse): String {
        if (r.code in 200..299) return r.body
        val message = runCatching { JSONObject(r.body).getJSONObject("error").getString("message") }.getOrDefault(r.body.take(200))
        throw DriveException(r.code, "Drive a répondu ${r.code} : $message")
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    /** Protège, par une barre oblique inverse, la barre oblique inverse et l'apostrophe d'une valeur de la requête `q`. */
    private fun escape(s: String) = s.replace("\\", "\\\\").replace("'", "\\'")

    companion object {
        const val DEFAULT_FOLDER = "Dictée"
        const val RENEW_MESSAGE = "Accès Google à renouveler"
        const val FOLDER_MIME = "application/vnd.google-apps.folder"
        const val SCOPE = "https://www.googleapis.com/auth/drive.file"
        private const val API = "https://www.googleapis.com/drive/v3"
        private const val UPLOAD = "https://www.googleapis.com/upload/drive/v3"

        /** Demande d'autorisation Google Play Services pour le droit `drive.file`. L'adresse du compte se lit ensuite par [accountEmail]. */
        fun authorizationRequest(): AuthorizationRequest =
            AuthorizationRequest.builder().setRequestedScopes(listOf(Scope(SCOPE))).build()

        /** Demande qui ouvre le sélecteur Drive de Google pour choisir un dossier, avec le même droit `drive.file`. */
        fun folderPickerRequest(): AuthorizationRequest =
            AuthorizationRequest.builder()
                .setRequestedScopes(listOf(Scope(SCOPE)))
                .setOptOutIncludingGrantedScopes(true)
                .setPrompt(AuthorizationRequest.Prompt.CONSENT)
                .addResourceParameter(AuthorizationRequest.ResourceParameter.PICKER_OAUTH_TRIGGER, "true")
                .addResourceParameter(AuthorizationRequest.ResourceParameter.PICKER_ALLOW_FOLDER_SELECTION, "true")
                .build()
    }
}

/** Implémentation HTTP d'Android, par HttpURLConnection. */
class UrlHttp : Http {
    override fun call(request: HttpRequest): HttpResponse {
        val c = URL(request.url).openConnection() as HttpURLConnection
        try {
            c.requestMethod = request.method
            c.connectTimeout = 30_000
            c.readTimeout = 120_000
            request.headers.forEach { (k, v) -> c.setRequestProperty(k, v) }
            when (val body = request.body) {
                is HttpBody.Bytes -> {
                    c.doOutput = true
                    c.setRequestProperty("Content-Type", body.contentType)
                    c.setFixedLengthStreamingMode(body.bytes.size)
                    c.outputStream.use { it.write(body.bytes) }
                }
                is HttpBody.Stream -> {
                    c.doOutput = true
                    c.setRequestProperty("Content-Type", body.contentType)
                    c.setFixedLengthStreamingMode(body.length)
                    c.outputStream.use { out -> body.open().use { copyByBlocks(it, out, body.onProgress) } }
                }
                null -> Unit
            }
            // En envoi à longueur fixe, un 401 lève cette exception au lieu de rendre la réponse : DriveClient doit le voir pour son nouvel essai.
            val code = try {
                c.responseCode
            } catch (e: HttpRetryException) {
                return HttpResponse(e.responseCode(), "", emptyMap())
            }
            val stream = if (code >= 400) c.errorStream else c.inputStream
            val text = stream?.bufferedReader()?.use { it.readText() } ?: ""
            val headers = c.headerFields.entries.filter { it.key != null }.associate { it.key!! to it.value.joinToString(",") }
            return HttpResponse(code, text, headers)
        } finally {
            c.disconnect()
        }
    }

    /** Copie le flux par blocs de 64 Kio et signale le total envoyé après chaque bloc. */
    private fun copyByBlocks(input: InputStream, out: OutputStream, onProgress: (sent: Long) -> Unit) {
        val buffer = ByteArray(BLOCK_SIZE)
        var sent = 0L
        while (true) {
            val read = input.read(buffer)
            if (read < 0) return
            out.write(buffer, 0, read)
            sent += read
            onProgress(sent)
        }
    }

    private companion object {
        const val BLOCK_SIZE = 64 * 1024
    }
}
