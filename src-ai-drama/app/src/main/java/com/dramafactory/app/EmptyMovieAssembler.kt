package com.dramafactory.app

import com.dramafactory.core.assemble.MovieAssembler
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File

/**
 * TD-4：从 AppGraph 上帝对象抽出的 Room 初始化失败兜底（原 AppGraph.EmptyMovieAssembler 嵌套对象）。
 * ffmpeg-kit 未初始化时 MovieAssembler 的空实现，composeFilmIfReady 检测到即跳过合成。
 */
internal object EmptyMovieAssembler : MovieAssembler {
    override val progress: StateFlow<MovieAssembler.MovieAssembleProgress> =
        MutableStateFlow(MovieAssembler.MovieAssembleProgress(
            MovieAssembler.AssembleStage.DONE, 0, 0, "empty", 0))
    override suspend fun assemble(
        clips: List<File>, output: File,
        grade: MovieAssembler.ColorGradePreset,
    ): MovieAssembler.AssembleResult =
        MovieAssembler.AssembleResult.Failure(
            MovieAssembler.Strategy.CONCAT_COPY, "ffmpeg-kit 未初始化，请使用云端合成")
}
