package com.piercingxx.xxnote.data

import android.content.Context
import android.content.ContextWrapper
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * Hardening #9 gate coverage for [XxDatabase.getInstance]'s keying:
 *
 * - within one environment (one filesDir), every caller shares one handle;
 * - a DIFFERENT environment (different filesDir) builds its own instance.
 *
 * Both halves are asserted inside single test methods against explicit
 * environments, so the proof never depends on JUnit method order or on
 * Robolectric's per-method temp-dir rotation.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class XxDatabaseSingletonTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun repeatedCallsInOneEnvironmentReturnTheSameInstance() {
        assertSame(
            XxDatabase.getInstance(ApplicationProvider.getApplicationContext()),
            XxDatabase.getInstance(
                ApplicationProvider.getApplicationContext<Context>().applicationContext,
            ),
        )
    }

    @Test
    fun eachFreshEnvironmentBuildsItsOwnInstance() {
        val envA = isolatedEnvironment(tmp.newFolder("env-a"))
        val envB = isolatedEnvironment(tmp.newFolder("env-b"))

        val instanceA = XxDatabase.getInstance(envA)
        // The cache hit is keyed to env A's filesDir path...
        assertSame(instanceA, XxDatabase.getInstance(envA))
        // ...and env B's different filesDir rebuilds instead of reusing it.
        assertNotSame(instanceA, XxDatabase.getInstance(envB))
    }

    /**
     * A context standing in for an independent environment: its application
     * context reports [envDir] as filesDir and opens databases inside it, so
     * [XxDatabase.getInstance] keys and builds it separately from the real
     * app environment (mirrors how each Robolectric method gets a fresh dir).
     */
    private fun isolatedEnvironment(envDir: File): Context {
        val appContext = object : ContextWrapper(ApplicationProvider.getApplicationContext()) {
            override fun getFilesDir(): File = envDir
            override fun getApplicationContext(): Context = this

            override fun openOrCreateDatabase(
                name: String?,
                mode: Int,
                factory: android.database.sqlite.SQLiteDatabase.CursorFactory?,
            ) = super.openOrCreateDatabase(File(envDir, name ?: XxDatabase.NAME).absolutePath, mode, factory)

            override fun openOrCreateDatabase(
                name: String?,
                mode: Int,
                factory: android.database.sqlite.SQLiteDatabase.CursorFactory?,
                errorHandler: android.database.DatabaseErrorHandler?,
            ) = super.openOrCreateDatabase(
                File(envDir, name ?: XxDatabase.NAME).absolutePath,
                mode,
                factory,
                errorHandler,
            )
        }
        return object : ContextWrapper(appContext) {
            override fun getApplicationContext(): Context = appContext
        }
    }
}
