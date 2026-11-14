package top.supergeoff.dictee

import android.content.ContextWrapper
import android.content.pm.ProviderInfo
import android.content.ContentResolver
import android.test.mock.MockContentResolver
import android.provider.DocumentsContract
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class PhoneFolderGuardTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().context
    private lateinit var provider: FakeDocumentsProvider
    private lateinit var folder: PhoneFolder
    private lateinit var source: File

    @Before fun setUp() {
        val tree = DocumentsContract.buildTreeDocumentUri(FakeDocumentsProvider.AUTHORITY, FakeDocumentsProvider.ROOT)
        val resolver = MockContentResolver(context)
        val wrapped = object : ContextWrapper(context) {
            override fun getContentResolver(): ContentResolver = resolver
        }
        provider = FakeDocumentsProvider()
        provider.attachInfo(wrapped, ProviderInfo().apply {
            authority = FakeDocumentsProvider.AUTHORITY
            exported = true
            grantUriPermissions = true
            readPermission = "android.permission.MANAGE_DOCUMENTS"
            writePermission = "android.permission.MANAGE_DOCUMENTS"
        })
        resolver.addProvider(FakeDocumentsProvider.AUTHORITY, provider)
        folder = PhoneFolder(wrapped, tree)
        source = File.createTempFile("synthetic-md-", ".tmp", context.cacheDir).apply { writeText("new synthetic content") }
    }
    @After fun tearDown() { provider.reset(); source.delete() }

    @Test fun renameFailureBeforePromotionPreservesOldEvenIfRestoreFails() {
        provider.put("note.md", "old synthetic content")
        provider.failRenameTo = "note.md"
        assertThrows(PhoneStoreException::class.java) { folder.writeGuarded("note.md", source, true) { it() } }
        assertEquals("old synthetic content", provider.contents("note.md") ?: provider.contents("note.md.old"))
        assertNull(provider.contents("note.md.part"))
    }

    @Test fun refusedGuardLeavesOldUntouchedAndDeletesPart() {
        provider.put("note.md", "old synthetic content")
        var calls = 0
        assertThrows(PhoneStoreException::class.java) { folder.writeGuarded("note.md", source, true) { calls++ } }
        assertEquals(1, calls)
        assertEquals("old synthetic content", provider.contents("note.md"))
        assertNull(provider.contents("note.md.old"))
        assertNull(provider.contents("note.md.part"))
    }

    @Test fun unreadableNameAfterPromotionReportsErrorAndLeavesNew() {
        provider.put("note.md", "old synthetic content")
        provider.unreadablePromoted = true
        assertThrows(PhoneStoreException::class.java) { folder.writeGuarded("note.md", source, true) { it() } }
        assertEquals("new synthetic content", provider.contents("note.md"))
        assertNull(provider.contents("note.md.old"))
    }

    @Test fun missingModeRejectsFileAppearingBeforeFirstCheck() {
        provider.put("note.md", "appeared")
        assertThrows(PhoneStoreException::class.java) { folder.writeGuarded("note.md", source, false) { it() } }
        assertEquals("appeared", provider.contents("note.md"))
        assertNull(provider.contents("note.md.part"))
    }

    @Test fun missingModeRejectsFileAppearingBetweenChecks() {
        provider.appearOnChildQuery = 3
        assertThrows(PhoneStoreException::class.java) { folder.writeGuarded("note.md", source, false) { it() } }
        assertEquals("appeared", provider.contents("note.md"))
        assertNull(provider.contents("note.md.part"))
        assertNull(provider.contents("note.md.old"))
    }

    @Test fun acceptedPromotionWritesOnce() {
        var calls = 0
        folder.writeGuarded("note.md", source, false) { calls++; it() }
        assertEquals(1, calls)
        assertEquals("new synthetic content", provider.contents("note.md"))
    }
}
