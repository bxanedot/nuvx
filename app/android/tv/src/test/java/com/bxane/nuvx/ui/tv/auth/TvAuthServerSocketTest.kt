package com.bxane.nuvx.ui.tv.auth

import org.junit.Assert.assertEquals
import org.junit.Test
import java.net.ServerSocket
import java.net.Socket

class TvAuthServerSocketTest {

    @Test
    fun clientsBeyondTheConnectionLimitAreClosed() {
        ServerSocket(0).use { server ->
            val clients = List(5) {
                Socket("127.0.0.1", server.localPort).apply { soTimeout = 1500 }
            }
            val accepted = List(clients.size) { server.accept() }
            val method = TvAuthServer.javaClass.getDeclaredMethod("handleClient", Socket::class.java, Function1::class.java)
            method.isAccessible = true
            try {
                accepted.forEach { method.invoke(TvAuthServer, it, null) }

                assertEquals(-1, clients.last().getInputStream().read())
            } finally {
                clients.forEach { it.close() }
                accepted.forEach { it.close() }
            }
        }
    }

    @Test
    fun stalledRequestLineTimesOut() {
        ServerSocket(0).use { server ->
            val client = Socket("127.0.0.1", server.localPort).apply { soTimeout = 7000 }
            val accepted = server.accept()
            val method = TvAuthServer.javaClass.getDeclaredMethod("handleClient", Socket::class.java, Function1::class.java)
            method.isAccessible = true
            try {
                method.invoke(TvAuthServer, accepted, null)

                assertEquals(-1, client.getInputStream().read())
            } finally {
                client.close()
                accepted.close()
            }
        }
    }
}
