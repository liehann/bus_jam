package com.phloots.crittercafe.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.phloots.crittercafe.BuildConfig
import com.phloots.crittercafe.ui.skin.critter.CafeSkin
import com.phloots.crittercafe.ui.skin.critter.CafeThemes
import com.phloots.crittercafe.data.AndroidAssetSource
import com.phloots.crittercafe.data.GameModeStore
import com.phloots.crittercafe.data.LevelMeta
import com.phloots.crittercafe.data.LevelRepository
import com.phloots.crittercafe.data.PackCatalog
import com.phloots.crittercafe.data.PackInfo
import com.phloots.crittercafe.data.PackSelectionStore
import com.phloots.crittercafe.data.Progress
import com.phloots.crittercafe.data.ProgressStore
import com.phloots.crittercafe.data.SeenMechanics
import com.phloots.crittercafe.data.SeenMechanicsStore
import com.phloots.crittercafe.data.SkinStore
import com.phloots.crittercafe.data.progressDataStore
import com.phloots.crittercafe.core.io.PackedLevel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * A pack with its levels loaded (empty if the file could not be decoded) and their [LevelMeta] (empty, or one per
 * level).
 */
data class LoadedPack(val info: PackInfo, val levels: List<PackedLevel>, val meta: List<LevelMeta> = emptyList()) {
    /** Level [number]'s (1-based) meta; empty when the pack has none. */
    fun meta(number: Int): LevelMeta = meta.getOrNull(number - 1) ?: LevelMeta()
}

/**
 * App-wide data: the level packs, the selected pack and its progress. Release builds only know the campaign;
 * debug builds also offer every `assets/packs/<name>.json` and remember the selection.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AppViewModel(application: Application) : AndroidViewModel(application) {
    private val dataStore = application.progressDataStore
    private val assets = AndroidAssetSource(application.assets)
    val repository = LevelRepository(assets)
    val packs: List<PackInfo> = PackCatalog.discover(assets, includeExtra = false) // the official campaign only
    private val selection = PackSelectionStore(dataStore)
    private val stores = HashMap<String, ProgressStore>()

    /** Progress (and win sink) of [pack]. */
    fun progressStore(pack: PackInfo): ProgressStore = stores.getOrPut(pack.file) { ProgressStore(dataStore, pack.file) }

    /** Selected pack (falls back to the first, i.e. the campaign); null until the selection has been read. */
    val pack: StateFlow<PackInfo?> = selection.selected
        .map { file -> packs.firstOrNull { it.file == file } ?: packs.first() }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /** The selected pack's levels; null while loading. */
    val loaded: StateFlow<LoadedPack?> = pack.filterNotNull()
        .flatMapLatest { p ->
            flow {
                emit(null)
                val levels = runCatching { repository.levels(p.file) }.getOrElse { emptyList() }
                val meta = runCatching { repository.meta(p.file) }.getOrElse { emptyList() }
                emit(LoadedPack(p, levels, meta.takeIf { it.size == levels.size }.orEmpty()))
            }
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /** The selected pack's progress; null while loading. The prototype build unlocks every level. */
    val progress: StateFlow<Progress?> = pack.filterNotNull()
        .flatMapLatest { p -> progressStore(p).progress }
        .map { if (BuildConfig.UNLOCK_ALL) it.copy(highestUnlocked = Int.MAX_VALUE) else it }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private val mode = GameModeStore(dataStore)

    /** Real-time carousel mode: always on (the turn-based mode stays in the code, without a switch). */
    val realTime: StateFlow<Boolean?> = mode.realTime
        .map { true }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null) // null until the setting has been read

    fun setRealTime(on: Boolean) {
        viewModelScope.launch { mode.setRealTime(on) }
    }

    private val skinStore = SkinStore(dataStore)

    /** Critter Café skin: always on (the flat skin stays in the code, without a switch). */
    val critterCafe: StateFlow<Boolean?> = skinStore.critterCafe
        .map { true }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    fun setCritterCafe(on: Boolean) {
        viewModelScope.launch { skinStore.setCritterCafe(on) }
    }

    /** The Critter Café themes the app has art for: the classic café first, then the world themes. */
    private val cafeSkins = CafeThemes.available(application)

    /**
     * Critter Café theme (a debug switch for now; release builds keep the classic café). Its art is loaded here, so
     * the screen colour derived from the backdrop is known before the first frame.
     */
    val cafeSkin: StateFlow<CafeSkin> = skinStore.cafeTheme
        .map { id -> (cafeSkins.firstOrNull { BuildConfig.DEBUG && it.id == id } ?: cafeSkins.first()).also { it.prepare(application) } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, cafeSkins.first())

    /**
     * The skin a level with [meta] is drawn in: its pack theme (prepared here) when it names a known one, else the
     * selected [cafeSkin].
     */
    fun skinFor(meta: LevelMeta): CafeSkin =
        meta.theme?.let { CafeThemes.skin(it) }?.also { it.prepare(getApplication()) } ?: cafeSkin.value

    /** Switches to the next Critter Café theme. */
    fun nextCafeTheme() {
        val next = cafeSkins[(cafeSkins.indexOf(cafeSkin.value) + 1) % cafeSkins.size]
        viewModelScope.launch { skinStore.setCafeTheme(next.id) }
    }

    private val seenStore = SeenMechanicsStore(dataStore)

    /** Mechanic intros already shown (the game screen's first-time cards). */
    val seenMechanics: SeenMechanics = object : SeenMechanics {
        override val seen = seenStore.seen
        override fun markSeen(id: String) {
            viewModelScope.launch { seenStore.markSeen(id) }
        }
    }

    /** Everything the first home frame needs has been read: skin, mode, the selected pack and its progress. */
    fun isReady(): Boolean = critterCafe.value != null && realTime.value != null && loaded.value != null && progress.value != null

    fun selectPack(file: String) {
        viewModelScope.launch { selection.select(file) }
    }
}
