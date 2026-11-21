package com.cyeam.cnote

import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import android.content.Context
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class HomeworkDownloadsMediaStoreTest {
    @Test fun repeatedDownloadReusesThePublicEntryAndTruncatesOldBytes() {
        assumeTrue(Build.VERSION.SDK_INT>=29)
        val context=ApplicationProvider.getApplicationContext<Context>()
        val resolver=context.contentResolver
        val name="cnote-download-test-${UUID.randomUUID()}.jpg"
        val created=mutableSetOf<Uri>()
        try {
            val first=Uri.parse(HomeworkDownloads.save(context,name,"image/jpeg","original bytes".toByteArray())).also { created.add(it) }
            val second=Uri.parse(HomeworkDownloads.save(context,name,"image/jpeg","new".toByteArray())).also { created.add(it) }
            assertEquals(first,second)
            assertEquals("new",resolver.openInputStream(second)!!.bufferedReader().use { it.readText() })
            resolver.query(MediaStore.Downloads.EXTERNAL_CONTENT_URI,arrayOf(MediaStore.Downloads._ID),
                "${MediaStore.Downloads.DISPLAY_NAME} = ? AND ${MediaStore.Downloads.RELATIVE_PATH} = ?",
                arrayOf(name,Environment.DIRECTORY_DOWNLOADS+"/CNote/"),null)!!.use { assertEquals(1,it.count) }
        } finally { created.forEach { resolver.delete(it,null,null) } }
    }
}
