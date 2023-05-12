/*
 * Copyright 2023 Sasikanth Miriyampalli
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package dev.sasikanth.rss.reader.home

import com.arkivanov.essenty.instancekeeper.InstanceKeeper
import com.arkivanov.essenty.lifecycle.Lifecycle
import com.arkivanov.essenty.lifecycle.doOnCreate
import dev.sasikanth.rss.reader.database.Feed
import dev.sasikanth.rss.reader.database.PostWithMetadata
import dev.sasikanth.rss.reader.repository.RssRepository
import dev.sasikanth.rss.reader.utils.DispatchersProvider
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.flatMapMerge
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

@OptIn(ExperimentalCoroutinesApi::class)
internal class HomeViewModel(
  lifecycle: Lifecycle,
  dispatchersProvider: DispatchersProvider,
  private val rssRepository: RssRepository,
) : InstanceKeeper.Instance {

  private val viewModelScope = CoroutineScope(SupervisorJob() + dispatchersProvider.main)

  private val _state = MutableStateFlow(HomeState.DEFAULT)
  val state: StateFlow<HomeState> =
    _state.stateIn(
      scope = viewModelScope,
      started = SharingStarted.WhileSubscribed(5000),
      initialValue = HomeState.DEFAULT
    )

  private val _effects = MutableSharedFlow<HomeEffect>(extraBufferCapacity = 10)
  val effects = _effects.asSharedFlow()

  init {
    lifecycle.doOnCreate { dispatch(HomeEvent.LoadContent) }
  }

  fun dispatch(event: HomeEvent) {
    when (event) {
      HomeEvent.LoadContent -> loadContent()
      HomeEvent.OnSwipeToRefresh -> refreshContent()
      is HomeEvent.OnFeedSelected -> onFeedSelected(event.feed)
      HomeEvent.OnHomeSelected -> onHomeSelected()
      HomeEvent.OnAddClicked -> onAddClicked()
      is HomeEvent.OnPostClicked -> onPostClicked(event.post)
    }
  }

  private fun loadContent() {
    state
      .flatMapMerge { state -> rssRepository.posts(selectedFeedLink = state.selectedFeed?.link) }
      .onEach { posts -> _state.update { it.copy(posts = posts.toImmutableList()) } }
      .launchIn(viewModelScope)

    rssRepository
      .allFeeds()
      .onEach { feeds -> _state.update { it.copy(feeds = feeds.toImmutableList()) } }
      .launchIn(viewModelScope)
  }

  private fun onPostClicked(post: PostWithMetadata) {
    viewModelScope.launch { _effects.emit(HomeEffect.OpenPost(post)) }
  }

  private fun onAddClicked() {
    viewModelScope.launch { _effects.emit(HomeEffect.NavigateToAddScreen) }
  }

  private fun onHomeSelected() {
    _state.update { it.copy(selectedFeed = null) }
  }

  private fun onFeedSelected(feed: Feed) {
    _state.update { it.copy(selectedFeed = feed) }
  }

  private fun refreshContent() {
    viewModelScope.launch { updateLoadingState { rssRepository.updateFeeds() } }
  }

  private suspend fun updateLoadingState(action: suspend () -> Unit) {
    _state.update { it.copy(loadingState = HomeLoadingState.Loading) }
    action()
    _state.update { it.copy(loadingState = HomeLoadingState.Idle) }
  }

  override fun onDestroy() {
    viewModelScope.cancel()
  }
}
