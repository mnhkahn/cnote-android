package com.cyeam.cnote

import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test

class HomeworkDownloadsTest {
    @Test fun repeatedDownloadReplacesAndTruncatesTheSameFile() {
        val directory=Files.createTempDirectory("homework-download-test").toFile()
        try {
            val first=HomeworkDownloads.replaceFile(directory,"作业_图一.jpg","original bytes".toByteArray())
            val second=HomeworkDownloads.replaceFile(directory,"作业_图一.jpg","new".toByteArray())
            assertEquals(first,second)
            assertEquals("new",second.readText())
            assertEquals(1,directory.listFiles()!!.size)
            val blocked=directory.resolve("blocked.jpg").apply { mkdir() }
            blocked.resolve("keep").writeText("keep")
            assertThrows(Exception::class.java) { HomeworkDownloads.replaceFile(directory,"blocked.jpg",byteArrayOf(1)) }
            assertEquals("keep",blocked.resolve("keep").readText())
            assertEquals(2,directory.listFiles()!!.size)
        } finally { directory.deleteRecursively() }
    }
}
