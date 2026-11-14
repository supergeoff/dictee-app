package top.supergeoff.dictee

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.security.KeyStore
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class SynthesisCredentialStoreTest {
    @Test fun persistsSyntheticKeyAndIsolatesTranscriptionAlias() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val id = UUID.randomUUID().toString()
        val synthesisAlias = "test-synthesis-$id"
        val transcriptionAlias = "test-transcription-$id"
        val prefs = "test-synthesis-$id"
        val transcriptionPrefs = "test-transcription-$id"
        val secret = "synthetic-synthesis-key"
        val keystore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        try {
            val synthesis = AndroidCredentialStore(context, synthesisAlias, prefs)
            val transcription = AndroidCredentialStore(context, transcriptionAlias, transcriptionPrefs)
            val ref = synthesis.save(secret)
            val sttRef = transcription.save("synthetic-transcription-key")
            assertFalse(context.getSharedPreferences(prefs, Context.MODE_PRIVATE).getString(ref, null)!!.contains(secret))
            assertEquals(secret, AndroidCredentialStore(context, synthesisAlias, prefs).read(ref))
            assertEquals("synthetic-transcription-key", AndroidCredentialStore(context, transcriptionAlias, transcriptionPrefs).read(sttRef))
            try { AndroidCredentialStore(context, transcriptionAlias, prefs).read(ref); fail() }
            catch (e: CredentialException) { assertFalse(e.toString().contains(secret)); assertNull(e.cause) }
            try { transcription.read(ref); fail() } catch (_: CredentialException) { }
            keystore.deleteEntry(transcriptionAlias)
            assertEquals(secret, synthesis.read(ref))
        } finally {
            keystore.deleteEntry(synthesisAlias)
            keystore.deleteEntry(transcriptionAlias)
            context.getSharedPreferences(prefs, Context.MODE_PRIVATE).edit().clear().commit()
            context.getSharedPreferences(transcriptionPrefs, Context.MODE_PRIVATE).edit().clear().commit()
        }
    }
}
