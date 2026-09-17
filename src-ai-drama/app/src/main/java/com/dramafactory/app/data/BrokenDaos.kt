package com.dramafactory.app.data

import com.dramafactory.app.storage.StorageUnavailableException

/**
 * P0 持久化可靠性：Room 初始化失败兜底 DAO。
 *
 * 原实现为全 no-op / 空集合（把存储故障伪装成"创建成功但列表为空"），现已改为**显式失败**：
 * 每个写方法抛 [StorageUnavailableException]，读方法同样抛——禁止静默成功。
 * 生产装配应由 AppGraph 的存储闸门拦截（StorageState.Blocked），这里的异常是第二道安全网：
 * 任何绕过闸门直接触达 DAO 的路径都会立刻失败，而不是返回空列表/0L/Unit。
 */
internal class BrokenDramaDao : DramaDao {
    private fun noStorage(): Nothing = throw StorageUnavailableException(
        code = "DAO_UNAVAILABLE",
        userMessage = "本地存储不可用，数据未写入（Room 初始化失败）",
        diagnosticId = "broken-dao",
    )

    override suspend fun reserveAction(key: ActionIdempotencyEntity): Long = noStorage()
    override suspend fun completeAction(key: String, updatedAt: Long) = noStorage()
    override suspend fun releaseAction(key: String) = noStorage()
    override suspend fun upsertProject(p: ProjectEntity) = noStorage()
    override suspend fun listProjects(): List<ProjectEntity> = noStorage()
    override suspend fun project(id: String): ProjectEntity? = noStorage()
    override suspend fun deleteProject(id: String) = noStorage()
    override suspend fun upsertAsset(a: AssetEntity) = noStorage()
    override suspend fun assetsOf(projectId: String, kind: String): List<AssetEntity> = noStorage()
    override suspend fun assetsAllOf(projectId: String): List<AssetEntity> = noStorage()
    override suspend fun updateAssetLocal(assetId: String, source: String, imageUri: String?, videoUri: String?, referenceImageUri: String?, prompt: String, updatedAt: Long) = noStorage()
    override suspend fun setAssetReferenceImage(assetId: String, referenceImageUri: String?, updatedAt: Long) = noStorage()
    override suspend fun setAssetQuality(assetId: String, qualityScore: Double?, auditState: String, defectsJson: String?, rejectReason: String?, g1ErrorCode: String?, faceRatio: Double?, poseRole: String?, updatedAt: Long) = noStorage()
    override suspend fun updateAssetPrompt(assetId: String, prompt: String, updatedAt: Long) = noStorage()
    override suspend fun setAssetRemoteUrl(assetId: String, remoteUrl: String, updatedAt: Long) = noStorage()
    override suspend fun setAssetEnrichedPrompt(assetId: String, enrichedPrompt: String?, updatedAt: Long) = noStorage()
    override suspend fun assetRemoteUrl(assetId: String): String? = noStorage()
    override suspend fun deleteAsset(assetId: String) = noStorage()
    override suspend fun assetQuality(assetId: String): AssetQualityRow? = noStorage()
    override suspend fun assetQualities(projectId: String): List<AssetQualityRow> = noStorage()
    override suspend fun setEpisodeAllowedCrossEra(episodeId: String, allowed: String) = noStorage()
    override suspend fun episodeAllowedCrossEra(episodeId: String): String? = noStorage()
    override suspend fun setReviewState(assetId: String, state: String) = noStorage()
    override suspend fun upsertShot(s: ShotEntity) = noStorage()
    override suspend fun shotsOf(episodeId: String): List<ShotEntity> = noStorage()
    override suspend fun deleteShotsOf(episodeId: String) = noStorage()
    override suspend fun deleteShot(shotId: String) = noStorage()
    override suspend fun deleteRenderTask(shotId: String) = noStorage()
    override suspend fun deleteRenderTasksOf(episodeId: String) = noStorage()
    override suspend fun renderStatesOf(episodeId: String): List<RenderStateRow> = noStorage()
    override suspend fun setShotKeyframes(shotId: String, first: String?, last: String?) = noStorage()
    override suspend fun setShotReferenceVideo(shotId: String, uri: String?) = noStorage()
    override suspend fun shotKeyframes(shotId: String): ShotEntity? = noStorage()
    override suspend fun shotReferenceVideo(shotId: String): String? = noStorage()
    override suspend fun upsertRenderTask(t: RenderTaskEntity) = noStorage()
    override suspend fun renderTasksOf(ep: String): List<RenderTaskEntity> = noStorage()
    override suspend fun renderTask(shotId: String): RenderTaskEntity? = noStorage()
    override suspend fun renderTasksOfShot(shotId: String): List<RenderTaskEntity> = noStorage()
    override suspend fun allEpisodeIds(): List<String> = noStorage()
    override suspend fun renderTasksOfEpOrdered(ep: String): List<RenderTaskEntity> = noStorage()
    override suspend fun pendingRepoll(ep: String): List<RenderTaskEntity> = noStorage()
    override suspend fun upsertProviderConfig(c: ProviderConfigEntity) = noStorage()
    override suspend fun verifiedConfig(channel: String): ProviderConfigEntity? = noStorage()
    override suspend fun upsertEpisode(e: EpisodeEntity) = noStorage()
    override suspend fun episode(id: String): EpisodeEntity? = noStorage()
    override suspend fun episodesOf(projectId: String): List<EpisodeEntity> = noStorage()
}

internal class BrokenMovieLibraryDao : MovieLibraryDao {
    private fun noStorage(): Nothing = throw StorageUnavailableException(
        code = "DAO_UNAVAILABLE",
        userMessage = "本地存储不可用，成片未写入（Room 初始化失败）",
        diagnosticId = "broken-movie-dao",
    )

    override suspend fun upsertFilmOf(film: FinishedFilmEntity): Long = noStorage()
    override suspend fun deleteFilmOf(episodeId: String): Int = noStorage()
    override suspend fun deleteFilm(film: FinishedFilmEntity): Int = noStorage()
    override suspend fun finishedFilmsOf(projectId: String): List<FinishedFilmEntity> = noStorage()
    override suspend fun finishedFilmOf(episodeId: String): FinishedFilmEntity? = noStorage()
    override suspend fun assembledEpisodeIds(projectId: String): List<String> = noStorage()
    override suspend fun allFilms(): List<FinishedFilmEntity> = noStorage()
}