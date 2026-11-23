package com.pako.eminus4.ui.forums

import com.pako.eminus4.data.repository.ForumRepository
import com.pako.eminus4.ui.common.SubjectBlocksState
import com.pako.eminus4.ui.common.SubjectBlocksViewModel
import com.pako.eminus4.ui.common.SubjectCounts

class ForumsViewModel(private val repository: ForumRepository) : SubjectBlocksViewModel<ForumRepository.Loaded>() {

    init {
        start()
    }

    override suspend fun fetch(force: Boolean) = repository.loadAll(force)

    override fun counts(loaded: ForumRepository.Loaded) =
        blocksOf(SubjectBlocksState(loaded)).let { SubjectCounts(it.activeSubjects, it.pastSubjects) }

    companion object {
        fun blocksOf(state: SubjectBlocksState<ForumRepository.Loaded>): ForumBlocks.Result = ForumBlocks.build(
            state.loaded?.forums.orEmpty(), state.loaded?.coursesById.orEmpty(), state.showPast, state.expanded
        )
    }
}
