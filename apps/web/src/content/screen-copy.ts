import { SCREENS, SCREEN_ORDER } from '@/app/screens'
import type { ScreenKey } from '@/types'
import type { StateCopy } from './state-copy'
import { SCREEN_OBJECT, degradedCopy, emptyCopy, errorCopy } from './state-copy'

/**
 * 12 屏「面向用户」的页面说明与状态文案（P1-036）
 * ---------------------------------------------------------------------------
 * 约定：
 * - **标题不在这里重复维护**：`title` 一律从 `@/app/screens` 读取（`screenTitle()` / `screenCopy()`），
 *   避免两套标题漂移；本文件只补“用户视角的一句话说明 + 四态文案”。
 * - 说明里不出现批次编号与未完成标记，也不承诺尚未接入的能力。
 * - 空 / 错 / 降级文案直接复用 `state-copy.ts` 的屏级映射，页面不得再写第二份。
 */

/** 每屏“用户视角”的一句话说明（screens.description 的补充口径，不改 screens.ts） */
export const SCREEN_INTRO: Readonly<Record<ScreenKey, string>> = {
  overview: '一套 AI + 一个适配层 + N 套 ERP：先看怎么建、怎么接、怎么验收，再决定从哪个业务闭环开始。',
  owner: '业主只面对一个智能管家：一句话说需求，AI 识别账单 / 报修 / 查进度 / 转人工，再按规则核验身份后答复。',
  progress: '一张工单从受理到沉淀的 7 个节点：谁在做、系统如何回写、业主看到什么，都在同一条时间轴上。',
  copilot: '管家的员工端副驾，8 个工作区各管一件事：知识秒查、话术生成、工单摘要、业主画像、日报周报、群服务、知识共建、历史会话。',
  desk: '人工坐席的工作台：三队列、上下文同屏、分级与 SLA、质检；转人工不断线，AI 摘要先到位。',
  billing: '欠费台账、分层触达、催缴话术与渠道红线；只做提醒与登记，不碰钱、不做减免。',
  erp: '5 套 ERP 的连接器与 18 项标准能力：能力由探测确认，缺什么就降级或隐藏入口，不做“假支持”。',
  mapping: '把 ERP 的字段、状态、字典翻译成平台标准口径；发布前先跑 200 条样本，差异清零才允许上线。',
  orchestration: '一次请求的 7 跳链路、项目 → ERP 路由表、规则开关与降级台账；每一步都留痕可回溯。',
  trace: '按 trace id 回放会话与接口调用：知识引用、版本、护栏判断与证据包，一屏看全。',
  knowledge: '知识不是写完就完：版本、生效期、责任人、过期降级、缺口与 badcase，都要有人管。',
  dashboard: '按业务指标验收，不看对话量：自助解决、工单质量、缴费转化、收缴率与 ROI。',
}

/** 单屏完整文案（标题 + 说明 + 四态） */
export interface ScreenCopy {
  key: ScreenKey
  /** 页面标题：唯一来源 `@/app/screens` */
  title: string
  /** 用户视角一句话说明 */
  intro: string
  /** 本屏数据对象名（用于错误 / 降级句式） */
  object: string
  empty: StateCopy
  error: StateCopy
  degraded: StateCopy
}

/** 读取页面标题：唯一事实源仍是 screens.ts */
export function screenTitle(key: ScreenKey): string {
  return SCREENS[key].title
}

/** 读取单屏文案（标题 + 说明 + 四态） */
export function screenCopy(key: ScreenKey): ScreenCopy {
  return {
    key,
    title: screenTitle(key),
    intro: SCREEN_INTRO[key],
    object: SCREEN_OBJECT[key],
    empty: emptyCopy(key),
    error: errorCopy(key),
    degraded: degradedCopy(key),
  }
}

/** 12 屏文案表（按键索引，供 UiKit / 测试 / 后续页面统一引用） */
export const SCREEN_COPY: Readonly<Record<ScreenKey, ScreenCopy>> = Object.fromEntries(
  SCREEN_ORDER.map((key) => [key, screenCopy(key)]),
) as Record<ScreenKey, ScreenCopy>

/** 12 屏文案列表（保持 01—12 顺序，便于表格渲染） */
export const SCREEN_COPY_LIST: readonly ScreenCopy[] = SCREEN_ORDER.map((key) => SCREEN_COPY[key])
