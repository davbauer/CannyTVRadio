package com.cannytvradio.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

const val USER_AGENT = "CannyTVRadio/1.0 (Android TV)"

/**
 * HTTP with a "last known good" copy of every response on disk.
 * When every source fails, [getCached] answers from that copy instead of showing nothing.
 */
class Http(context: Context) {
    val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .callTimeout(10, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .addInterceptor { chain ->
            chain.proceed(chain.request().newBuilder().header("User-Agent", USER_AGENT).build())
        }
        .build()

    private val dir = File(context.filesDir, "responses").apply { mkdirs() }

    suspend fun get(url: String): String = withContext(Dispatchers.IO) {
        client.newCall(Request.Builder().url(url).build()).execute().use { r ->
            if (!r.isSuccessful) throw IOException("HTTP ${r.code} for $url")
            r.body?.string() ?: throw IOException("Empty body for $url")
        }
    }

    fun store(key: String, body: String) {
        runCatching { File(dir, hash(key)).writeText(body) }
    }

    fun getCached(key: String): String? = runCatching { File(dir, hash(key)).readText() }.getOrNull()

    fun clear() {
        dir.listFiles()?.forEach { it.delete() }
    }

    private fun hash(s: String): String =
        MessageDigest.getInstance("SHA-1").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }
}
