package com.cannytvradio.data

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import com.cannytvradio.BuildConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/** A newer version announced in the release's update.json. */
data class UpdateInfo(
    val versionCode: Int,
    val versionName: String,
    val changes: List<String>,
    val url: String,
    val sha256: String,
    val size: Long,
)

data class UpdateState(
    val available: UpdateInfo? = null,
    val checking: Boolean = false,
    /** null: no check yet; true: up to date; false: check failed. */
    val lastCheckOk: Boolean? = null,
    val step: Step = Step.Idle,
    val progress: Float = 0f,
) {
    enum class Step { Idle, Downloading, Verifying, NeedsPermission, Installing, Failed }
}

/**
 * Updates straight from GitHub releases.
 *
 * The app reads `update.json` from the latest release, at most every 6 hours. A download is only
 * accepted if all of these hold: the URL is a release of github.com/davbauer/CannyTVRadio, the
 * file's SHA-256 and size match update.json, and the APK is signed with the CannyTVRadio release
 * key (pinned below). Android itself also refuses an update signed with a different key.
 */
class Updater(private val context: Context, private val http: Http, private val scope: CoroutineScope) {
    private val _state = MutableStateFlow(UpdateState())
    val state: StateFlow<UpdateState> = _state.asStateFlow()
    private val prefs = context.getSharedPreferences("updates", Context.MODE_PRIVATE)

    fun check(force: Boolean = false) {
        if (_state.value.checking) return
        val last = prefs.getLong("lastCheck", 0)
        if (!force && System.currentTimeMillis() - last < CHECK_EVERY_MS && _state.value.lastCheckOk != null) return
        _state.update { it.copy(checking = true) }
        scope.launch {
            val info = try {
                val language = if (java.util.Locale.getDefault().language == "de") "de" else "en"
                UpdateFeed.parse(http.get(FEED), Build.VERSION.SDK_INT, Build.SUPPORTED_ABIS.toList(), language)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(checking = false, lastCheckOk = false) }
                return@launch
            }
            prefs.edit().putLong("lastCheck", System.currentTimeMillis()).apply()
            _state.update { it.copy(checking = false, lastCheckOk = true, available = info?.takeIf { i -> UpdateFeed.isNewer(i, BuildConfig.VERSION_CODE) }) }
        }
    }

    /** Downloads, verifies and hands the update to Android's installer. */
    fun install(activity: Activity) {
        val info = _state.value.available ?: return
        if (_state.value.step == UpdateState.Step.Downloading || _state.value.step == UpdateState.Step.Verifying) return
        // Android 8+: the user allows this app to install updates once.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !context.packageManager.canRequestPackageInstalls()) {
            _state.update { it.copy(step = UpdateState.Step.NeedsPermission) }
            runCatching {
                activity.startActivity(
                    Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}")),
                )
            }
            return
        }
        scope.launch {
            val file = try {
                download(info)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                android.util.Log.w("CannyTVRadio", "update: download failed: $e")
                _state.update { it.copy(step = UpdateState.Step.Failed) }
                return@launch
            }
            _state.update { it.copy(step = UpdateState.Step.Verifying) }
            val trusted = withContext(Dispatchers.IO) { UpdateFeed.sha256(file) == info.sha256.lowercase() && signedByUs(file) }
            if (!trusted) {
                android.util.Log.w("CannyTVRadio", "update: rejected, checksum or signature does not match")
                file.delete()
                _state.update { it.copy(step = UpdateState.Step.Failed) }
                return@launch
            }
            _state.update { it.copy(step = UpdateState.Step.Installing) }
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.updates", file)
            val intent = Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            runCatching { activity.startActivity(intent) }.onFailure {
                _state.update { s -> s.copy(step = UpdateState.Step.Failed) }
            }
        }
    }

    /** Back from the "install unknown apps" screen: carry on if it was allowed. */
    fun resumeAfterPermission(activity: Activity) {
        if (_state.value.step != UpdateState.Step.NeedsPermission) return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O || context.packageManager.canRequestPackageInstalls()) {
            _state.update { it.copy(step = UpdateState.Step.Idle) }
            install(activity)
        }
    }

    private suspend fun download(info: UpdateInfo): File = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, "updates").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val file = File(dir, "CannyTVRadio-${info.versionName}.apk")
        _state.update { it.copy(step = UpdateState.Step.Downloading, progress = 0f) }
        http.client.newCall(Request.Builder().url(info.url).build()).execute().use { r ->
            if (!r.isSuccessful) throw java.io.IOException("HTTP ${r.code}")
            val body = r.body ?: throw java.io.IOException("empty")
            val total = body.contentLength().takeIf { it > 0 } ?: info.size
            body.byteStream().use { input ->
                file.outputStream().use { out ->
                    val buffer = ByteArray(64 * 1024)
                    var done = 0L
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        out.write(buffer, 0, n)
                        done += n
                        _state.update { it.copy(progress = (done.toFloat() / total).coerceIn(0f, 1f)) }
                    }
                }
            }
        }
        if (info.size > 0 && file.length() != info.size) throw java.io.IOException("size mismatch")
        file
    }

    /** The APK must carry the CannyTVRadio release certificate. */
    @Suppress("DEPRECATION")
    private fun signedByUs(file: File): Boolean {
        val pm = context.packageManager
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
        val info: PackageInfo = pm.getPackageArchiveInfo(file.path, flags) ?: return false
        if (info.packageName != context.packageName) return false
        val certs = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.signingInfo?.let { if (it.hasMultipleSigners()) it.apkContentsSigners else it.signingCertificateHistory }
        } else {
            info.signatures
        } ?: return false
        return certs.any { digest(it.toByteArray()) == RELEASE_CERT_SHA256 }
    }

    private fun digest(bytes: ByteArray) =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    companion object {
        const val FEED = "${UpdateFeed.REPO}/releases/latest/download/update.json"
        /** SHA-256 of the CannyTVRadio release signing certificate. */
        private const val RELEASE_CERT_SHA256 = "ffbe18d85267d7c1a98ddb41175faeec3d9cc38f3086e160b8c833ab4c848001"
        private const val CHECK_EVERY_MS = 6 * 60 * 60 * 1000L
    }
}

/** Reading update.json and checking downloads; no Android dependencies, so it is unit-tested. */
object UpdateFeed {
    const val REPO = "https://github.com/davbauer/CannyTVRadio"
    /** Downloads are only trusted from this project's own GitHub releases. */
    const val TRUSTED_PREFIX = "$REPO/releases/download/"

    /** The download that fits this device, or null if none fits or none is trusted. */
    fun parse(body: String, sdkInt: Int, abis: List<String>, language: String): UpdateInfo? {
        val o = JSONObject(body)
        val changes = o.optJSONObject("changes")?.let { c -> (c.optJSONArray(language) ?: c.optJSONArray("en")).strings() }.orEmpty()
        val downloads = o.optJSONArray("downloads") ?: return null
        val pick = (0 until downloads.length()).map { downloads.getJSONObject(it) }
            .filter { it.optInt("minSdk", 21) <= sdkInt }
            .filter { d -> d.optJSONArray("abis").strings().let { it.isEmpty() || it.any(abis::contains) } }
            .firstOrNull { isTrusted(it.optString("url")) }
            ?: return null
        return UpdateInfo(
            versionCode = o.getInt("versionCode"),
            versionName = o.optString("versionName"),
            changes = changes,
            url = pick.getString("url"),
            sha256 = pick.getString("sha256").lowercase(),
            size = pick.optLong("size"),
        )
    }

    /** Only https downloads of this repository's releases; no other host, no path tricks. */
    fun isTrusted(url: String): Boolean =
        url.startsWith(TRUSTED_PREFIX) && ".." !in url && "?" !in url && "#" !in url && "@" !in url

    fun isNewer(info: UpdateInfo, installedCode: Int) = info.versionCode > installedCode

    fun sha256(file: File): String = file.inputStream().use { sha256(it) }

    fun sha256(input: java.io.InputStream): String {
        val md = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val n = input.read(buffer)
            if (n < 0) break
            md.update(buffer, 0, n)
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }
}
