package com.piercingxx.xxnote.setup

import android.app.Application
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.piercingxx.xxnote.data.XxDatabase
import com.piercingxx.xxnote.ui.setup.SetupLogic
import com.piercingxx.xxnote.ui.setup.SetupViewModel
import java.util.concurrent.CountDownLatch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * First-run skip writes the local-only sentinel and leaves the credential
 * row empty, so MainActivity's start-route can open the vault without
 * bouncing back to Setup.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SetupLocalOnlyTest {

    @Test
    fun skipLocallyPersistsSentinelWithoutACredential() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val vm = SetupViewModel(app)
        val done = CountDownLatch(1)
        vm.skipLocally { done.countDown() }
        val deadline = System.currentTimeMillis() + 8_000
        while (done.count > 0 && System.currentTimeMillis() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(25)
        }
        assertTrue("skipLocally must finish", done.count == 0L)

        runBlocking {
            val db = XxDatabase.getInstance(app)
            assertEquals(
                SetupLogic.LOCAL_ONLY_VALUE,
                db.settingDao().get(SetupLogic.KEY_LOCAL_ONLY),
            )
            assertNull(db.credentialDao().get())
        }
        assertTrue(
            !SetupLogic.startOnSetup(hasCredential = false, localOnly = true),
        )
    }
}
