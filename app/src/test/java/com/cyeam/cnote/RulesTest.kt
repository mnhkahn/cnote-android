package com.cyeam.cnote

import org.junit.Assert.*
import org.junit.Test

class RulesTest {
    @Test fun titlesAreSafeAndCompatibleWithDesktop() {
        assertEquals("测试笔记",Rules.title(" 测试笔记.html "))
        listOf("","../escape","a/b","a\\b","bad:name","bad\nname","文".repeat(54)).forEach { assertThrows(IllegalArgumentException::class.java) { Rules.title(it) } }
        val n=Note(id="12345678-abcd",title="我的笔记")
        assertEquals("我的笔记--12345678.html",Rules.filename(n));assertEquals(n.title,Rules.displayTitle(Rules.filename(n)))
    }
    @Test fun uploadAcknowledgementNeverOverwritesNewerDraftOrDeletion() {
        val sent=Note(title="draft",html="old",revision=4)
        val newer=sent.copy(html="new",revision=5)
        val ack=Rules.acknowledge(newer,sent,"remote","v1","account")
        assertEquals("new",ack.html);assertEquals("old",ack.baseline);assertTrue(ack.dirty)
        assertTrue(Rules.acknowledge(sent.copy(deleted=true),sent,"remote","v1","account").dirty)
        assertFalse(Rules.acknowledge(sent,sent,"remote","v1","account").dirty)
    }
}
