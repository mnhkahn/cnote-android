package com.cyeam.cnote.sdk

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Values are encrypted with a non-exportable Android Keystore key; JNI never sees the key. */
class SecureVault(context:Context) {
    private val prefs=context.getSharedPreferences("shared-core-vault",Context.MODE_PRIVATE)
    private fun key():SecretKey {
        val store=KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey("com.cyeam.cnote.shared",null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore").run {
            init(KeyGenParameterSpec.Builder("com.cyeam.cnote.shared",KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT).setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build());generateKey()
        }
    }
    @Synchronized fun get(name:String):String? {
        val blob=prefs.getString(name,null)?.let { Base64.decode(it,Base64.NO_WRAP) }?:return null
        require(blob.size>12) { "登录凭据损坏" }
        val cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.DECRYPT_MODE,key(),GCMParameterSpec(128,blob.copyOfRange(0,12)))
        return String(cipher.doFinal(blob.copyOfRange(12,blob.size)),Charsets.UTF_8)
    }
    @Synchronized fun set(name:String,value:String?) {
        if(value==null){check(prefs.edit().remove(name).commit()) { "删除凭据失败" };return}
        val cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.ENCRYPT_MODE,key())
        val blob=cipher.iv+cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        check(prefs.edit().putString(name,Base64.encodeToString(blob,Base64.NO_WRAP)).commit()) { "凭据保存失败" }
    }
}
