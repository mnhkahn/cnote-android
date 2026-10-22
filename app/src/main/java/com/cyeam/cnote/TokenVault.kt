package com.cyeam.cnote

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONObject
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

data class Tokens(val access:String,val refresh:String,val expires:Long)
/** Only an authenticated AES-GCM blob is stored on disk; its key never leaves Android Keystore. */
class TokenVault(context:Context,private val name:String="oauth-vault") {
    private val preferences=context.getSharedPreferences(name,Context.MODE_PRIVATE)
    private val alias="com.cyeam.cnote.$name"
    private fun key():SecretKey {
        val store=KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias,null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore").run {
            init(KeyGenParameterSpec.Builder(alias,KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT).setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build());generateKey()
        }
    }
    @Synchronized fun read():Tokens? {
        val encoded=preferences.getString("session",null)?:return null
        val bytes=Base64.decode(encoded,Base64.NO_WRAP);require(bytes.size>12)
        val cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.DECRYPT_MODE,key(),GCMParameterSpec(128,bytes.copyOfRange(0,12)))
        val json=JSONObject(String(cipher.doFinal(bytes.copyOfRange(12,bytes.size)),Charsets.UTF_8))
        return Tokens(json.getString("access"),json.getString("refresh"),json.getLong("expires"))
    }
    @Synchronized fun write(tokens:Tokens) {
        val json=JSONObject().put("access",tokens.access).put("refresh",tokens.refresh).put("expires",tokens.expires).toString()
        val cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.ENCRYPT_MODE,key())
        val blob=cipher.iv+cipher.doFinal(json.toByteArray())
        check(preferences.edit().putString("session",Base64.encodeToString(blob,Base64.NO_WRAP)).commit()) { "登录凭据保存失败" }
    }
    @Synchronized fun clear() { check(preferences.edit().clear().commit()) { "清除凭据失败" } }
}
