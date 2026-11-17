package com.cyeam.cnote.sdk

/** Protocol v1: the same JSON commands and events used by the desktop editor. Call off the UI thread. */
object NativeCore {
    init { System.loadLibrary("cnote_core") }
    @Synchronized external fun open(database:String,legacyDatabase:String,downloads:String,vault:SecureVault)
    external fun dispatch(command:String)
    external fun poll():String
}
