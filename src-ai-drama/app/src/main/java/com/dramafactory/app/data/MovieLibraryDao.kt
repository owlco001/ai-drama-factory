package com.dramafactory.app.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

/**
 * 成片库 DAO（T014 §2.5，Room 表 v5 `finished_films`）。
 *
 * 与主 DramaDao 独立成文件，便于成片库模块（LibraryPage / MovieAssembler wiring）单独依赖。
 * 挂接到 DramaDatabase (v5) 的 @Database(entities) + 新增 MIGRATION_4_5。
 */
@Dao
interface MovieLibraryDao {

    /** 插入或替换（按 episodeId 幂等，重合成幂等覆盖）。 */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertFilmOf(film: FinishedFilmEntity): Long

    /** 按 episodeId 删除（成片库页"二次确认"删除用）。 */
    @Query("DELETE FROM finished_films WHERE episode_id = :episodeId")
    suspend fun deleteFilmOf(episodeId: String): Int

    /** 按 film_id 精确删除（兼容架构§2.5 的 deleteFilm）。 */
    @Delete
    suspend fun deleteFilm(film: FinishedFilmEntity): Int

    /** 某项目下所有成片（UI：LibraryPage 按项目聚合展示）。 */
    @Query("SELECT * FROM finished_films WHERE project_id = :projectId ORDER BY created_at DESC")
    suspend fun finishedFilmsOf(projectId: String): List<FinishedFilmEntity>

    /** 单集成片（UI：剧集卡片"已合成"状态判断 + ExoPlayer 预览入口）。 */
    @Query("SELECT * FROM finished_films WHERE episode_id = :episodeId LIMIT 1")
    suspend fun finishedFilmOf(episodeId: String): FinishedFilmEntity?

    /** 某项目已合成集 id 列表（架构§2.5 assembledEpisodeIds）。 */
    @Query("SELECT episode_id FROM finished_films WHERE project_id = :projectId")
    suspend fun assembledEpisodeIds(projectId: String): List<String>

    /** 全局成片列表（诊断/分享页用）。 */
    @Query("SELECT * FROM finished_films ORDER BY created_at DESC")
    suspend fun allFilms(): List<FinishedFilmEntity>
}
