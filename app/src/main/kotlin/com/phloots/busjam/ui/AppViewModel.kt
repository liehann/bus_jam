package com.phloots.busjam.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.phloots.busjam.core.model.Level
import com.phloots.busjam.data.LevelRepository
import com.phloots.busjam.data.Progress
import com.phloots.busjam.data.ProgressStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** App-wide data: the level pack and the player's progress. */
class AppViewModel(application: Application) : AndroidViewModel(application) {
    val repository = LevelRepository(application)
    val progressStore = ProgressStore(application)

    private val _levels = MutableStateFlow<List<Level>?>(null)
    val levels: StateFlow<List<Level>?> = _levels.asStateFlow()

    val progress: StateFlow<Progress?> =
        progressStore.progress.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    init {
        viewModelScope.launch { _levels.value = repository.levels() }
    }
}
