package com.cyeam.cnote

import org.junit.Assert.*
import org.junit.Test

class NoteTitleTest {
    @Test fun firstNonemptyBodyHeadingIsTheLabel() {
        assertEquals("AI各种琢磨 & 笔记",NoteTitle.heading("<title>文件标题</title><h1> </h1><H2>AI<strong>各种</strong>琢磨 &amp; 笔记</H2><h1>后面的标题</h1>"))
        assertEquals("第一行 第二行 ✓",NoteTitle.heading("<p>前言</p><h3> 第一行<br> 第二行&nbsp; &#x2713; </h3>"))
        assertEquals("没有闭合的标题",NoteTitle.heading("<h2>没有闭合的标题"))
        assertEquals("",NoteTitle.heading("<p>只有正文</p>"))
    }
    @Test fun nonContentTagsCannotSupplyTheLabel() {
        assertEquals("正文标题",NoteTitle.heading("<!-- <h1>注释</h1> --><script>const x='<h1>伪标题</h1>';</script><template><h1>模板</h1></template><svg><text><h1>SVG</h1></text></svg><h2>正文标题</h2>"))
    }
}
