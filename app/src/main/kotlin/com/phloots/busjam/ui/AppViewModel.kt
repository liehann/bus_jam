package com.phloots.busjam.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.phloots.busjam.BuildConfig
import com.phloots.busjam.data.AndroidAssetSource
import com.phloots.busjam.data.GameModeStore
import com.phloots.busjam.data.LevelRepository
import com.phloots.busjam.data.PackCatalog
import com.phloots.busjam.data.PackInfo
import com.phloots.busjam.data.PackSelectionStore
import com.phloots.busjam.data.Progress
import com.phloots.busjam.data.ProgressStore
import com.phloots.busjam.data.progressDataStore
import com.phloots.jam.core.io.PackedLevel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** A pack with its levels loaded (empty if the file could not be decoded). */
data class LoadedPack(val info: PackInfo, val levels: List<PackedLevel>)

/**
 * App-wide data: the level packs, the selected pack and its progress. Release builds only know the campaign;
 * debug builds also offer every `assets/packs/<name>.json` and remember the selection.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AppViewModel(application: Application) : AndroidViewModel(application) {
    private val dataStore = application.progressDataStore
    private val assets = AndroidAssetSource(application.assets)
    val repository = LevelRepository(assets)
    val packs: List<PackInfo> = PackCatalog.discover(assets, includeExtra = BuildConfig.DEBUG)
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
                emit(LoadedPack(p, runCatching { repository.levels(p.file) }.getOrElse { emptyList() }))
            }
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /** The selected pack's progress; null while loading. */
    val progress: StateFlow<Progress?> = pack.filterNotNull()
        .flatMapLatest { p -> progressStore(p).progress }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private val mode = GameModeStore(dataStore)

    /** Real-time carousel mode: debug builds only (default on, toggle on the home screen); release is turn-based. */
    val realTime: StateFlow<Boolean> = mode.realTime
        .map { BuildConfig.DEBUG && (it ?: true) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    fun setRealTime(on: Boolean) {
        viewModelScope.launch { mode.setRealTime(on) }
    }

    fun selectPack(file: String) {
        viewModelScope.launch { selection.select(file) }
    }
}
