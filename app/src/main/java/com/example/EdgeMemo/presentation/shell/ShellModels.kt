package com.example.EdgeMemo.presentation.shell

/** Explicit load states for every foundation screen — no implicit nulls. */
sealed interface LoadableState<out T> {
    data object Loading : LoadableState<Nothing>
    data class Ready<T>(val value: T) : LoadableState<T>
    data class Failed(val message: String) : LoadableState<Nothing>
}
