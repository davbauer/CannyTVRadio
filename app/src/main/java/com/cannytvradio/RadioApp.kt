package com.cannytvradio

import android.app.Application
import com.cannytvradio.data.BrowseModel
import com.cannytvradio.data.Http
import com.cannytvradio.data.RadioBrowser
import com.cannytvradio.data.Repository
import com.cannytvradio.data.Settings
import com.cannytvradio.data.TuneIn
import com.cannytvradio.player.PlayerController
import coil.ImageLoader
import coil.ImageLoaderFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class RadioApp : Application(), ImageLoaderFactory {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    lateinit var settings: Settings
        private set
    lateinit var repo: Repository
        private set
    lateinit var player: PlayerController
        private set
    lateinit var browse: BrowseModel
        private set

    override fun onCreate() {
        super.onCreate()
        val sp = getSharedPreferences("radio", MODE_PRIVATE)
        importIfPresent(sp)
        val http = Http(this)
        settings = Settings(sp)
        repo = Repository(this, http, RadioBrowser(http, sp), TuneIn(http), settings, scope)
        applyPendingImport()
        player = PlayerController(this, repo, scope)
        browse = BrowseModel(repo, settings, scope)
        scope.launch {
            settings.prefs.collect {
                player.sound.normalize = it.normalize
                player.sound.preset = it.eq
            }
        }
    }

    /**
     * Moving data between installs: an `import.json` placed in the app's external files folder
     * (via adb) is read once into the app's own storage and then deleted.
     */
    private fun importIfPresent(sp: android.content.SharedPreferences) {
        val file = java.io.File(getExternalFilesDir(null) ?: return, "import.json")
        if (!file.exists()) return
        runCatching {
            val json = file.readText()
            // Settings first, before anything reads them; stations once the repository exists.
            org.json.JSONObject(json).optJSONObject("settings")?.let { prefs ->
                val e = sp.edit()
                prefs.keys().forEach { k ->
                    when (val v = prefs.get(k)) {
                        is Boolean -> e.putBoolean(k, v)
                        is Int -> e.putInt(k, v)
                        else -> e.putString(k, v.toString())
                    }
                }
                e.commit()
            }
            java.io.File(filesDir, "import.pending").writeText(json)
            file.delete()
        }
    }

    /** Applied after the repository exists, so favorites go through the normal save path. */
    private fun applyPendingImport() {
        val pending = java.io.File(filesDir, "import.pending")
        if (!pending.exists()) return
        runCatching { repo.importStations(pending.readText()) }
        pending.delete()
    }

    /** A copy of favorites and settings in the external files folder, so they can be backed up or moved. */
    fun writeExport() {
        val dir = getExternalFilesDir(null) ?: return
        runCatching { java.io.File(dir, "export.json").writeText(repo.exportJson(getSharedPreferences("radio", MODE_PRIVATE))) }
    }

    // Logos are cached on disk regardless of server headers, so they show up offline too.
    override fun newImageLoader(): ImageLoader = ImageLoader.Builder(this)
        .okHttpClient { repo.http.client }
        .respectCacheHeaders(false)
        // No fade-in: while scrolling quickly that would mean extra frames for every logo.
        .crossfade(false)
        .build()
}
