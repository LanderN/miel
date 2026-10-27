package io.github.landern.miel

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InterruptedIOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.util.concurrent.ExecutionException
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/** Bounds workers left waiting in platform DNS or socket calls after cancellation. */
private val deadlineRequests =
    ThreadPoolExecutor(0, 2, 30, TimeUnit.SECONDS, SynchronousQueue()) { task ->
        Thread(task, "miel-http").apply { isDaemon = true }
    }

internal fun fetchBytes(
    url: String,
    limit: Int,
    timeoutMillis: Int = 6000,
    totalTimeoutMillis: Int = 0,
): ByteArray {
    if (totalTimeoutMillis == 0) return readBytes(url, limit, timeoutMillis)
    val request =
        try {
            deadlineRequests.submit<ByteArray> { readBytes(url, limit, timeoutMillis) }
        } catch (error: RejectedExecutionException) {
            throw IOException("Previous artwork requests are still stopping", error)
        }
    try {
        return request.get(totalTimeoutMillis.toLong(), TimeUnit.MILLISECONDS)
    } catch (error: TimeoutException) {
        throw SocketTimeoutException("Artwork request exceeded $totalTimeoutMillis ms")
    } catch (error: InterruptedException) {
        Thread.currentThread().interrupt()
        throw InterruptedIOException("Artwork request interrupted")
    } catch (error: ExecutionException) {
        throw error.cause ?: error
    } finally {
        request.cancel(true)
    }
}

private fun readBytes(url: String, limit: Int, timeoutMillis: Int): ByteArray {
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
