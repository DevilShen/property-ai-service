import { computed, ref } from 'vue'
import { api, ApiError } from '@/api'
import type { BillInfo, DegradedInfo, OwnerVerifyResult, RoomInfo } from '@/api'

/**
 * 业主端会话态（核验结论 + 账单 + 房档）
 * ---------------------------------------------------------------------------
 * 契约口径（v0.3.0 / shared §11）：
 * - 核验因子为手机号后四位（CAP-02，演示因子，不发真实短信）；
 *   核验成功后**5 分钟内在会话内复用**（再次查账单/报修不再重复核验）；
 * - 核验失败不提示错因，只提示剩余次数（`attemptsLeft`）；连续 3 次失败/已锁定
 *   （403 E-2001）→ 前端提示「已转人工」，不展示失败原因；
 * - 账单金额、差额、引用全部由 ERP 给出，前端只格式化，**不自算、不编造**。
 *
 * 实现说明：模块级单例（组合式），随 SPA 会话存活；不写 localStorage
 * （演示环境不在浏览器持久化业务数据）。页面刷新后重新核验，符合「5 分钟会话」语义。
 */

/** 核验结论会话有效期（契约：5 分钟内复用） */
export const OWNER_VERIFY_TTL_MS = 5 * 60 * 1000

const verification = ref<OwnerVerifyResult | null>(null)
const verifiedAt = ref(0)
const room = ref<RoomInfo | null>(null)
const bill = ref<BillInfo | null>(null)
const billDegraded = ref<DegradedInfo | undefined>(undefined)
const billErrorCode = ref('')

/** 核验是否仍在有效期内（5 分钟会话） */
function sessionValid(): boolean {
  return Boolean(verification.value?.verified) && Date.now() - verifiedAt.value < OWNER_VERIFY_TTL_MS
}

export interface VerifyOutcome {
  /** 是否通过 */
  verified: boolean
  /** 原始响应（含 attemptsLeft / locked / ownerNameMasked / projectName） */
  result?: OwnerVerifyResult
  /** 失败时的人话（不提示错因） */
  message?: string
  /** 是否已被锁定/需转人工（403 E-2001 或 locked） */
  locked?: boolean
  /** 失败时的契约错误码（页面转人话/决定是否转人工） */
  code?: string
}

export interface BillOutcome {
  ok: boolean
  bill?: BillInfo
  degraded?: DegradedInfo
  /** 失败时的契约错误码（页面转人话） */
  code?: string
  /** 失败时的人话 */
  message?: string
}

export function useOwnerSession() {
  const verifyResult = computed(() => verification.value)
  const isVerified = computed(() => sessionValid())

  /** 供其他链路（如建单前）确认核验有效；过期即清空，避免用过期的核验结论建单 */
  function ensureVerified(): boolean {
    if (sessionValid()) return true
    verification.value = null
    verifiedAt.value = 0
    return false
  }

  /** CAP-02 核验（手机号后四位） */
  async function verify(roomCode: string, phoneTail: string): Promise<VerifyOutcome> {
    try {
      const { data } = await api.owner.verify({ roomCode, phoneTail })
      verification.value = data
      if (data.verified) {
        verifiedAt.value = Date.now()
        billErrorCode.value = ''
        return { verified: true, result: data }
      }
      verifiedAt.value = 0
      const left = data.attemptsLeft
      if (data.locked) {
        return {
          verified: false,
          result: data,
          locked: true,
          message: '核验次数已达上限，已按规则转人工核验（不提示失败原因）',
        }
      }
      return {
        verified: false,
        result: data,
        message:
          typeof left === 'number'
            ? `核验未通过（剩余 ${left} 次），可重试或转人工；连续 3 次失败会自动转人工`
            : '核验未通过，可重试或转人工（不提示失败原因）',
      }
    } catch (error) {
      // 403 E-2001：锁定/未核验；其他（404 E-3001 房号不存在、503 上游不可用）同样不提示错因
      const code = error instanceof ApiError ? error.code : 'E-9001'
      const locked = code === 'E-2001'
      verification.value = null
      verifiedAt.value = 0
      return {
        verified: false,
        locked,
        code,
        message: locked
          ? '核验次数已达上限，已按规则转人工核验（不提示失败原因）'
          : '核验服务暂不可用，已转人工协助（不提示失败原因）',
      }
    }
  }

  /** CAP-01 房档（只读）：带出房号/面积/车位/绑定状态，失败不阻塞主流程 */
  async function loadRoom(roomCode: string): Promise<RoomInfo | null> {
    try {
      const { data } = await api.room.get(roomCode)
      room.value = data
      return data
    } catch {
      return null
    }
  }

  /** CAP-03 账单（只读）：金额/差额/引用来自响应；失败即降级（页面不展示任何金额） */
  async function loadBill(params: { roomCode: string; period: string }): Promise<BillOutcome> {
    try {
      const { data, degraded } = await api.bill.query(params)
      bill.value = data
      billDegraded.value = degraded
      billErrorCode.value = ''
      return { ok: true, bill: data, degraded }
    } catch (error) {
      bill.value = null
      billDegraded.value = error instanceof ApiError ? error.degraded : undefined
      const code = error instanceof ApiError ? error.code : 'E-9001'
      billErrorCode.value = code
      return {
        ok: false,
        code,
        degraded: billDegraded.value,
        message: '账单暂不可查，已转人工',
      }
    }
  }

  function reset() {
    verification.value = null
    verifiedAt.value = 0
    room.value = null
    bill.value = null
    billDegraded.value = undefined
    billErrorCode.value = ''
  }

  return {
    /** 会话内的核验结论（响应原样，未核验为 null） */
    verifyResult,
    /** 是否已核验且未过期（5 分钟） */
    isVerified,
    /** 最近一次房档 */
    room,
    /** 最近一次账单（金额全部来自 ERP） */
    bill,
    billDegraded,
    billErrorCode,
    ensureVerified,
    verify,
    loadRoom,
    loadBill,
    reset,
  }
}
