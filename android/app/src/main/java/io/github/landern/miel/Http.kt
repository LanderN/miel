package io.github.landern.miel

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InterruptedIOException
import java.net.HttpURLConnection
import java.net.URL

internal fun fetchBytes(url: String, limit: Int, timeoutMillis: Int = 6000): ByteArray {
    val connection = URL(url).openConnection() as HttpURLConnection
    connection.connectTimeout = timeoutMillis
    connection.readTimeout = timeoutMillis
    connection.setRequestProperty("User-Agent", "miel/0.1")
    connection.instanceFollowRedirects = false
    try {
        if (connection.responseCode != 200) {
            throw IOException("HTTP " + connection.responseCode)
        }
        connection.inputStream.use { input ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                val count = input.read(buffer)
                if (count == -1) break
                if (Thread.currentThread().isInterrupted) throw InterruptedIOException()
                if (output.size() + count > limit) throw IOException("Response too large")
                output.write(buffer, 0, count)
            }
            return output.toByteArray()
        }
    } finally {
        connection.disconnect()
    }
}
