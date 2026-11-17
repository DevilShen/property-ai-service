import { ref } from 'vue'

/**
 * 幂等键 composable（契约 v0.3.0「写操作必须带 Idempotency-Key」）
 * ---------------------------------------------------------------------------
 * 口径：
 * - 键格式 `ui-<uuid>`（`crypto.randomUUID()`，≥8 字符，符合契约 8—64 字符）；
 * - **同一个用户动作重试复用同键**：以「动作种子」标识一次动作（如
 *   `ticket.create|3-2-1802|plumbing|卫生间|今天 16:00—18:00`）。
 *   同种子再次调用 → 复用首次生成的键（重试/双击不会重复建单）；
 *   种子变化（改了报修要素/换了工单/换了评分）→ 视为新动作，换新键，避免 409 E-3003（同键不同载荷）；
 * - 动作成功落库后调用 `settle()` 释放，下一次提交重新生成键。
 *
 * 注：不使用 `sessionStorage` 持久化——契约要求的是「同一动作重试」，跨刷新重放由服务端 24h 幂等表兜底；
 * 若刷新后重新提交同一动作，服务端会按同房 24h 重复建单（422 E-1001）给出既有工单，前端展示该单号即可。
 */
export interface IdempotencyKeyHandle {
  /** 当前动作键（未生成时为空串；仅用于展示/排障） */
  readonly key: () => string
  /** 取该动作种子的幂等键：同种子复用，新种子重新生成 */
  ensure: (seed: string) => string
  /** 动作已成功落库/取消：释放当前键，下一次提交重新生成 */
  settle: () => void
}

function randomKey(): string {
  const uuid =
    typeof crypto !== 'undefined' && typeof crypto.randomUUID === 'function'
      ? crypto.randomUUID()
      : // 兜底：非安全上下文（http + 内网 IP）没有 crypto.randomUUID 时用 getRandomValues 拼 UUID v4
        (() => {
          const bytes = new Uint8Array(16)
          if (typeof crypto !== 'undefined' && crypto.getRandomValues) crypto.getRandomValues(bytes)
          else for (let i = 0; i < 16; i += 1) bytes[i] = Math.floor(Math.random() * 256)
          bytes[6] = (bytes[6]! & 0x0f) | 0x40
          bytes[8] = (bytes[8]! & 0x3f) | 0x80
          const hex = [...bytes].map((byte) => byte.toString(16).padStart(2, '0')).join('')
          return `${hex.slice(0, 8)}-${hex.slice(8, 12)}-${hex.slice(12, 16)}-${hex.slice(16, 20)}-${hex.slice(20)}`
        })()
  return `ui-${uuid}`
}

/** 生成一个幂等键（无状态场景用；有重试语义的写操作请用 {@link useIdempotencyKey}） */
export function createIdempotencyKey(): string {
  return randomKey()
}

/** 把动作要素压成稳定种子（键排序，避免字段顺序不同被判成不同动作） */
export function actionSeed(action: string, payload: Record<string, unknown> = {}): string {
  const parts = Object.keys(payload)
    .sort()
    .map((key) => `${key}=${String(payload[key] ?? '')}`)
  return [action, ...parts].join('|')
}

/** 幂等键状态机：同一动作重试复用同键，动作成功后释放 */
export function useIdempotencyKey(): IdempotencyKeyHandle {
  const currentKey = ref('')
  const currentSeed = ref('')

  return {
    key: () => currentKey.value,
    ensure(seed: string): string {
      if (!currentKey.value || currentSeed.value !== seed) {
        currentSeed.value = seed
        currentKey.value = randomKey()
      }
      return currentKey.value
    },
    settle() {
      currentKey.value = ''
      currentSeed.value = ''
    },
  }
}
