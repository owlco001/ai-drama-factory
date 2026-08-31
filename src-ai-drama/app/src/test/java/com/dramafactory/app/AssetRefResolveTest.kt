package com.dramafactory.app

import com.dramafactory.app.data.AssetEntity
import com.dramafactory.app.ui.AssetCatalog
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * v1.7.21 回归单测：渲染侧「资产引用 → 参考图 URI」的解析。
 *
 * 背景（实测复现的两个真问题）：
 *  1. 旧实现内联在 ViewModels.setAssetImageResolver 里，只做
 *     `rows.filter { asset_id in refIds }`——按 id 精确匹配、不展开子卡。
 *     而资产目录（AssetCatalog.build）只给母卡，于是 refIds 里永远是母卡 id，
 *     v1.7.20 生成的 4 张参考图**一张都进不了渲染**：花 4 张图的额度做参考图套装，
 *     锁脸却还是只靠母卡那一张图，参考图成了死资产。
 *  2. 取不到图时回退「项目级 character/scene 前 4 张」，多角色项目里
 *     等于把**另一个角色**的脸当参考注入，直接串脸——比不注入更糟。
 */
class AssetRefResolveTest {

    private fun row(
        id: String,
        kind: String = "character",
        prompt: String = "角色",
        parentId: String? = null,
        poseRole: String? = null,
        remoteUrl: String? = "https://img/$id.png",
        imageUri: String? = null,
        review: String = "none",
    ) = AssetEntity(
        asset_id = id, project_id = "p1", kind = kind, parent_id = parentId,
        pose_role = poseRole, prompt = prompt, remote_url = remoteUrl,
        image_uri = imageUri, review_state = review, updated_at = 0L)

    private val refKeys = listOf("front_bust", "side_45_right", "profile_side", "front_full_body")

    /** 母卡（可指定有无图）+ 完整参考图套装 */
    private fun characterWithSheet(
        motherId: String,
        motherHasImage: Boolean = true,
        refWithImage: Set<String> = refKeys.toSet(),
    ): List<AssetEntity> {
        val rows = mutableListOf<AssetEntity>()
        rows += row(motherId, remoteUrl = if (motherHasImage) "https://img/$motherId.png" else null)
        for (k in refKeys) {
            rows += row("${motherId}_$k", prompt = "角色，$k", parentId = motherId, poseRole = k,
                remoteUrl = if (k in refWithImage) "https://img/$motherId/$k.png" else null)
        }
        return rows
    }

    @Test fun `引用母卡时展开参考图，front_bust 排第一`() {
        val rows = characterWithSheet("c1")
        val uris = AssetCatalog.resolveRefUris(rows, listOf("c1"))
        assertEquals(listOf("https://img/c1/front_bust.png", "https://img/c1/side_45_right.png"), uris,
            "锁脸基准图必须排第一；单角色最多 2 张，给场景卡留配额")
    }

    @Test fun `母卡无图但参考图已生成时仍能取到图`() {
        // 旧实现只按 id 精确匹配母卡，母卡无图就返回空 → 该镜彻底不锁脸。
        val rows = characterWithSheet("c1", motherHasImage = false)
        val uris = AssetCatalog.resolveRefUris(rows, listOf("c1"))
        assertTrue(uris.isNotEmpty(), "母卡无图时必须用参考图兜底，不能让该镜不锁脸")
        assertEquals("https://img/c1/front_bust.png", uris.first())
    }

    @Test fun `参考图缺失时补母卡自身的图`() {
        val rows = characterWithSheet("c1", refWithImage = setOf("front_bust"))
        val uris = AssetCatalog.resolveRefUris(rows, listOf("c1"))
        assertEquals(2, uris.size)
        assertTrue(uris.contains("https://img/c1.png"), "参考图不足时应补母卡图")
    }

    @Test fun `角色加场景时配额分配且不超上限`() {
        val rows = characterWithSheet("c1") +
            row("s1", kind = "scene", prompt = "破庙内景", remoteUrl = "https://img/s1.png")
        val uris = AssetCatalog.resolveRefUris(rows, listOf("c1", "s1"))
        assertEquals(3, uris.size, "角色 2 张 + 场景 1 张")
        assertTrue(uris.contains("https://img/s1.png"), "场景图不能被角色参考图挤掉")
        assertEquals(2, AssetCatalog.resolveRefUris(rows, listOf("c1", "s1"), limit = 2).size, "limit 生效")
    }

    @Test fun `被判重生成的参考图不参与锁脸`() {
        val rows = characterWithSheet("c1").map {
            if (it.pose_role == "front_bust") it.copy(review_state = "regen") else it
        }
        val uris = AssetCatalog.resolveRefUris(rows, listOf("c1"))
        assertFalse(uris.contains("https://img/c1/front_bust.png"), "被用户否决的图不能再当参考")
        assertTrue(uris.isNotEmpty())
    }

    @Test fun `母卡被判重生成时不取其图但仍可用参考图`() {
        val rows = characterWithSheet("c1").map {
            if (it.parent_id == null) it.copy(review_state = "regen") else it
        }
        val uris = AssetCatalog.resolveRefUris(rows, listOf("c1"))
        assertFalse(uris.contains("https://img/c1.png"))
        assertEquals("https://img/c1/front_bust.png", uris.first())
    }

    @Test fun `无引用或 id 不存在时返回空，不擅自注入`() {
        val rows = characterWithSheet("c1")
        assertTrue(AssetCatalog.resolveRefUris(rows, emptyList()).isEmpty())
        assertTrue(AssetCatalog.resolveRefUris(rows, listOf("不存在的id")).isEmpty())
    }

    @Test fun `兜底在多角色项目禁用，避免串脸`() {
        val rows = characterWithSheet("c1") + characterWithSheet("c2")
        assertTrue(AssetCatalog.fallbackUris(rows).isEmpty(),
            "多角色项目回退「项目级前4张」会把另一个角色的脸注入进来，直接串脸")
    }

    @Test fun `兜底在单角色项目可用`() {
        val rows = characterWithSheet("c1") +
            row("s1", kind = "scene", remoteUrl = "https://img/s1.png")
        val uris = AssetCatalog.fallbackUris(rows)
        assertTrue(uris.contains("https://img/s1.png"), "单角色时可安全兜底")
        assertFalse(uris.any { it.contains("/c1/") }, "兜底只取母卡，不取参考图子卡")
    }

    // ---- 目录侧：只生成了参考图、母卡无图的角色不能从目录里消失 ----

    @Test fun `母卡无图但参考图已生成时仍进目录`() {
        // 旧规则按「自身有无图」过滤，用户在资产页点「参考图」却没单独生成母卡图时，
        // 这个角色会彻底从目录消失 → 分镜一镜都不引用它 → 渲染无参考图。
        val rows = characterWithSheet("c1", motherHasImage = false)
        assertEquals(listOf("c1"), AssetCatalog.build(rows).map { it.id },
            "参考图已出图的角色必须留在目录里，否则它永远不会被引用")
    }

    @Test fun `母卡与参考图都无图时不进目录`() {
        val rows = characterWithSheet("c1", motherHasImage = false, refWithImage = emptySet())
        assertTrue(AssetCatalog.build(rows).isEmpty(), "彻底没图的资产仍不该进目录")
    }

    @Test fun `目录仍只留母卡且排除重生成`() {
        val rows = characterWithSheet("c1")
        assertEquals(listOf("c1"), AssetCatalog.build(rows).map { it.id }, "参考图子卡不得进目录")
        val regen = rows.map { if (it.asset_id == "c1") it.copy(review_state = "regen") else it }
        assertTrue(AssetCatalog.build(regen).isEmpty(), "被否决的母卡不进目录")
    }
}
