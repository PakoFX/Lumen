package com.pako.eminus4.ui.exams

import com.pako.eminus4.data.repository.ExamRepository
import com.pako.eminus4.ui.common.SubjectBlocksState
import com.pako.eminus4.ui.common.SubjectBlocksViewModel
import com.pako.eminus4.ui.common.SubjectCounts

class ExamsViewModel(private val repository: ExamRepository) : SubjectBlocksViewModel<ExamRepository.Loaded>() {

    init {
        start()
    }

    override suspend fun fetch(force: Boolean) = repository.loadAll(force)

    override fun counts(loaded: ExamRepository.Loaded) =
        blocksOf(SubjectBlocksState(loaded)).let { SubjectCounts(it.activeSubjects, it.pastSubjects) }

    companion object {
        fun blocksOf(state: SubjectBlocksState<ExamRepository.Loaded>): ExamBlocks.Result = ExamBlocks.build(
            state.loaded?.exams.orEmpty(), state.loaded?.coursesById.orEmpty(), state.showPast, state.expanded
        )
    }
}
