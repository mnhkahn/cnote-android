package com.cyeam.cnote

import org.jsoup.Jsoup

/** A display label only: body headings never rename the OneDrive file. */
object NoteTitle {
    fun heading(html:String):String {
        val document=Jsoup.parse(html)
        document.select("script,style,template,svg,math,iframe,object").remove()
        return document.select("h1,h2,h3,h4,h5,h6").asSequence()
            .map { it.text().replace(Regex("[\\s\\p{Z}]+")," ").trim() }
            .firstOrNull { it.isNotEmpty() }.orEmpty()
    }
}
