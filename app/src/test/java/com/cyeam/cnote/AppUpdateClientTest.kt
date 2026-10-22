package com.cyeam.cnote

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.net.URL
import java.time.Instant

class AppUpdateClientTest {
    private val endpoint=URL("https://www.cyeam.com/api/apps/cnote/update?versionCode=1000")
    private fun response()=JSONObject().put("appId","cnote").put("hasUpdate",true).put("packageName","com.cyeam.cnote").put("versionName","0.1.1").put("versionCode",1001)
        .put("sizeBytes",1400000).put("releaseNotes","更新说明").put("expiresAt",Instant.now().plusSeconds(900).toString()).put("downloadUrl","/api/apps/cnote/download?token=test")
    private fun parse(value:JSONObject)=AppUpdateClient.parseGateway(value.toString(),endpoint,1000,"com.cyeam.cnote")
    private fun mirror()=response().put("tag","v0.1.1").put("sha256","a".repeat(64)).put("downloadUrl","https://github.com/mnhkahn/cnote-android/releases/download/v0.1.1/CNote-release.apk")
    private fun parseMirror(value:JSONObject)=AppUpdateClient.parseMirror(value.toString(),1000,"com.cyeam.cnote")
    @Test fun absentUpdateNeedsNoDownloadMetadata(){assertNull(parse(JSONObject().put("hasUpdate",false)))}
    @Test fun gatewayUsesRelativeUrlAndApkVersionCode(){val update=parse(response())!!;assertEquals(1001L,update.versionCode);assertEquals("https://www.cyeam.com/api/apps/cnote/download?token=test",update.downloadUrl)}
    @Test fun rejectsWrongApplicationAndInvalidSizes(){for((field,value)in listOf("appId" to "homework","packageName" to "com.homeworkbuddy","versionCode" to 1000,"versionCode" to 999,"sizeBytes" to 0,"sizeBytes" to 300_000_000)){assertThrows(IllegalArgumentException::class.java){parse(response().put(field,value))}}}
    @Test fun rejectsUntrustedGatewayUrls(){for(url in listOf("http://www.cyeam.com/api/apps/cnote/download","https://evil.example/api/apps/cnote/download","https://user@www.cyeam.com/api/apps/cnote/download","https://www.cyeam.com:8443/api/apps/cnote/download","/api/apps/homework/download","/api/apps/cnote/download#fragment")){assertThrows(IllegalArgumentException::class.java){parse(response().put("downloadUrl",url))}}}
    @Test fun rejectsExpiredGatewayToken(){assertThrows(IllegalArgumentException::class.java){parse(response().put("expiresAt",Instant.now().minusSeconds(5).toString()))}}
    @Test fun publicMirrorHasDigestAndExactRepository(){val update=parseMirror(mirror())!!;assertEquals("a".repeat(64),update.sha256);assertEquals(1001L,update.versionCode)}
    @Test fun mirrorIgnoresInstalledOrOlderReleases(){assertNull(parseMirror(mirror().put("versionCode",1000)));assertNull(parseMirror(mirror().put("versionCode",999)))}
    @Test fun mirrorRejectsForeignAssetsAndInvalidDigests(){for(url in listOf("https://github.com/attacker/cnote-android/releases/download/v0.1.1/CNote-release.apk","https://github.com/mnhkahn/cnote-android/releases/download/v0.1.0/CNote-release.apk","http://github.com/mnhkahn/cnote-android/releases/download/v0.1.1/CNote-release.apk","https://github.com/mnhkahn/cnote-android/releases/download/v0.1.1/CNote-release.apk?redirect=bad")){assertThrows(IllegalArgumentException::class.java){parseMirror(mirror().put("downloadUrl",url))}};assertThrows(IllegalArgumentException::class.java){parseMirror(mirror().put("sha256","bad"))}}
}
