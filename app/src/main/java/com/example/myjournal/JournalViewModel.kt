package com.example.myjournal

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException

data class JournalState(
    val lessons: List<Lesson> = emptyList(),
    val versions: List<ScheduleVersion> = emptyList(),
    val entries: List<ScheduleEntry> = emptyList(),
    val excluded: Set<String> = emptySet(),
    val ready: Boolean = false
)

class JournalViewModel(application: Application) : AndroidViewModel(application) {
    private val database = JournalDatabase.create(application)
    val repository = JournalRepository(database)
    val state = combine(repository.dao.observeLessons(), repository.dao.observeVersions(),
        repository.dao.observeEntries(), repository.dao.observeExcluded()) { lessons, versions, entries, excluded ->
        JournalState(lessons, versions, entries, excluded.map { it.date }.toSet(), true)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), JournalState())
    val error = MutableStateFlow<String?>(null)
    val busy = MutableStateFlow(false)
    fun execute(action: suspend () -> Unit) {
        if (busy.value) return
        viewModelScope.launch {
            busy.value = true
            try { action() } catch (e: CancellationException) { throw e }
            catch (e: Exception) { error.value = e.message ?: "Не удалось сохранить данные" }
            finally { busy.value = false }
        }
    }
    override fun onCleared() { database.close() }
}
