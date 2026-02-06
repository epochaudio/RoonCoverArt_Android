package com.example.roonplayer.api

import android.content.SharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectionHistoryRepositoryTest {

    @Test
    fun `saveSuccessfulConnection persists and increments success count`() {
        val prefs = FakeSharedPreferences()
        val repo = ConnectionHistoryRepository(prefs)
        val ip = "192.168.1.10"
        val port = 9330

        val first = repo.saveSuccessfulConnection(
            ip = ip,
            port = port,
            isValidHost = { true },
            now = 1000L
        )
        val second = repo.saveSuccessfulConnection(
            ip = ip,
            port = port,
            isValidHost = { true },
            now = 2000L
        )

        assertEquals(1, first?.successCount)
        assertEquals(2, second?.successCount)
        assertEquals(2000L, second?.savedAt)
        assertEquals(2, prefs.getInt("roon_successful_${ip}_port_${port}_count", 0))
        assertEquals(2000L, prefs.getLong("roon_successful_${ip}_port_${port}_time", 0L))

        val lastState = repo.getLastSuccessfulConnectionState()
        assertEquals(ip, lastState.host)
        assertEquals(port, lastState.port)
        assertEquals(2000L, lastState.lastConnectionTime)
    }

    @Test
    fun `saveSuccessfulConnection rejects invalid host`() {
        val prefs = FakeSharedPreferences()
        val repo = ConnectionHistoryRepository(prefs)

        val result = repo.saveSuccessfulConnection(
            ip = "by_core_id_xxx",
            port = 9330,
            isValidHost = { host -> !host.startsWith("by_core_id_") },
            now = 1000L
        )

        assertNull(result)
        assertFalse(prefs.contains("last_successful_host"))
    }

    @Test
    fun `getSavedSuccessfulConnections returns latest first`() {
        val prefs = FakeSharedPreferences(
            mutableMapOf(
                "roon_successful_192.168.1.10_port_9330_time" to 100L,
                "roon_successful_192.168.1.20_port_9331_time" to 300L
            )
        )
        val repo = ConnectionHistoryRepository(prefs)

        val connections = repo.getSavedSuccessfulConnections(isValidHost = { true })

        assertEquals(2, connections.size)
        assertEquals("192.168.1.20" to 9331, connections[0])
        assertEquals("192.168.1.10" to 9330, connections[1])
    }

    @Test
    fun `getPrioritizedConnections sorts by success count then recency`() {
        val prefs = FakeSharedPreferences(
            mutableMapOf(
                "roon_successful_192.168.1.10_port_9330_time" to 100L,
                "roon_successful_192.168.1.10_port_9330_count" to 2,
                "roon_successful_192.168.1.20_port_9330_time" to 50L,
                "roon_successful_192.168.1.20_port_9330_count" to 5,
                "roon_successful_192.168.1.30_port_9331_time" to 300L,
                "roon_successful_192.168.1.30_port_9331_count" to 5
            )
        )
        val repo = ConnectionHistoryRepository(prefs)

        val prioritized = repo.getPrioritizedConnections(isValidHost = { true })

        assertEquals(3, prioritized.size)
        assertEquals("192.168.1.30", prioritized[0].ip)
        assertEquals("192.168.1.20", prioritized[1].ip)
        assertEquals("192.168.1.10", prioritized[2].ip)
    }

    @Test
    fun `cleanupOldConnections removes stale time and count keys`() {
        val prefs = FakeSharedPreferences(
            mutableMapOf(
                "roon_successful_192.168.1.10_port_9330_time" to 8000L,
                "roon_successful_192.168.1.10_port_9330_count" to 3,
                "roon_successful_192.168.1.20_port_9330_time" to 9800L,
                "roon_successful_192.168.1.20_port_9330_count" to 1
            )
        )
        val repo = ConnectionHistoryRepository(prefs)

        val removed = repo.cleanupOldConnections(
            retentionMs = 1000L,
            now = 10000L
        )

        assertEquals(1, removed)
        assertFalse(prefs.contains("roon_successful_192.168.1.10_port_9330_time"))
        assertFalse(prefs.contains("roon_successful_192.168.1.10_port_9330_count"))
        assertTrue(prefs.contains("roon_successful_192.168.1.20_port_9330_time"))
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
