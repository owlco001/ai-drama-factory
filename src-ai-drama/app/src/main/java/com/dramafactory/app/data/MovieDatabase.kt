package com.dramafactory.app.data

import android.content.Context

/**
 * 成片库便利入口（T014 §2.5 + §四数据库变更）。
 *
 * 复用主 Room 实例 [DramaDatabase]（同一 DB），同时暴露：
 * - [DramaDao]：业务主 DAO（不变）
 * - [MovieLibraryDao]：成片库 DAO（T014 新增）
 *
 * AppGraph 通过 [getDramaDao] / [getMovieLibraryDao] 获取两个 DAO；
 * UI / ViewModel 侧可经本对象取成片库 DAO（LibraryPage 连线用）。
 *
 * 迁移链：v1→v2→v3→v4→v5（MIGRATION_4_5 新增 finished_films 表）。
 */
object MovieDatabase {

    fun get(context: Context): DramaDatabase = DramaDatabase.get(context.applicationContext)

    fun getDramaDao(context: Context): DramaDao = get(context).dao()

    fun getMovieLibraryDao(context: Context): MovieLibraryDao = get(context).movieLibraryDao()

    /** 供 AppGraph 一次性同时拿到两个 DAO。 */
    data class Pair(val dramaDao: DramaDao, val movieLibraryDao: MovieLibraryDao)

    fun getDaoPair(context: Context): Pair {
        val db = get(context)
        return Pair(db.dao(), db.movieLibraryDao())
    }
}
