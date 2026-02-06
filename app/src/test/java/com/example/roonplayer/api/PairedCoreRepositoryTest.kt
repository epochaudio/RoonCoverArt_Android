package com.example.roonplayer.api

import android.content.SharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PairedCoreRepositoryTest {

    @Test
    fun `loadPairedCores returns legacy host-based records`() {
        val prefs = FakeSharedPreferences(
            mutableMapOf(
                "roon_core_token_192.168.1.10:9330" to "token_a",
                "roon_core_id_192.168.1.10:9330" to "core_a",
                "roon_last_connected_192.168.1.10:9330" to 100L
            )
        )
        val repo = PairedCoreRepository(prefs)

        val records = repo.loadPairedCores(
            defaultPort = 9330,
            isValidHost = { true },
            fallbackLastSuccessful = null
        )

        assertEquals(1, records.size)
        val record = records["192.168.1.10:9330"]
        assertEquals("192.168.1.10", record?.host)
        assertEquals(9330, record?.port)
        assertEquals("token_a", record?.token)
        assertEquals("core_a", record?.coreId)
        assertEquals(100L, record?.lastConnected)
    }

    @Test
    fun `loadPairedCores resolves core-id token to latest mapped host`() {
        val prefs = FakeSharedPreferences(
            mutableMapOf(
                "roon_core_token_by_core_id_core_a" to "token_a",
                "roon_last_connected_by_core_id_core_a" to 300L,
                "roon_core_id_192.168.1.10:9330" to "core_a",
                "roon_last_connected_192.168.1.10:9330" to 100L,
                "roon_core_id_192.168.1.20:9331" to "core_a",
                "roon_last_connected_192.168.1.20:9331" to 200L
            )
        )
        val repo = PairedCoreRepository(prefs)

        val records = repo.loadPairedCores(
            defaultPort = 9330,
            isValidHost = { true },
            fallbackLastSuccessful = null
        )

        assertEquals(1, records.size)
        val record = records["192.168.1.20:9331"]
        assertEquals("192.168.1.20", record?.host)
        assertEquals(9331, record?.port)
        assertEquals("core_a", record?.coreId)
        assertEquals(300L, record?.lastConnected)
    }

    @Test
    fun `loadPairedCores falls back to last successful host when core mapping missing`() {
        val prefs = FakeSharedPreferences(
            mutableMapOf(
                "roon_core_token_by_core_id_core_b" to "token_b"
            )
        )
        val repo = PairedCoreRepository(prefs)

        val records = repo.loadPairedCores(
            defaultPort = 9330,
            isValidHost = { true },
            fallbackLastSuccessful = LastSuccessfulConnectionState(
                host = "192.168.1.99",
                port = 9330,
                lastConnectionTime = 500L
            )
        )

        assertEquals(1, records.size)
        val record = records["192.168.1.99:9330"]
        assertEquals("token_b", record?.token)
        assertEquals("core_b", record?.coreId)
        assertEquals(500L, record?.lastConnected)
    }

    @Test
    fun `migrateLegacyTokenToCoreId moves host-based token to core-id keys`() {
        val prefs = FakeSharedPreferences(
            mutableMapOf(
                "roon_core_token_192.168.1.10:9330" to "legacy_token",
                "roon_last_connected_192.168.1.10:9330" to 700L
            )
        )
        val repo = PairedCoreRepository(prefs)

        val status = repo.migrateLegacyTokenToCoreId(
            coreId = "core_a",
            hostInput = "192.168.1.10:9330"
        )

        assertEquals(TokenMigrationStatus.MIGRATED, status)
        assertEquals("legacy_token", prefs.getString("roon_core_token_by_core_id_core_a", null))
        assertEquals(700L, prefs.getLong("roon_last_connected_by_core_id_core_a", 0L))
        assertFalse(prefs.contains("roon_core_token_192.168.1.10:9330"))
        assertFalse(prefs.contains("roon_last_connected_192.168.1.10:9330"))
    }

    @Test
    fun `saveRegistrationToken uses core-id key and cleans legacy keys`() {
        val prefs = FakeSharedPreferences(
            mutableMapOf(
                "roon_core_id_192.168.1.10:9330" to "core_a",
                "roon_core_token_192.168.1.10:9330" to "legacy_token",
                "roon_last_connected_192.168.1.10:9330" to 100L
            )
        )
        val repo = PairedCoreRepository(prefs)

        val result = repo.saveRegistrationToken(
            hostInput = "192.168.1.10:9330",
            token = "new_token",
            connectedAt = 900L
        )

        assertEquals("core_a", result.coreId)
        assertEquals("new_token", prefs.getString("roon_core_token_by_core_id_core_a", null))
        assertEquals(900L, prefs.getLong("roon_last_connected_by_core_id_core_a", 0L))
        assertFalse(prefs.contains("roon_core_token_192.168.1.10:9330"))
        assertFalse(prefs.contains("roon_last_connected_192.168.1.10:9330"))
        assertEquals("new_token", repo.getSavedToken("192.168.1.10:9330"))
    }

    @Test
    fun `getSavedToken returns null when neither core-id nor host token exists`() {
        val repo = PairedCoreRepository(FakeSharedPreferences())
        assertNull(repo.getSavedToken("192.168.1.10:9330"))
    }

    private class FakeSharedPreferences(
        private val data: MutableMap<String, Any?> = mutableMapOf()
    ) : SharedPreferences {

        override fun getAll(): MutableMap<String, *> = data.toMutableMap()

        override fun getString(key: String?, defValue: String?): String? {
            if (key == null) return defValue
            return data[key] as? String ?: defValue
        }

        override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? {
            if (key == null) return defValues
            @Suppress("UNCHECKED_CAST")
            return (data[key] as? MutableSet<String>) ?: defValues
        }

        override fun getInt(key: String?, defValue: Int): Int {
            if (key == null) return defValue
            return data[key] as? Int ?: defValue
        }

        override fun getLong(key: String?, defValue: Long): Long {
            if (key == null) return defValue
            return data[key] as? Long ?: defValue
        }

        override fun getFloat(key: String?, defValue: Float): Float {
            if (key == null) return defValue
            return data[key] as? Float ?: defValue
        }

        override fun getBoolean(key: String?, defValue: Boolean): Boolean {
            if (key == null) return defValue
            return data[key] as? Boolean ?: defValue
        }

        override fun contains(key: String?): Boolean {
            if (key == null) return false
            return data.containsKey(key)
        }

        override fun edit(): SharedPreferences.Editor = EditorImpl(data)

        override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {
            // No-op for unit tests.
        }

        override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {
            // No-op for unit tests.
        }

        private class EditorImpl(
            private val data: MutableMap<String, Any?>
        ) : SharedPreferences.Editor {
            private val pending = mutableMapOf<String, Any?>()
            private var clearRequested = false

            override fun putString(key: String?, value: String?): SharedPreferences.Editor {
                if (key != null) pending[key] = value
                return this
            }

            override fun putStringSet(key: String?, values: MutableSet<String>?): SharedPreferences.Editor {
                if (key != null) pending[key] = values
                return this
            }

            override fun putInt(key: String?, value: Int): SharedPreferences.Editor {
                if (key != null) pending[key] = value
                return this
            }

            override fun putLong(key: String?, value: Long): SharedPreferences.Editor {
                if (key != null) pending[key] = value
                return this
            }

            override fun putFloat(key: String?, value: Float): SharedPreferences.Editor {
                if (key != null) pending[key] = value
                return this
            }

            override fun putBoolean(key: String?, value: Boolean): SharedPreferences.Editor {
                if (key != null) pending[key] = value
                return this
            }

            override fun remove(key: String?): SharedPreferences.Editor {
                if (key != null) pending[key] = null
                return this
            }

            override fun clear(): SharedPreferences.Editor {
                clearRequested = true
                return this
            }

            override fun commit(): Boolean {
                apply()
                return true
            }

            override fun apply() {
                if (clearRequested) {
                    data.clear()
                    clearRequested = false
                }

                pending.forEach { (key, value) ->
                    if (value == null) {
                        data.remove(key)
                    } else {
                        data[key] = value
                    }
                }
                pending.clear()
            }
        }
    }
}
