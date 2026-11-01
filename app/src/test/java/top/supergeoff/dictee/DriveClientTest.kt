package top.supergeoff.dictee

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.net.URLDecoder

/** Faux client HTTP : enregistre les requêtes et renvoie les réponses prévues, dans l'ordre. */
class FakeHttp(private vararg val responses: HttpResponse) : Http {
    val requests = mutableListOf<HttpRequest>()
    override fun call(request: HttpRequest): HttpResponse {
        requests += request
        return responses.getOrNull(requests.size - 1) ?: error("requête inattendue : ${request.method} ${request.url}")
    }
}

/** Faux fournisseur de jetons : donne le jeton suivant après chaque invalidation, puis garde le dernier. */
class FakeTokens(private vararg val values: String) : TokenSource {
    val invalidated = mutableListOf<String>()
    override fun token() = values[minOf(invalidated.size, values.size - 1)]
    override fun invalidate(token: String) { invalidated += token }
}

private fun json(code: Int, body: String, headers: Map<String, String> = emptyMap()) = HttpResponse(code, body, headers)
private fun HttpRequest.query(): String = URLDecoder.decode(url.substringAfter("?"), "UTF-8")
private fun HttpRequest.jsonBody(): JSONObject = JSONObject(String((body as HttpBody.Bytes).bytes))

class DriveClientTest {

    @Test
    fun `un dossier choisi ou l'app peut ajouter des fichiers est accepte avec son nom`() {
        val http = FakeHttp(
            json(200, """{"id":"P1","name":"Dictées","mimeType":"application/vnd.google-apps.folder","trashed":false,"capabilities":{"canAddChildren":true}}"""),
        )
        val folder = DriveClient(http, FakeTokens("jeton")).checkFolder("P1")
        assertEquals(DriveFolder("P1", "Dictées"), folder)
        assertEquals("GET", http.requests[0].method)
        assertTrue(http.requests[0].url.startsWith("https://www.googleapis.com/drive/v3/files/P1?"))
        assertTrue(http.requests[0].query().contains("capabilities(canAddChildren)"))
    }

    @Test
    fun `un dossier choisi inutilisable est refuse avec la raison`() {
        val cases = listOf(
            """{"id":"P1","name":"note.txt","mimeType":"text/plain","trashed":false,"capabilities":{"canAddChildren":false}}""" to "n'est pas un dossier",
            """{"id":"P1","name":"Ancien","mimeType":"application/vnd.google-apps.folder","trashed":true,"capabilities":{"canAddChildren":true}}""" to "corbeille",
            """{"id":"P1","name":"Partagé","mimeType":"application/vnd.google-apps.folder","trashed":false,"capabilities":{"canAddChildren":false}}""" to "ajouter des fichiers",
            """{"id":"P1","name":"Sans droits","mimeType":"application/vnd.google-apps.folder","trashed":false}""" to "ajouter des fichiers",
        )
        for ((body, reason) in cases) {
            try {
                DriveClient(FakeHttp(json(200, body)), FakeTokens("jeton")).checkFolder("P1")
                fail("exception attendue pour $body")
            } catch (e: DriveException) {
                assertTrue(e.message!!, e.message!!.contains(reason))
            }
        }
    }

    @Test
    fun `une reponse en erreur autre que 401 leve une exception avec le code HTTP sans nouvel essai`() {
        val http = FakeHttp(json(403, """{"error":{"message":"Insufficient Permission"}}"""))
        try {
            DriveClient(http, FakeTokens("jeton")).listFiles("F1")
            fail("exception attendue")
        } catch (e: DriveException) {
            assertEquals(403, e.code)
            assertTrue(e.message!!.contains("Insufficient Permission"))
            assertEquals(1, http.requests.size)
        }
    }

    @Test
    fun `une erreur 401 renouvelle le jeton et refait l'appel une fois`() {
        val http = FakeHttp(json(401, """{"error":{"message":"Invalid Credentials"}}"""), json(200, """{"files":[]}"""))
        val tokens = FakeTokens("t1", "t2")
        assertEquals(emptyMap<String, Long>(), DriveClient(http, tokens).listFiles("F1"))
        assertEquals(listOf("Bearer t1", "Bearer t2"), http.requests.map { it.headers["Authorization"] })
        assertEquals(listOf("t1"), tokens.invalidated)
    }

    @Test
    fun `une seconde erreur 401 demande de renouveler l'acces`() {
        val http = FakeHttp(json(401, "{}"), json(401, "{}"))
        val e = assertThrows(DriveException::class.java) { DriveClient(http, FakeTokens("t1", "t2")).listFiles("F1") }
        assertEquals(401, e.code); assertEquals("Accès Google à renouveler", e.message); assertEquals(2, http.requests.size)
    }

    @Test
    fun `la liste donne noms et tailles sur toutes les pages, document sans taille compris`() {
        val http = FakeHttp(
            json(200, """{"nextPageToken":"p2","files":[{"name":"Call-20261030160502.mp3","size":"9880000"}]}"""),
            json(200, """{"files":[{"name":"Notes de réunion"}]}"""))
        assertEquals(mapOf("Call-20261030160502.mp3" to 9_880_000L, "Notes de réunion" to 0L), DriveClient(http, FakeTokens("t")).listFiles("F1"))
        assertTrue(http.requests[0].query().contains("files(name,size)"))
        assertTrue(http.requests[1].query().contains("pageToken=p2"))
    }

    @Test
    fun `la recherche d'un dossier echappe l'apostrophe et ne se limite pas a la racine`() {
        val http = FakeHttp(json(200, """{"files":[]}"""))
        assertNull(DriveClient(http, FakeTokens("t")).findFolder("Comptes rendus d'équipe"))
        val q = http.requests[0].query()
        assertTrue(q, q.contains("name = 'Comptes rendus d\\'équipe'")); assertFalse(q.contains("'root' in parents"))
    }

    @Test
    fun `la recherche d'un dossier echappe aussi la barre oblique inverse`() {
        val http = FakeHttp(json(200, """{"files":[]}"""))
        assertNull(DriveClient(http, FakeTokens("t")).findFolder("a\\b"))
        val q = http.requests[0].query()
        assertTrue(q, q.contains("""name = 'a\\b'"""))
    }

    @Test
    fun `la recherche d'un dossier renvoie le premier resultat hors corbeille`() {
        val http = FakeHttp(json(200, """{"files":[{"id":"M1","name":"Dictée"},{"id":"M2","name":"Dictée"}]}"""))
        assertEquals(DriveFolder("M1", "Dictée"), DriveClient(http, FakeTokens("t")).findFolder("Dictée"))
        val q = http.requests[0].query()
        assertTrue(q, q.contains("name = 'Dictée'"))
        assertTrue(q, q.contains("mimeType = 'application/vnd.google-apps.folder'"))
        assertTrue(q, q.contains("trashed = false"))
        assertTrue(q, q.contains("fields=files(id,name)"))
    }

    @Test
    fun `un dossier se cree a la racine`() {
        val http = FakeHttp(json(200, """{"id":"M1","name":"Dictée"}"""))
        assertEquals(DriveFolder("M1", "Dictée"), DriveClient(http, FakeTokens("t")).createFolder("Dictée"))
        assertEquals("root", http.requests[0].jsonBody().getJSONArray("parents").getString(0))
    }

    @Test
    fun `l'envoi d'un VTT annonce son type et sa taille et lit le flux`() {
        val http = FakeHttp(json(200, "", mapOf("Location" to "https://upload.example/s1")), json(200, """{"id":"X1","size":"3"}"""))
        val size = DriveClient(http, FakeTokens("t")).upload("F1", "Call-20261030160502.vtt", "text/vtt", 3, { "abc".byteInputStream() })
        assertEquals(3L, size)
        assertEquals("text/vtt", http.requests[0].headers["X-Upload-Content-Type"])
        assertEquals("3", http.requests[0].headers["X-Upload-Content-Length"])
        assertEquals("Call-20261030160502.vtt", http.requests[0].jsonBody().getString("name"))
        val body = http.requests[1].body as HttpBody.Stream
        assertEquals(3L, body.length); assertEquals("abc", body.open().readBytes().decodeToString())
    }

    @Test
    fun `une erreur 401 sur l'envoi du flux refait la requete avec un nouveau jeton et le meme flux`() {
        val http = FakeHttp(
            json(200, "", mapOf("Location" to "https://upload.example/s1")),
            json(401, "{}"),
            json(200, """{"id":"X1","size":"3"}"""),
        )
        val tokens = FakeTokens("t1", "t2")
        val size = DriveClient(http, tokens).upload("F1", "Call-20261030160502.vtt", "text/vtt", 3, { "abc".byteInputStream() })
        assertEquals(3L, size)
        assertEquals(listOf("Bearer t1", "Bearer t1", "Bearer t2"), http.requests.map { it.headers["Authorization"] })
        assertEquals(listOf("t1"), tokens.invalidated)
        assertEquals("https://upload.example/s1", http.requests[2].url)
        val body = http.requests[2].body as HttpBody.Stream
        assertEquals(3L, body.length); assertEquals("abc", body.open().readBytes().decodeToString())
    }

    @Test
    fun `l'adresse du compte se lit par about avec le seul champ user emailAddress`() {
        val http = FakeHttp(json(200, """{"user":{"emailAddress":"user@example.com"}}"""))
        assertEquals("user@example.com", DriveClient(http, FakeTokens("t")).accountEmail())
        assertEquals("GET", http.requests[0].method)
        assertTrue(http.requests[0].url.startsWith("https://www.googleapis.com/drive/v3/about?"))
        assertEquals("fields=user(emailAddress)", http.requests[0].query())
        assertEquals("Bearer t", http.requests[0].headers["Authorization"])
    }

    @Test
    fun `une reponse sans adresse donne null`() {
        for (body in listOf("""{"user":{}}""", """{}""", """{"user":{"emailAddress":null}}""")) {
            assertNull(body, DriveClient(FakeHttp(json(200, body)), FakeTokens("t")).accountEmail())
        }
    }

    @Test
    fun `une erreur HTTP sur la lecture de l'adresse leve une exception avec le code, sans nouvel essai`() {
        val http = FakeHttp(json(403, """{"error":{"message":"Insufficient Permission"}}"""))
        val e = assertThrows(DriveException::class.java) { DriveClient(http, FakeTokens("t")).accountEmail() }
        assertEquals(403, e.code); assertTrue(e.message!!.contains("Insufficient Permission")); assertEquals(1, http.requests.size)
    }

    @Test
    fun `la lecture de l'adresse renouvelle le jeton sur une erreur 401 comme les autres appels`() {
        val http = FakeHttp(json(401, "{}"), json(200, """{"user":{"emailAddress":"user@example.com"}}"""))
        val tokens = FakeTokens("t1", "t2")
        assertEquals("user@example.com", DriveClient(http, tokens).accountEmail())
        assertEquals(listOf("Bearer t1", "Bearer t2"), http.requests.map { it.headers["Authorization"] })
        assertEquals(listOf("t1"), tokens.invalidated)
    }
}
