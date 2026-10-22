package com.cyeam.cnote

import android.content.Context
import android.os.Bundle
import com.google.firebase.analytics.FirebaseAnalytics

class Analytics(private val context:Context) {
    private val prefs=context.getSharedPreferences("preferences",Context.MODE_PRIVATE)
    private val firebase by lazy { FirebaseAnalytics.getInstance(context) }
    fun initialize() { firebase.setAnalyticsCollectionEnabled(enabled());firebase.setDefaultEventParameters(Bundle().apply { putString("app","cnote_android");putString("platform","android");putString("app_version",BuildConfig.VERSION_NAME) }) }
    fun enabled()=prefs.getBoolean("analytics",!BuildConfig.DEBUG)
    fun enabled(value:Boolean) { prefs.edit().putBoolean("analytics",value).apply();firebase.setAnalyticsCollectionEnabled(value) }
    fun noteCreated()=event("note_created")
    fun noteDeleted()=event("note_deleted")
    fun login(success:Boolean)=event("login_completed",Bundle().apply { putString("outcome",if(success)"success" else "failure") })
    fun sync(operation:String,outcome:String,duration:Long)=event("sync_completed",Bundle().apply { putString("operation",operation);putString("outcome",outcome);putLong("duration_ms",duration) })
    fun screen(name:String)=event(FirebaseAnalytics.Event.SCREEN_VIEW,Bundle().apply { putString(FirebaseAnalytics.Param.SCREEN_NAME,name);putString(FirebaseAnalytics.Param.SCREEN_CLASS,"MainActivity") })
    private fun event(name:String,parameters:Bundle?=null) { if(enabled())firebase.logEvent(name,parameters) }
}
