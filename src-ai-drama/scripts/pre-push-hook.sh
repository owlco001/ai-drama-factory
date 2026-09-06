#!/usr/bin/env bash
# ============================================================================
# TD-2 / 清单步骤3（选项A）：pre-push 守卫
# 推送前自动跑 :core-engine:check（内含 checkSilentTests 字节码守卫），
# 若发现「被 JUnit 静默丢弃的非 void @Test」或任何测试失败，则拦截本次 push。
# 目的：让 TD-1/TD-2 的守卫真正自动跑，不依赖人记得。
# 反向验证：把 MultiVideoProviderTest 的 reconcile 用例末行 Unit 去掉 → push 被拦。
# ============================================================================
set -euo pipefail

REPO_ROOT="$(git rev-parse --show-toplevel)"
cd "$REPO_ROOT"

# 构建环境（阿里云镜像/代理/SDK 路径）：优先仓库内 build-env.sh，
# 其次沙箱约定的 /workspace/build-env.sh。缺失则依赖调用方已注入的环境。
for f in build-env.sh /workspace/build-env.sh; do
  if [ -f "$f" ]; then
    # shellcheck disable=SC1091
    source "$f" >/dev/null 2>&1 || true
    break
  fi
done

# 优先使用系统 gradle（沙箱官方源被墙，./gradlew 无法下载发行包）；
# 团队环境若无系统 gradle 则回退 ./gradlew。
GRADLE_BIN="$(command -v gradle || true)"
if [ -z "$GRADLE_BIN" ]; then GRADLE_BIN="./gradlew"; fi

echo "[pre-push] 运行 :core-engine:check（含 checkSilentTests 守卫）via $GRADLE_BIN ..."
if "$GRADLE_BIN" :core-engine:check --console=plain; then
  echo "[pre-push] ✅ 守卫通过，允许推送。"
  exit 0
else
  echo "[pre-push] ❌ :core-engine:check 失败，已拦截推送。请先修复后再 push。" >&2
  exit 1
fi
