package top.supergeoff.dictee

import android.content.Context
import android.util.Base64
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.security.KeyStore
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class AndroidCredentialStoreTest {
    @Test fun encryptsAndDecryptsAndDetectsLostKey() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val id = UUID.randomUUID().toString()
        val alias = "test-transcription-$id"
        val prefsName = "test-transcription-$id"
        val secret = "synthetic-device-key"
        val store = AndroidCredentialStore(context, alias, prefsName)
        val ref = store.save(secret)
        val encrypted = context.getSharedPreferences(prefsName, Context.MODE_PRIVATE).getString(ref, null)!!
        assertFalse(encrypted.contains(secret))
        assertFalse(Base64.decode(encrypted, Base64.NO_WRAP).toString(Charsets.ISO_8859_1).contains(secret))
        assertEquals(secret, AndroidCredentialStore(context, alias, prefsName).read(ref))
        KeyStore.getInstance("AndroidKeyStore").apply { load(null); deleteEntry(alias) }
        try { store.read(ref); fail("La clé supprimée ne doit plus déchiffrer") }
        catch (e: CredentialException) { assertFalse(e.toString().contains(secret)); assertNull(e.cause) }
    }
}
