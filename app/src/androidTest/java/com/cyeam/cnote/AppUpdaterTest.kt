package com.cyeam.cnote

import android.content.Context
import androidx.core.content.FileProvider
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

@RunWith(AndroidJUnit4::class)
class AppUpdaterTest {
    private val context=ApplicationProvider.getApplicationContext<CNoteApp>()
    @Test fun realApkVerifiesMatchingPackageSignatureVersionAndDigest() {
        val apk=File(context.applicationInfo.sourceDir)
        val hash=MessageDigest.getInstance("SHA-256").digest(apk.readBytes()).joinToString(""){"%02x".format(it)}
        verifyUpdateApk(context,apk,apk.length(),BuildConfig.VERSION_CODE.toLong(),hash,BuildConfig.VERSION_CODE-1L)
        assertThrows(IllegalStateException::class.java){verifyUpdateApk(context,apk,apk.length()-1,BuildConfig.VERSION_CODE.toLong(),hash,BuildConfig.VERSION_CODE-1L)}
        assertThrows(IllegalStateException::class.java){verifyUpdateApk(context,apk,apk.length(),BuildConfig.VERSION_CODE.toLong(),"0".repeat(64),BuildConfig.VERSION_CODE-1L)}
        assertThrows(IllegalStateException::class.java){verifyUpdateApk(context,apk,apk.length(),BuildConfig.VERSION_CODE.toLong(),null)}
    }
    @Test fun rejectsAnotherRealPackageAndOnlySharesTheUpdatesDirectory() {
        val apk=File(InstrumentationRegistry.getInstrumentation().context.applicationInfo.sourceDir)
        assertThrows(IllegalStateException::class.java){verifyUpdateApk(context,apk,apk.length(),1,null,0)}
        val inside=File(context.getExternalFilesDir(android.os.Environment.DIRECTORY_DOWNLOADS),"updates/test.apk").apply { parentFile!!.mkdirs();writeText("test") }
        try { val uri=FileProvider.getUriForFile(context,"${context.packageName}.updates",inside);assertEquals("content",uri.scheme)
            assertThrows(IllegalArgumentException::class.java){FileProvider.getUriForFile(context,"${context.packageName}.updates",File(context.filesDir,"notes.db"))}
        }finally { inside.delete() }
    }
    @Test fun updateChecksDoNotBlockUiAndConcurrentChecksCoalesce() {
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO);val entered=CountDownLatch(1);val release=CountDownLatch(1);val calls=AtomicInteger()
        context.getSharedPreferences("app_updates",Context.MODE_PRIVATE).edit().clear().commit()
        val updater=AppUpdater(context,scope) { calls.incrementAndGet();entered.countDown();release.await(10,TimeUnit.SECONDS);null }
        try {
            InstrumentationRegistry.getInstrumentation().runOnMainSync { updater.checkForUpdate(true) }
            assertTrue(entered.await(5,TimeUnit.SECONDS));updater.checkForUpdate(true);Thread.sleep(100);assertEquals(1,calls.get());release.countDown()
            val until=System.nanoTime()+TimeUnit.SECONDS.toNanos(5)
            while(updater.state.value.message!="已是最新版本"&&System.nanoTime()<until)Thread.sleep(20)
            assertEquals(UpdateStage.IDLE,updater.state.value.stage);assertEquals("已是最新版本",updater.state.value.message)
        }finally { release.countDown();scope.cancel() }
    }
}
