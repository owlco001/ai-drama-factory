package com.dramafactory.app.data

import com.dramafactory.app.ui.AssetCatalog

/**
 * TD-4：从 AppGraph 上帝对象抽出的 Room 初始化失败兜底 DAO（原 AppGraph.BrokenDramaDao / BrokenMovieLibraryDao）。
 * 全部为 no-op / 空集合，保证 Room 不可用时 App 不崩，仅相关能力静默降级。
 */
internal class BrokenDramaDao : DramaDao {
    override suspend fun upsertProject(p: ProjectEntity) {}
    override suspend fun listProjects(): List<ProjectEntity> = emptyList()
    override suspend fun project(id: String): ProjectEntity? = null
    override suspend fun deleteProject(id: String) {}
    override suspend fun upsertAsset(a: AssetEntity) {}
    override suspend fun assetsOf(projectId: String, kind: String): List<AssetEntity> = emptyList()
    override suspend fun assetsAllOf(projectId: String): List<AssetEntity> = emptyList()
    override suspend fun updateAssetLocal(assetId: String, source: String, imageUri: String?, videoUri: String?, referenceImageUri: String?, prompt: String, updatedAt: Long) {}
    override suspend fun setAssetReferenceImage(assetId: String, referenceImageUri: String?, updatedAt: Long) {}
    override suspend fun setAssetQuality(assetId: String, qualityScore: Double?, auditState: String, defectsJson: String?, rejectReason: String?, g1ErrorCode: String?, faceRatio: Double?, poseRole: String?, updatedAt: Long) {}
    override suspend fun updateAssetPrompt(assetId: String, prompt: String, updatedAt: Long) {}
    override suspend fun setAssetRemoteUrl(assetId: String, remoteUrl: String, updatedAt: Long) {}
    override suspend fun setAssetEnrichedPrompt(assetId: String, enrichedPrompt: String?, updatedAt: Long) {}
    override suspend fun assetRemoteUrl(assetId: String): String? = null
    override suspend fun deleteAsset(assetId: String) {}
    override suspend fun assetQuality(assetId: String): AssetQualityRow? = null
    override suspend fun assetQualities(projectId: String): List<AssetQualityRow> = emptyList()
    override suspend fun setEpisodeAllowedCrossEra(episodeId: String, allowed: String) {}
    override suspend fun episodeAllowedCrossEra(episodeId: String): String? = null
    override suspend fun setReviewState(assetId: String, state: String) {}
    override suspend fun upsertShot(s: ShotEntity) {}
    override suspend fun shotsOf(episodeId: String): List<ShotEntity> = emptyList()
    override suspend fun deleteShotsOf(episodeId: String) {}
    override suspend fun deleteShot(shotId: String) {}
    override suspend fun deleteRenderTask(shotId: String) {}
    override suspend fun deleteRenderTasksOf(episodeId: String) {}
    override suspend fun renderStatesOf(episodeId: String): List<RenderStateRow> = emptyList()
    override suspend fun setShotKeyframes(shotId: String, first: String?, last: String?) {}
    override suspend fun setShotReferenceVideo(shotId: String, uri: String?) {}
    override suspend fun shotKeyframes(shotId: String): ShotEntity? = null
    override suspend fun shotReferenceVideo(shotId: String): String? = null
    override suspend fun upsertRenderTask(t: RenderTaskEntity) {}
    override suspend fun renderTasksOf(ep: String): List<RenderTaskEntity> = emptyList()
    override suspend fun renderTask(shotId: String): RenderTaskEntity? = null
    override suspend fun renderTasksOfShot(shotId: String): List<RenderTaskEntity> = emptyList()
    override suspend fun allEpisodeIds(): List<String> = emptyList()
    override suspend fun renderTasksOfEpOrdered(ep: String): List<RenderTaskEntity> = emptyList()
    override suspend fun pendingRepoll(ep: String): List<RenderTaskEntity> = emptyList()
    override suspend fun upsertProviderConfig(c: ProviderConfigEntity) {}
    override suspend fun verifiedConfig(channel: String): ProviderConfigEntity? = null
    override suspend fun upsertEpisode(e: EpisodeEntity) {}
    override suspend fun episode(id: String): EpisodeEntity? = null
    override suspend fun episodesOf(projectId: String): List<EpisodeEntity> = emptyList()
}

internal class BrokenMovieLibraryDao : MovieLibraryDao {
    override suspend fun upsertFilmOf(film: FinishedFilmEntity): Long = 0L
    override suspend fun deleteFilmOf(episodeId: String): Int = 0
    override suspend fun deleteFilm(film: FinishedFilmEntity): Int = 0
    override suspend fun finishedFilmsOf(projectId: String): List<FinishedFilmEntity> = emptyList()
    override suspend fun finishedFilmOf(episodeId: String): FinishedFilmEntity? = null
    override suspend fun assembledEpisodeIds(projectId: String): List<String> = emptyList()
    override suspend fun allFilms(): List<FinishedFilmEntity> = emptyList()
}
