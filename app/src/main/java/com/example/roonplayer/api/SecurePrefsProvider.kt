package com.example.roonplayer.api

import android.content.Context
import android.content.SharedPreferences
import android.util.Log

/**
 * Returns encrypted SharedPreferences when androidx.security is available,
 * otherwise falls back to the provided plain preferences.
 */
class SecurePrefsProvider(
    private val context: Context,
    private val fallbackPreferences: SharedPreferences
) {
    @Volatile
    private var secureUnavailableLogged = false

    fun getSecurePreferences(
        fileName: String = DEFAULT_SECURE_PREFS_FILE
    ): SharedPreferences {
        val securePrefs = createEncryptedSharedPreferences(context, fileName)
        if (securePrefs != null) {
            return securePrefs
        }

        if (!secureUnavailableLogged) {
            secureUnavailableLogged = true
            Log.w(TAG, "EncryptedSharedPreferences unavailable, fallback to plain SharedPreferences")
        }
        return fallbackPreferences
    }

    private fun createEncryptedSharedPreferences(
        context: Context,
        fileName: String
    ): SharedPreferences? {
        return try {
            val masterKeyClass = Class.forName("androidx.security.crypto.MasterKey")
            val keySchemeClass = Class.forName("androidx.security.crypto.MasterKey\$KeyScheme")
            val builderClass = Class.forName("androidx.security.crypto.MasterKey\$Builder")

            val builder = builderClass.getConstructor(Context::class.java).newInstance(context)
            val keyScheme = enumValue(
                enumClass = keySchemeClass,
                constantName = "AES256_GCM"
            )
            builderClass.getMethod("setKeyScheme", keySchemeClass).invoke(builder, keyScheme)
            val masterKey = builderClass.getMethod("build").invoke(builder)

            val encryptedPrefsClass = Class.forName("androidx.security.crypto.EncryptedSharedPreferences")
            val keyEncryptionClass = Class.forName(
                "androidx.security.crypto.EncryptedSharedPreferences\$PrefKeyEncryptionScheme"
            )
            val valueEncryptionClass = Class.forName(
                "androidx.security.crypto.EncryptedSharedPreferences\$PrefValueEncryptionScheme"
            )

            val keyEncryptionScheme = enumValue(
                enumClass = keyEncryptionClass,
                constantName = "AES256_SIV"
            )
            val valueEncryptionScheme = enumValue(
                enumClass = valueEncryptionClass,
                constantName = "AES256_GCM"
            )

            val createMethod = encryptedPrefsClass.getMethod(
                "create",
                Context::class.java,
                String::class.java,
                masterKeyClass,
                keyEncryptionClass,
                valueEncryptionClass
            )

            createMethod.invoke(
                null,
                context,
                fileName,
                masterKey,
                keyEncryptionScheme,
                valueEncryptionScheme
            ) as? SharedPreferences
        } catch (e: Throwable) {
            Log.w(TAG, "Failed to create encrypted SharedPreferences: ${e.message}")
            null
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun enumValue(
        enumClass: Class<*>,
        constantName: String
    ): Any {
        val typedEnumClass = enumClass as Class<out Enum<*>>
        return java.lang.Enum.valueOf(typedEnumClass, constantName)
    }

    companion object {
        private const val TAG = "SecurePrefsProvider"
        private const val DEFAULT_SECURE_PREFS_FILE = "CoverArtSecure"
    }
}
