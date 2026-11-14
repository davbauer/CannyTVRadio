package com.cannytvradio.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** A list that is loading (null), loaded, or failed with nothing saved to show. */
data class Lane(val stations: List<Station>? = null, val failed: Boolean = false)

/**
 * Everything the browse screens show. It lives in the app, not in the screens,
 * so switching tabs never reloads or cancels anything.
 */
class BrowseModel(
    private val repo: Repository,
    private val settings: Settings,
    private val scope: CoroutineScope,
) {
    val popular = MutableStateFlow(Lane())
    val trending = MutableStateFlow(Lane())
    val world = MutableStateFlow(Lane())
    private var homeLoadedFor: String? = null

    fun loadHome(force: Boolean = false) {
        val home = settings.prefs.value.home
        if (!force && homeLoadedFor == home && popular.value.failed.not()) return
        homeLoadedFor = home
        load(popular) { repo.popularAtHome() }
        load(trending) { repo.trendingAtHome() }
        load(world) { repo.worldwide() }
    }

    // Regions

    private val _countries = MutableStateFlow<List<Country>>(emptyList())
    val countries: StateFlow<List<Country>> = _countries.asStateFlow()
    private val _country = MutableStateFlow("")
    val country: StateFlow<String> = _country.asStateFlow()
    private val _regions = MutableStateFlow<List<Region>>(emptyList())
    val regions: StateFlow<List<Region>> = _regions.asStateFlow()
    private val _region = MutableStateFlow<Region?>(null)
    val region: StateFlow<Region?> = _region.asStateFlow()
    val regionStations = MutableStateFlow(Lane())
    private var regionJob: Job? = null

    fun openRegions() {
        if (_country.value.isEmpty()) selectCountry(settings.prefs.value.home)
        if (_countries.value.isEmpty()) scope.launch {
            _countries.value = runCatching { repo.countries() }.getOrDefault(emptyList())
            loadRegionNames()
        }
    }

    fun selectCountry(code: String) {
        if (code == _country.value && regionStations.value.stations != null) return
        _country.value = code
        _region.value = null
        _regions.value = emptyList()
        loadRegionNames()
        loadRegionStations()
    }

    fun selectRegion(region: Region?) {
        if (region?.key == _region.value?.key) return
        _region.value = region
        loadRegionStations()
    }

    private fun loadRegionNames() {
        val code = _country.value
        val name = _countries.value.firstOrNull { it.code == code }?.name ?: return
        scope.launch { _regions.value = runCatching { repo.regions(name, code) }.getOrDefault(emptyList()) }
    }

    private fun loadRegionStations() {
        regionJob?.cancel()
        regionJob = load(regionStations) { repo.region(_country.value, _region.value) }
    }

    // Search

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()
    val results = MutableStateFlow<Lane?>(null)
    private var searchJob: Job? = null

    fun search(text: String) {
        if (text == _query.value) return
        _query.value = text
        searchJob?.cancel()
        if (text.trim().length < 2) {
            results.value = null
            return
        }
        results.value = Lane()
        searchJob = scope.launch {
            delay(380) // wait until typing pauses
            results.value = Lane(repo.search(text))
        }
    }

    // Now playing

    val similar = MutableStateFlow<List<Station>>(emptyList())
    private var similarFor: String? = null

    fun loadSimilar(station: Station) {
        if (similarFor == station.id) return
        similarFor = station.id
        similar.value = emptyList()
        scope.launch { similar.value = repo.similar(station).take(16) }
    }

    private fun load(target: MutableStateFlow<Lane>, block: suspend () -> List<Station>): Job {
        target.value = Lane()
        return scope.launch {
            target.value = try {
                Lane(block())
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Lane(failed = true)
            }
        }
    }
}
