package com.example.EdgeMemo.testing

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress

/**
 * Tiny in-test HTTP backend for exercising the real Android HTTP remotes
 * against deterministic responses (never a substitute for the real EdgeMind
 * backend — that lives in backend/ with its own test suite).
 */
class LocalHttpBackend private constructor(private val server: HttpServer) {

    val baseUrl: String
        get() = "http://127.0.0.1:${server.address.port}"

    fun close() {
        server.stop(0)
    }

    companion object {
        fun start(handler: (HttpExchange) -> Unit): LocalHttpBackend {
            val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
            server.createContext("/") { exchange ->
                try {
                    handler(exchange)
                } finally {
                    exchange.close()
                }
            }
            server.start()
            return LocalHttpBackend(server)
        }
    }
}

fun HttpExchange.respond(status: Int, body: String) {
    val bytes = body.toByteArray(Charsets.UTF_8)
    responseHeaders.add("Content-Type", "application/json")
    sendResponseHeaders(status, bytes.size.toLong())
    responseBody.use { it.write(bytes) }
}

fun HttpExchange.readBody(): String =
    requestBody.bufferedReader(Charsets.UTF_8).use { it.readText() }

fun HttpExchange.requestPath(): String = requestURI.path

/** Full path + query string (what the client actually sent). */
fun HttpExchange.requestUri(): String = requestURI.toString()
