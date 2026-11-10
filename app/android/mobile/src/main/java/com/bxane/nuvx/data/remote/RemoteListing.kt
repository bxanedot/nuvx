package com.bxane.nuvx.data.remote

import com.bxane.nuvx.data.model.UiState

object RemoteListing {

    fun <T> state(listing: Result<List<T>>, emptyMessage: String): UiState<List<T>> =
        listing.fold(
            onSuccess = { items -> if (items.isEmpty()) UiState.Error(emptyMessage) else UiState.Success(items) },
            onFailure = { UiState.Error(it.message?.takeIf(String::isNotBlank) ?: it.javaClass.simpleName) },
        )
}
