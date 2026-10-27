package com.cyeam.cnote

import java.util.UUID

data class Note(
    val id:String=UUID.randomUUID().toString(),val account:String="",val remoteId:String?=null,
    val title:String,val html:String="<p><br></p>",val baseline:String="",val etag:String?=null,
    val revision:Long=0,val dirty:Boolean=true,val deleted:Boolean=false,val conflict:Boolean=false,
    val modified:Long=System.currentTimeMillis(),val loaded:Boolean=true,val approvedRevision:Long = -1,
    // Legacy approval fields remain readable for database compatibility; they no longer gate sync.
    val needsApproval:Boolean=false,val conflictHtml:String?=null,val conflictEtag:String?=null,val remoteMissing:Boolean=false
)
data class Summary(val id:String,val title:String,val modified:Long,val dirty:Boolean,val conflict:Boolean,val needsApproval:Boolean)
data class Session(val id:String="",val name:String="",val authenticated:Boolean=false)
data class UiState(val notes:List<Summary> = emptyList(),val session:Session=Session(),val selected:String?=null,val status:String="离线可用 · 笔记保存在本机",val busy:Boolean=false)

object Rules {
    const val MAX_HTML=20*1024*1024
    fun title(value:String):String {
        val name=value.trim().removeSuffix(".html")
        require(name.isNotEmpty()&&name.toByteArray().size<=160&&!name.any { it.isISOControl()||it in "\\/:*?\"<>|" }) { "标题不能为空，最多 160 字节，不能包含文件路径字符" }
        return name
    }
    fun displayTitle(value:String)=value.removeSuffix(".html").replace(Regex("--[a-zA-Z0-9]{8}$"),"")
    fun filename(note:Note)="${note.title}--${note.id.take(8)}.html"
    fun acknowledge(current:Note,sent:Note,remoteId:String,etag:String,account:String)=current.copy(
        account=account,remoteId=remoteId,etag=etag,baseline=sent.html,dirty=current.revision!=sent.revision||current.deleted,
        needsApproval=false
    )
}
