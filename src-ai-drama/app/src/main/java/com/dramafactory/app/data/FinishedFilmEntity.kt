package com.dramafactory.app.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 成片库实体（T014 §2.5，Room 表 v5 `finished_films`）。
 *
 * 任务规格列：episode_id, project_id, file_path, duration_ms, file_size, created_at。
 * film_id 作为 PK（规则："{episodeId}"），供 upsert/deleteFilmOf 使用。
 */
@Entity(
    tableName = "finished_films",
    indices = [
        androidx.room.Index(value = ["episode_id"]),
        androidx.room.Index(value = ["project_id"]),
    ],
)
data class FinishedFilmEntity(
    @PrimaryKey val film_id: String,       // "{episodeId}"
    val episode_id: String,
    val project_id: String,
    @ColumnInfo(name = "file_path") val filePath: String,   // files/movies/{episodeId}.mp4 绝对路径
    @ColumnInfo(name = "file_size") val fileSize: Long,     // 字节，>0
    @ColumnInfo(name = "duration_ms") val durationMs: Long, // 成片时长(毫秒)
    @ColumnInfo(name = "created_at") val createdAt: Long,   // 合成完成时间戳
)
