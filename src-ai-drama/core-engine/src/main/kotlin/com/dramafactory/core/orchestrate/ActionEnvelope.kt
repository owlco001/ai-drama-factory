package com.dramafactory.core.orchestrate

/**
 * AI 动作信封（动作可靠性协议 P0）。
 *
 * 目标：把「[ACT] verb | key=value」这一流式文本指令升级为带版本、动作ID、幂等键、
 * 上下文和结构化结果的信封（envelope），让执行层以结构化结果为准，fail-closed，
 * 不再被「未知动作 / 非法参数 / 上下文不匹配 / 幂等重放」静默吞掉。
 *
 * 兼容性：旧的 [ACT] 跨 chunk 缓冲解析（[parseActions]）保持不变，`StreamingAssistant`
 * 先把整段流式文本拼起来再逐行解析，再经 [decodeEnvelope] 转换为 [ActionEnvelope]。
 * 未携带 actionId / idempotencyKey / protocolVersion 的旧指令同样可解析（缺省自动补）。
 *
 * 参数仍沿用 [ACT] 的 key=value 文本协议（args 为 Map<String,String>）；需要结构化
 * 值时可把 JSON 字符串作为值传入。
 */
const val ACTION_PROTOCOL_VERSION = 1

/** 动作执行状态。无法判定一律 FAILED / BLOCKED（fail-closed）。 */
enum class ActionStatus { ACCEPTED, RUNNING, SUCCEEDED, FAILED, BLOCKED }

/**
 * 动作信封：协议版本、动作ID、动词、结构化参数、幂等键、项目/剧集上下文。
 * 相同 [idempotencyKey] 不得重复创建或提交。
 */
data class ActionEnvelope(
    val protocolVersion: Int = ACTION_PROTOCOL_VERSION,
    val actionId: String,
    val verb: String,
    val args: Map<String, String> = emptyMap(),
    val idempotencyKey: String? = null,
    val projectId: String? = null,
    val episodeId: String? = null,
)

/** 结构化执行结果：actionId / status / message / entityRefs / errorCode。 */
data class ActionResult(
    val actionId: String,
    val status: ActionStatus,
    val message: String,
    val entityRefs: List<String> = emptyList(),
    val errorCode: String? = null,
)

/** 动作上下文：当前项目/集 + 已见过的幂等键（用于重放去重）。 */
data class ActionContext(
    val projectId: String? = null,
    val episodeId: String? = null,
    val knownIdempotencyKeys: MutableSet<String> = mutableSetOf(),
)

/** Durable idempotency boundary. Implementations must reserve atomically. */
interface ActionIdempotencyStore {
    suspend fun reserve(key: String): Boolean
    suspend fun complete(key: String)
    suspend fun release(key: String)
}

/** Shared process registry; cross-process replay requires a persisted unique table. */
object SharedActionIdempotency {
    private val keys = mutableSetOf<String>()
    @Synchronized fun contains(key: String): Boolean = key in keys
    @Synchronized fun mark(key: String) { keys += key }
}

/** 解码结果：合法信封或 fail-closed 的拒绝结果。 */
sealed interface DecodedAction {
    data class Ok(val envelope: ActionEnvelope) : DecodedAction
    data class Rejected(val result: ActionResult) : DecodedAction
}

/** 信封专用参数名（不进入 args，作为信封元数据）。 */
private val RESERVED_PARAMS = setOf("actionId", "idempotencyKey", "projectId", "episodeId", "protocolVersion")

/** 需要项目上下文的动作（缺 projectId 即 FAILED，fail-closed）。 */
private val CONTEXT_REQUIRED = setOf(
    "set_script", "extract_assets", "generate", "edit_asset", "remove_asset",
    "review_pass", "review_all_pass", "build_pose_pack", "gen_shots", "render",
    "render_status", "render_pause", "render_resume", "compose_film", "run_pipeline",
    "list_assets", "set_cross_era",
)

/** 需要剧集上下文的动作（缺 episodeId 即 FAILED）。 */
private val EPISODE_REQUIRED = setOf(
    "render_status", "render_pause", "render_resume", "compose_film",
)

private fun String?.orIfBlank(other: String?): String? = if (this.isNullOrBlank()) other else this

/**
 * 把一条已解析的 [ACT] 指令解码为 [ActionEnvelope]。
 *
 * 校验顺序（fail-closed，任一不通过即返回 [DecodedAction.Rejected]）：
 * 1. 协议版本不支持 → BLOCKED(UNSUPPORTED_PROTOCOL)
 * 2. 未知动作 → BLOCKED(UNKNOWN_ACTION)
 * 3. 必要参数缺失 → FAILED(INVALID_ARGUMENTS)
 * 4. 上下文不匹配（缺项目/剧集）→ FAILED(INVALID_ARGUMENTS)
 * 5. 幂等键重复 → BLOCKED(DUPLICATE_IDEMPOTENCY)
 *
 * 幂等键只在动作执行成功后登记；解码不会消费幂等键，失败动作可重试。
 */
fun decodeEnvelope(intent: ActionIntent, context: ActionContext = ActionContext()): DecodedAction {
    val verb = intent.verb.trim()
    if (verb.isBlank()) {
        return DecodedAction.Rejected(failureResult("", "空动作动词", "EMPTY_VERB"))
    }
    val protoRaw = intent.param("protocolVersion")
    val idem = intent.param("idempotencyKey")
    val aid = intent.param("actionId") ?: defaultActionId(verb, idem, intent.params)
    val proto = if (protoRaw == null) ACTION_PROTOCOL_VERSION else protoRaw.toIntOrNull()
        ?: return DecodedAction.Rejected(failureResult(aidForInvalidProtocol(intent), "非法动作协议版本：$protoRaw", "INVALID_PROTOCOL"))
    if (proto != ACTION_PROTOCOL_VERSION) {
        return DecodedAction.Rejected(
            failureResult(aid, "不支持的动作协议版本 v$proto（当前支持 v$ACTION_PROTOCOL_VERSION）", "UNSUPPORTED_PROTOCOL"))
    }
    if (verb !in KNOWN_ACTIONS) {
        return DecodedAction.Rejected(failureResult(aid, "未知动作：$verb", "UNKNOWN_ACTION"))
    }
    val vErr = validationError(intent, context)
    if (vErr != null) {
        return DecodedAction.Rejected(failureResult(aid, vErr, "INVALID_ARGUMENTS"))
    }
    if (idem != null && (idem in context.knownIdempotencyKeys || SharedActionIdempotency.contains(idem))) {
        return DecodedAction.Rejected(
            failureResult(aid, "幂等键已处理过：$idem，禁止重复执行", "DUPLICATE_IDEMPOTENCY"))
    }
    val args = intent.params.filterKeys { it !in RESERVED_PARAMS }
    return DecodedAction.Ok(
        ActionEnvelope(
            protocolVersion = proto,
            actionId = aid,
            verb = verb,
            args = args,
            idempotencyKey = idem,
            projectId = intent.param("projectId").orIfBlank(context.projectId),
            episodeId = intent.param("episodeId").orIfBlank(context.episodeId),
        ))
}

/** 在结构化动作执行成功后登记幂等键；失败/阻断结果保持可重试。 */
fun ActionContext.markSucceeded(envelope: ActionEnvelope, result: ActionResult) {
    if (result.status == ActionStatus.SUCCEEDED) envelope.idempotencyKey?.let {
        knownIdempotencyKeys += it
        SharedActionIdempotency.mark(it)
    }
}

private fun aidForInvalidProtocol(intent: ActionIntent): String =
    intent.param("actionId") ?: defaultActionId(intent.verb.trim(), intent.param("idempotencyKey"), intent.params)

/** 逐动作参数 + 上下文校验；返回失败原因（可读），通过返回 null。 */
private fun validationError(intent: ActionIntent, context: ActionContext): String? {
    val verb = intent.verb
    val p = intent::param
    val missingArg = when (verb) {
        "new_project" -> if (p("name").isNullOrBlank()) "name" else null
        "set_script" -> if (p("text").isNullOrBlank() && p("script").isNullOrBlank()) "text" else null
        "test_drama" -> if (p("script").isNullOrBlank() && p("text").isNullOrBlank()) "script" else null
        "open_project" -> if (p("id").isNullOrBlank()) "id" else null
        "generate" -> if (p("assetId").isNullOrBlank()) "assetId" else null
        "edit_asset" ->
            if (p("assetId").isNullOrBlank()) "assetId"
            else if (p("prompt").isNullOrBlank()) "prompt"
            else null
        "remove_asset" -> if (p("assetId").isNullOrBlank()) "assetId" else null
        "review_pass" -> if (p("assetId").isNullOrBlank()) "assetId" else null
        "build_pose_pack" ->
            if (p("characterId").isNullOrBlank() && p("assetId").isNullOrBlank()) "characterId" else null
        "set_cross_era" -> if (p("allowed").isNullOrBlank()) "allowed" else null
        else -> null
    }
    if (missingArg != null) return "缺少必要参数：$missingArg"
    if (verb in CONTEXT_REQUIRED) {
        val hasProject = p("projectId").orIfBlank(context.projectId) != null
        if (!hasProject) return "缺少项目上下文（projectId）"
    }
    if (verb in EPISODE_REQUIRED) {
        val hasEp = p("episodeId").orIfBlank(context.episodeId) != null
        if (!hasEp) return "缺少剧集上下文（episodeId）"
    }
    return null
}

private fun failureResult(actionId: String, message: String, errorCode: String): ActionResult =
    ActionResult(actionId, ActionStatus.FAILED, message, emptyList(), errorCode)

/** 未携带 actionId 时的确定性生成规则（保证同动词+同幂等键可复现）。 */
private fun defaultActionId(verb: String, idem: String?, args: Map<String, String>): String {
    val seed = idem ?: args.toSortedMap().toString().hashCode().toUInt().toString(16)
    return "act_${verb}_$seed"
}