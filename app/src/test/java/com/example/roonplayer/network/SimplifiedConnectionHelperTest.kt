package com.example.roonplayer.network

import org.junit.Assert.assertEquals
import org.junit.Test

class SimplifiedConnectionHelperTest {

    private val helper = SimplifiedConnectionHelper(
        connectionValidator = RoonConnectionValidator(defaultPort = 9330, defaultTimeoutMs = 1000),
        defaultPort = 9330
    )

    @Test
    fun `parseHostAndPort supports bracketed ipv6 with port`() {
        assertEquals("::1" to 9330, invokeParseHostAndPort("[::1]:9330"))
    }

    @Test
    fun `parseHostAndPort supports bare ipv6 without port`() {
        assertEquals("2001:db8::5" to 0, invokeParseHostAndPort("2001:db8::5"))
    }

    @Test
    fun `parseHostAndPort supports host with port`() {
        assertEquals("roon.local" to 9100, invokeParseHostAndPort("roon.local:9100"))
    }

    private fun invokeParseHostAndPort(input: String): Pair<String, Int> {
        val method = SimplifiedConnectionHelper::class.java.getDeclaredMethod(
            "parseHostAndPort",
            String::class.java
        )
        method.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        return method.invoke(helper, input) as Pair<String, Int>
    }
}
