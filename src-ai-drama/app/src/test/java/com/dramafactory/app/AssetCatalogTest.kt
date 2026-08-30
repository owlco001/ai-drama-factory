package com.dramafactory.app

import com.dramafactory.app.data.AssetEntity
import com.dramafactory.app.ui.AssetCatalog
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * v1.7.17 回归单测：分镜生成的资产目录构造 + 资产引用序列化。
 *
 * 背景：此前目录把 参考图子卡和还没生图的空卡一起喂给 LLM——
 * LLM 引用侧脸/怒容子卡会让跨镜长相漂移，引用空卡则渲染时静默无参考图。
 */
class AssetCatalogTest {

    private fun row(
        id: String,
        kind: String = "character",
        prompt: String = "张角",
        parentId: String? = null,
        poseRole: String? = null,
        remoteUrl: String? = "https://img/$id.png",
        imageUri: String? = null,
        review: String = "none",
    ) = AssetEntity(
        asset_id = id, project_id = "p1", kind = kind, parent_id = parentId,
        pose_role = poseRole, prompt = prompt, remote_url = remoteUrl,
        image_uri = imageUri, review_state = review, updated_at = 0L)

    @Test fun `排除 参考图子卡，只留母卡`() {
        val rows = listOf(
            row("a_mother"),
            row("a_pose1", prompt = "张角，正面立姿", parentId = "a_mother", poseRole = "front_anchor"),
            row("a_pose2", prompt = "张角，45度侧脸", parentId = "a_mother", poseRole = "side_45"),
        )
        val ids = AssetCatalog.build(rows).map { it.id }
        assertEquals(listOf("a_mother"), ids, "目录里出现 7 张同名张角会让 LLM 引到侧脸/怒容子卡")
    }

    @Test fun `排除尚未生图的资产`() {
        val rows = listOf(
            row("a_got", remoteUrl = "https://img/a.png"),
            row("a_pending", remoteUrl = null, imageUri = null),
            row("a_local_only", remoteUrl = null, imageUri = "file:///sdcard/x.jpg"),
        )
        val ids = AssetCatalog.build(rows).map { it.id }
        assertEquals(listOf("a_got", "a_local_only"), ids, "引用未生图的资产，渲染时会被静默丢掉")
    }

    @Test fun `排除判了重生成的资产`() {
        val rows = listOf(row("a_keep", review = "keep"), row("a_regen", review = "regen"))
        assertEquals(listOf("a_keep"), AssetCatalog.build(rows).map { it.id })
    }

    @Test fun `kind 归一化且未知值归 local`() {
        val rows = listOf(
            row("a1", kind = "CHARACTER"), row("a2", kind = "scene"),
            row("a3", kind = "prop"), row("a4", kind = "weird"),
        )
        assertEquals(listOf("character", "scene", "prop", "local"), AssetCatalog.build(rows).map { it.kind })
    }

    @Test fun `name 按冒号逗号逐级降级提取`() {
        assertEquals("张角", AssetCatalog.displayName("张角：灰袍道长左脸有疤"))
        assertEquals("张角", AssetCatalog.displayName("张角: 灰袍道长"))
        assertEquals("张角", AssetCatalog.displayName("张角，正面立姿（front anchor）"))
        assertEquals("破庙内景", AssetCatalog.displayName("破庙内景"))
        assertEquals("未命名", AssetCatalog.displayName(null))
        assertTrue(AssetCatalog.displayName("一".repeat(50)).length <= 12, "超长 prompt 不应整段当名字")
    }

    @Test fun `encodeRefIds 输出标准 JSON 数组`() {
        assertEquals("""["a_1","a_2"]""", AssetCatalog.encodeRefIds(listOf("a_1", "a_2")))
        assertEquals("[]", AssetCatalog.encodeRefIds(emptyList()))
        assertEquals("""["a_1"]""", AssetCatalog.encodeRefIds(listOf("a_1", "a_1")), "去重")
    }

    @Test fun `parseRefIds 兼容带引号新数据与无引号历史数据`() {
        assertEquals(listOf("a_1", "a_2"), AssetCatalog.parseRefIds("""["a_1","a_2"]"""))
        assertEquals(listOf("a_1", "a_2"), AssetCatalog.parseRefIds("[a_1,a_2]"))
        assertEquals(emptyList(), AssetCatalog.parseRefIds("[]"))
        assertEquals(emptyList(), AssetCatalog.parseRefIds(null))
        assertEquals(emptyList(), AssetCatalog.parseRefIds("  "))
    }

    @Test fun `引用序列化往返一致`() {
        val ids = listOf("a_1", "a_2", "a_3")
        assertEquals(ids, AssetCatalog.parseRefIds(AssetCatalog.encodeRefIds(ids)))
    }
}
