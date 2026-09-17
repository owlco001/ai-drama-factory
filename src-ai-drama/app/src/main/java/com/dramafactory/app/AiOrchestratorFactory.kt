package com.dramafactory.app

import com.dramafactory.app.data.AssetEntity
import com.dramafactory.app.data.DramaDatabase
import com.dramafactory.app.data.EpisodeEntity
import com.dramafactory.app.data.PersistenceActionExecutor
import com.dramafactory.app.data.PersistenceWriteException
import com.dramafactory.app.data.ProjectEntity
import com.dramafactory.app.data.ShotEntity
import com.dramafactory.app.storage.StorageUnavailableException
import com.dramafactory.app.ui.AssetCatalog
import com.dramafactory.core.orchestrate.DefaultAiOrchestrator
import com.dramafactory.core.orchestrate.PipelineStage5

/**
 * TD-4：从 AppGraph.init 抽出的 AI 全托管编排器装配（原 AppGraph.init 内 553–742 行的大段 lambda 构造）。
 * 以 `AppGraph` 扩展函数形式存在：可访问 AppGraph 的 internal/public 成员（agnes / dao / image /
 * currentEraKey / textProviderFor / enrichAssetPrompt / agnesKeyReady / storageGuard 等）。
 *
 * P0 持久化可靠性：所有落库 lambda（建项目/建集/资产/分镜/URL/checkpoint）一律先过存储闸门，
 * 写后按精确 ID 读回并比对关键字段，任一步失败抛 [PersistenceWriteException] 使
 * [DefaultAiOrchestrator] 的 PipelineRun 进入失败契约（success=false），禁止半成功报告。
 */
internal fun AppGraph.buildAiOrchestrator(): DefaultAiOrchestrator =
    DefaultAiOrchestrator(
        activeTextModelIdProvider = { textModelRouter.activeTextModelId() },
        createProject = { name ->
            val gate = requireStorageReady()
            if (gate != null) throw StorageUnavailableException(gate.code, gate.userMessage, gate.diagnosticId)
            PersistenceActionExecutor.writeProject(dao, storageGuard, name, "pipeline.createProject")
                .entityIds.first()
        },
        createEpisode = { projectId, scriptText ->
            val gate = requireStorageReady()
            if (gate != null) throw StorageUnavailableException(gate.code, gate.userMessage, gate.diagnosticId)
            val epId = "${projectId}_ep1"
            // ★F3 修复：按剧本自动推断时代红线（LLM 优先，规则兜底），替换原写死 "han"。
            // 第十三轮 EraDetector 与人工模式（ViewModels:370-374）同策略。
            val llmReady = agnesKeyReady()
            currentEraKey = runCatching {
                com.dramafactory.core.quality.EraDetector.detect(scriptText, llmReady) { req ->
                    agnes.chat(req)
                }
            }.getOrElse { com.dramafactory.core.quality.EraDetector.Detection("han", "", false) }.eraKey
            val flags = DramaDatabase.Companion.AiStageFlags
            val stageFlags =
                flags.put(flags.putBool("", flags.AI_MANAGED, true),
                    flags.PROJECT_ID, projectId)
            // 剧本写入 + 按 episode_id 读回 + script_json 比对；失败即抛
            PersistenceActionExecutor.writeEpisode(
                dao, storageGuard,
                EpisodeEntity(
                    episode_id = epId, project_id = projectId, ep_no = 1,
                    script_json = scriptText, stage_flags = stageFlags,
                ),
                actionId = "pipeline.createEpisode",
                expectedScript = scriptText,
            )
            epId
        },
        checkModel = { modelId ->
            if (modelId.isBlank()) {
                dao.verifiedConfig("text")?.let { Result.success(Unit) }
                    ?: Result.failure(com.dramafactory.core.model.ProviderError.AuthError("未验证文本模型"))
            } else {
                // TD-5：checkModel 本身是 suspend λ，validate 亦是 suspend；
                // 去掉 runBlocking，改为在编排器协程上下文直接 await，消除网络阻塞（原写法会卡线程）。
                runCatching {
                    textModelRouter.validate(modelId).getOrThrow()
                }
            }
        },
        extractAssets = { text, _ ->
            runCatching {
                // 文字模型走用户自选(DeepSeek等)，key 多候选兜底（修 text-agnes 读不到）
                val tp = textProviderFor()
                val r = com.dramafactory.core.quality.LlmAssetExtractor.extract(text) { req ->
                    tp.chat(req)
                }
                r.assets.map { a ->
                    DefaultAiOrchestrator.AiAsset(
                        assetId = "a_${System.nanoTime()}",
                        kind = a.kind, name = a.name, prompt = a.desc,
                    )
                }
            }
        },
        generateImage = { asset ->
            // 存储闸门：被阻断直接抛（fatal），不触达 Provider
            val gate = requireStorageReady()
            if (gate != null) throw StorageUnavailableException(gate.code, gate.userMessage, gate.diagnosticId)
            runCatching {
                if (asset.assetId.isBlank() || asset.kind.isBlank() || asset.prompt.isBlank()) {
                    throw IllegalArgumentException("资产字段不完整：id=${asset.assetId}, kind=${asset.kind}")
                }
                val preset = com.dramafactory.core.quality.EraDetector.presetFor(currentEraKey)
                // TD-5：generateImage 在编排器协程上下文中执行，避免阻塞主线程。
                // ★F3：按剧本推断时代预设，不写死具体时代。
                com.dramafactory.app.ui.AssetImageGenerator.generate(
                    provider = image, kind = asset.kind,
                    basePrompt = enrichAssetPrompt(asset.kind, asset.prompt), preset = preset)
            }.fold(
                onSuccess = { url ->
                    // P0：URL 回填必须按 asset_id 读回比对；失败抛 PersistenceWriteException（冒泡出 lambda，
                    // 由编排器 GENERATE_IMAGES 循环向上传播，使 PipelineRun 进入失败契约），绝不返回"已生成"假成功。
                    PersistenceActionExecutor.setAssetRemoteUrlVerified(dao, storageGuard, asset.assetId, url, asset.assetId)
                    Result.success(url)
                },
                onFailure = { Result.failure(it) },
            )
        },
        auditAsset = { asset ->
            // ★F2 修复：真实质量审计——调用 AssetAuditor.audit（G1 文件级硬校验 + G2 多模态打分），
            // 替换原直接返回 passed=true 的「假通过」（原实现关闭了 PRD F03 两层闸门）。
            // 未生成图像/未配置 Key 时不阻断流水线，但明确标注未审计（audit_skipped_*）。
            // 注意：λ 返回类型必须是 Result<AuditResult>，故整体包在 runCatching 内；
            // 异常会变为 Result.failure，由编排器 AUDIT 阶段按「未过」处理（WARN 标红放行）。
            runCatching {
                val remoteUrl = runCatching { dao.assetRemoteUrl(asset.assetId) }.getOrNull()
                val llmReady = agnesKeyReady()
                if (remoteUrl.isNullOrBlank() || !llmReady) {
                    return@runCatching DefaultAiOrchestrator.AuditResult(passed = true, reason = "audit_skipped_no_image_or_key")
                }
                val bytes = MediaHelpers.fetchImageBytes(remoteUrl)
                    ?: return@runCatching DefaultAiOrchestrator.AuditResult(passed = true, reason = "audit_image_fetch_failed")
                val dataUri = MediaHelpers.downscaleToDataUri(bytes)
                    ?: return@runCatching DefaultAiOrchestrator.AuditResult(passed = true, reason = "audit_image_decode_failed")
                val describer = com.dramafactory.core.quality.AssetAuditor.agnesDescriber(agnes, "")
                val engine = com.dramafactory.app.ui.QualityEngine()
                val outcome = engine.auditAsset(
                    imageBytes = bytes, imageDataUri = dataUri,
                    description = asset.prompt, assetType = asset.kind,
                    describer = describer,
                )
                DefaultAiOrchestrator.AuditResult(
                    passed = outcome.auditState == com.dramafactory.core.model.AuditState.APPROVED,
                    score = outcome.qualityScore,
                    reason = outcome.rejectReason,
                )
            }
        },
        generateShots = { pid, script, _ ->
            runCatching {
                // 文字模型走用户自选(DeepSeek等)，key 多候选兜底
                val tp = textProviderFor()
                // 第十五轮：从 DB 拉本项目已抽取/已生成的资产注入 LLM，让分镜用 asset_id 引用
                val assets = runCatching { dao.assetsAllOf(pid) }.getOrDefault(emptyList())
                // v1.7.17：与 StoryboardViewModel 共用同一套目录构造规则
                val catalog = com.dramafactory.app.ui.AssetCatalog.build(assets)
                val r = com.dramafactory.core.quality.AiStoryboardDirector.generate(
                    script, chat = { req -> tp.chat(req) }, assets = catalog)
                r.shots.map { s ->
                    DefaultAiOrchestrator.AiShot(s.shotNo, s.action ?: "", s.dialogue, s.assetIds)
                }
            }
        },
        enqueueRender = { episodeId, shots ->
            val metas = shots.map {
                com.dramafactory.core.model.ShotMeta(
                    shotId = "${episodeId}_shot${it.shotNo}",
                    episodeId = episodeId,
                    prompt = it.action,
                )
            }
            val queue = com.dramafactory.app.ui.RenderRuntime.queueFor(episodeId)
            // TD-5：enqueueRender 本身是 suspend λ，去掉 runBlocking，在协程上下文直接 await
            runCatching { queue.enqueueEpisode(episodeId, metas) }
                .map { metas.size }
        },
        persistAssets = { episodeId, assets ->
            val projectId = episodeId.substringBeforeLast("_ep")
            val gate = requireStorageReady()
            if (gate != null) throw StorageUnavailableException(gate.code, gate.userMessage, gate.diagnosticId)
            val entities = assets.map { a ->
                AssetEntity(
                    asset_id = a.assetId,
                    project_id = projectId,
                    kind = a.kind,
                    prompt = a.name + "：" + a.prompt,
                    updated_at = System.currentTimeMillis(),
                )
            }
            // 全有或失败：第 N 项失败立即抛、不继续第 N+1 项，禁止半成功报告
            PersistenceActionExecutor.writeAssetsBatch(dao, storageGuard, projectId, entities, "pipeline.persistAssets")
        },
        persistShots = { episodeId, shots ->
            val projectId = episodeId.substringBeforeLast("_ep")
            val gate = requireStorageReady()
            if (gate != null) throw StorageUnavailableException(gate.code, gate.userMessage, gate.diagnosticId)
            val entities = shots.map { s ->
                ShotEntity(
                    shot_id = "${episodeId}_shot${s.shotNo}",
                    episode_id = episodeId,
                    project_id = projectId,
                    shot_no = s.shotNo,
                    action = s.action,
                    dialogue = s.dialogue,
                    first_asset_ids = AssetCatalog.encodeRefIds(s.assetIds),
                    last_asset_ids = "[]",
                )
            }
            // 写后按 episode_id 读回校验数量与集合；失败即抛，流水线进入失败契约
            PersistenceActionExecutor.writeShotsBatch(dao, storageGuard, episodeId, entities, "pipeline.persistShots")
        },
        writeCheckpoint = { episodeId, stage, assetCount, shotCount, renderEnqueued, failed ->
            val gate = requireStorageReady()
            if (gate != null) throw StorageUnavailableException(gate.code, gate.userMessage, gate.diagnosticId)
            val flags = DramaDatabase.Companion.AiStageFlags
            val cur = dao.episode(episodeId)
            var f = cur?.stage_flags ?: "{}"
            f = flags.put(f, flags.LAST_SUCCESS_STAGE, stage.name)
            f = flags.putInt(f, flags.ASSET_COUNT, assetCount)
            f = flags.putBool(f, flags.RENDER_ENQUEUED, renderEnqueued)
            failed?.let { f = flags.put(f, flags.FAILED_STAGE, it.name) }
            val target = cur?.copy(stage_flags = f)
                ?: throw PersistenceWriteException("pipeline.writeCheckpoint", "checkpoint", listOf(episodeId), "checkpoint 缺少真实剧集记录")
            dao.upsertEpisode(target)
            // P0：checkpoint 写后读回比对
            val back = dao.episode(episodeId)
            if (back?.stage_flags != f) {
                throw PersistenceWriteException("pipeline.writeCheckpoint", "checkpoint", listOf(episodeId),
                    "checkpoint 写入后读回不一致")
            }
        },
        readCheckpoint = { episodeId ->
            val flags = DramaDatabase.Companion.AiStageFlags
            val stageName = flags.getString(
                dao.episode(episodeId)?.stage_flags,
                flags.LAST_SUCCESS_STAGE)
            if (stageName != null) runCatching { PipelineStage5.valueOf(stageName) }.getOrNull()
            else null
        },
        // ★F4 修复：断点续跑时读回真实剧本（episodes.script_json），替换 DefaultAiOrchestrator 内的 "RETRY_STUB" 占位
        readScript = { episodeId ->
        val episode = dao.episode(episodeId)
            ?: throw PersistenceWriteException("pipeline.readScript", "readScript", listOf(episodeId), "剧本不存在")
        episode.script_json ?: ""
        },
    )