import { computed, ref, type Ref } from 'vue'
import { COPILOT_WORKSPACES, COPILOT_WORKSPACE_ORDER } from '@/app/copilot'
import { canAccess } from '@/app/permissions'
import { SCREENS, SCREEN_ORDER } from '@/app/screens'
import type { IconName, Role } from '@/types'

/**
 * P1-026 全局搜索：静态演示索引 + 关键词匹配排序 + 高亮切分 + 最近搜索。
 *
 * 约束（README/AGENTS）：
 * - 索引为**演示数据（mock）**，不接真实搜索服务；知识/工单/会话条目口径对齐
 *   `mock/data/knowledge.json`、`mock/data/tickets.json` 与 Copilot「历史会话」工作区，
 *   不写真实业主手机号、不编造金额；
 * - 权限过滤是**占位**：用 `canAccess(role, route)` 过滤当前角色不可见的目标路由，
 *   面板底部明确标注「占位，非真实鉴权」；
 * - 关键词高亮用 `<mark>`，由 `highlightSegments` 切分，样式在 GlobalSearch.vue。
 */

/** 结果分组（顺序即展示顺序） */
export type SearchGroupKey = 'knowledge' | 'ticket' | 'session' | 'page' | 'workspace'

/** 与 WTag 的 tone 对齐 */
export type SearchTone = 'g' | 'b' | 'a' | 'r' | 'v' | 'n'

export interface SearchItem {
  id: string
  group: SearchGroupKey
  title: string
  /** 次级信息：知识 ID·版本·分类 / 工单号·房号·班组 / 业主·traceId / 路由 */
  meta: string
  /** 右侧标记：版本 / 状态 / 结果 / 分组 */
  badge: string
  badgeTone: SearchTone
  icon: IconName
  /** 参与匹配的补充关键词 */
  keywords: string[]
  /** 命中后的最终跳转路由（已按角色解析） */
  route: string
}

export interface SearchGroupBlock {
  key: SearchGroupKey
  label: string
  /** 该分组命中总数（items 可能被截断展示） */
  total: number
  items: SearchItem[]
}

export interface HighlightSegment {
  text: string
  hit: boolean
}

export const SEARCH_GROUP_ORDER: SearchGroupKey[] = ['knowledge', 'ticket', 'session', 'page', 'workspace']

export const SEARCH_GROUP_LABELS: Record<SearchGroupKey, string> = {
  knowledge: '知识',
  ticket: '工单',
  session: '会话',
  page: '页面',
  workspace: 'Copilot 工作区',
}

/** 每个分组最多展示条数（footer 仍显示命中总数） */
export const SEARCH_GROUP_LIMIT = 5

export const RECENT_SEARCH_KEY = 'property:recent-search'
export const RECENT_SEARCH_LIMIT = 5

/** 索引条目定义：routes 为候选目标，按优先级取第一个当前角色可访问者 */
interface IndexedItem {
  id: string
  group: SearchGroupKey
  title: string
  meta: string
  badge: string
  badgeTone: SearchTone
  icon: IconName
  keywords: string[]
  routes: string[]
}

/* ------------------------------ 静态演示索引（mock） ------------------------------ */

interface KnowledgeSeed {
  id: string
  title: string
  version: string
  category: string
  owner: string
  keywords: string[]
  expired?: boolean
}

/** 知识 8 条：ID/标题/版本/分类/责任人与 mock/data/knowledge.json 一致 */
const KNOWLEDGE_SEEDS: KnowledgeSeed[] = [
  {
    id: 'K-001',
    title: '物业费单价',
    version: 'V1.0',
    category: '收费类',
    owner: '财务·周',
    keywords: ['物业费', '单价', '2.50', '建筑面积', '包干制'],
  },
  {
    id: 'K-002',
    title: '公摊电费',
    version: 'V1.1',
    category: '收费类',
    owner: '财务·周',
    keywords: ['公摊电费', '公摊', '分摊', '公共能耗', '0.38', '四舍五入'],
  },
  {
    id: 'K-003',
    title: '车位费',
    version: 'V1.0',
    category: '收费类',
    owner: '财务·周',
    keywords: ['车位费', '车位服务费', '临停', '月租'],
  },
  {
    id: 'K-004',
    title: '缴费期限',
    version: 'V1.0',
    category: '收费类',
    owner: '财务·周',
    keywords: ['缴费期限', '截止日', '15日', '预缴', '账单'],
  },
  {
    id: 'K-007',
    title: '报修流程',
    version: 'V1.0',
    category: '办事流程',
    owner: '客服·林',
    keywords: ['报修', '工单', '派单', '回执', '3项'],
  },
  {
    id: 'K-008',
    title: '上门时间',
    version: 'V1.0',
    category: '办事流程',
    owner: '工程·许',
    keywords: ['上门时间', '响应', '4小时', '紧急'],
  },
  {
    id: 'K-009',
    title: '停水通知',
    version: 'V1.0',
    category: '动态通知',
    owner: '客服·林',
    keywords: ['停水', '通知', '取水点', '提前24小时'],
    expired: true,
  },
  {
    id: 'K-014',
    title: '滞纳金',
    version: 'V1.0',
    category: '收费类',
    owner: '财务·周',
    keywords: ['滞纳金', '万分之五', '逾期'],
  },
]

/** 工单 6 条：工单号/房号/状态/班组/现象与 mock/data/tickets.json 一致 */
interface TicketSeed {
  no: string
  room: string
  symptom: string
  status: string
  team: string
  aliases: string[]
}

const TICKET_SEEDS: TicketSeed[] = [
  {
    no: 'WO-20261110-0021',
    room: '1-1-101',
    symptom: '卫生间吊顶滴水，已漏到楼下',
    status: '已完工',
    team: '水电二班',
    aliases: ['漏水', '滴水', '给排水'],
  },
  {
    no: 'WO-20261112-0025',
    room: '6-1-401',
    symptom: '卫生间地面渗水，尚未确认是否漏到楼下',
    status: '处理中',
    team: '水电二班',
    aliases: ['漏水', '渗水', '给排水'],
  },
  {
    no: 'WO-20261114-0028',
    room: '2-1-301',
    symptom: '阳台地漏堵塞，排水缓慢',
    status: '已派单',
    team: '水电一班',
    aliases: ['堵塞', '排水'],
  },
  {
    no: 'WO-20261114-0030',
    room: '6-2-702',
    symptom: '单元门刷卡门禁失灵，需人工开门',
    status: '已受理',
    team: '秩序维护',
    aliases: ['门禁', '刷卡'],
  },
  {
    no: 'WO-20261113-0026',
    room: '3-2-902',
    symptom: '运行中有异响并伴随轻微抖动',
    status: '处理中',
    team: '电梯维保（外委）',
    aliases: ['电梯', '异响'],
  },
  {
    no: 'WO-20261111-0023',
    room: '3-1-1103',
    symptom: '配电箱频繁跳闸，无法正常用电',
    status: '已完工',
    team: '水电一班',
    aliases: ['跳闸', '停电', '强电'],
  },
]

const TICKET_TONE: Record<string, SearchTone> = {
  已完工: 'g',
  处理中: 'a',
  已派单: 'b',
  已受理: 'v',
}

/** 会话 5 条：业主/意图/traceId 与 Copilot「历史会话」工作区一致 */
interface SessionSeed {
  time: string
  owner: string
  intent: string
  result: string
  traceId: string
}

const SESSION_SEEDS: SessionSeed[] = [
  { time: '今天 15:02', owner: '王女士 · 3-2-1802', intent: '费用查询 / 公摊解释', result: '自助解决', traceId: 'tr-20261114-1502-8821' },
  { time: '今天 14:36', owner: '李先生 · 4-1-902', intent: '停车月卡续费', result: '转人工', traceId: 'tr-20261114-1436-4410' },
  { time: '今天 11:18', owner: '陈女士 · 6-1-1102', intent: '装修申报材料', result: '自助解决', traceId: 'tr-20261114-1118-2207' },
  { time: '今天 10:05', owner: '赵先生 · 2-3-601', intent: '欠费争议 / 分期', result: '已承诺', traceId: 'tr-20261114-1005-9931' },
  { time: '昨天 17:22', owner: '周女士 · 5-2-1503', intent: '停水通知未收到', result: '自助解决', traceId: 'tr-20261113-1722-5518' },
]

const SESSION_TONE: Record<string, SearchTone> = {
  自助解决: 'g',
  转人工: 'b',
  已承诺: 'a',
}

/** 会话候选跳转：全链路回溯 / 坐席工作台 / Copilot 历史会话（取当前角色第一个可见者） */
const SESSION_ROUTES = ['/trace', '/desk', '/copilot/history']

const RAW_ITEMS: IndexedItem[] = [
  ...KNOWLEDGE_SEEDS.map<IndexedItem>((seed) => ({
    id: `knowledge-${seed.id}`,
    group: 'knowledge',
    title: seed.title,
    meta: `${seed.id} · ${seed.version} · ${seed.category} · ${seed.owner}`,
    badge: seed.expired ? '已过期' : seed.version,
    badgeTone: seed.expired ? 'r' : 'g',
    icon: 'book',
    keywords: seed.keywords,
    routes: ['/knowledge'],
  })),
  ...TICKET_SEEDS.map<IndexedItem>((seed) => ({
    id: `ticket-${seed.no}`,
    group: 'ticket',
    title: seed.symptom,
    meta: `${seed.no} · ${seed.room} · ${seed.team}`,
    badge: seed.status,
    badgeTone: TICKET_TONE[seed.status] ?? 'n',
    icon: 'list-check',
    keywords: [seed.no, seed.room, seed.status, seed.team, ...seed.aliases],
    routes: ['/progress'],
  })),
  ...SESSION_SEEDS.map<IndexedItem>((seed) => ({
    id: `session-${seed.traceId}`,
    group: 'session',
    title: seed.owner,
    meta: `${seed.intent} · ${seed.traceId}`,
    badge: seed.result,
    badgeTone: SESSION_TONE[seed.result] ?? 'n',
    icon: 'activity',
    keywords: [seed.owner, seed.intent, seed.result, seed.traceId, seed.time],
    routes: SESSION_ROUTES,
  })),
  ...SCREEN_ORDER.map<IndexedItem>((key) => {
    const screen = SCREENS[key]
    return {
      id: `page-${screen.key}`,
      group: 'page',
      title: screen.navLabel,
      meta: `${screen.path} · ${screen.title}`,
      badge: screen.group === 'core' ? '业务工作台' : '适配与运营',
      badgeTone: 'n',
      icon: screen.icon,
      keywords: [screen.title, screen.key, screen.eyebrow],
      routes: [screen.path],
    }
  }),
  ...COPILOT_WORKSPACE_ORDER.map<IndexedItem>((key) => {
    const workspace = COPILOT_WORKSPACES[key]
    return {
      id: `workspace-${workspace.key}`,
      group: 'workspace',
      title: workspace.label,
      meta: `${workspace.path} · ${workspace.description}`,
      badge: 'Copilot',
      badgeTone: 'v',
      icon: workspace.icon,
      keywords: [workspace.key, workspace.label],
      routes: [workspace.path],
    }
  }),
]

/* ------------------------------ 权限过滤占位 ------------------------------ */

/** 按候选路由取第一个当前角色可访问的目标（`canAccess` 为可见性占位） */
export function resolveRoute(role: Role, routes: string[]): string | undefined {
  return routes.find((path) => canAccess(role, path))
}

/** 构建当前角色可见的搜索索引（不可见目标的条目直接剔除） */
export function buildSearchIndex(role: Role): SearchItem[] {
  return RAW_ITEMS.flatMap<SearchItem>((item) => {
    const { routes, ...rest } = item
    const route = resolveRoute(role, routes)
    return route ? [{ ...rest, route }] : []
  })
}

/* ------------------------------ 匹配与排序 ------------------------------ */

/** 关键词切分：按空白拆词，全部小写；多词按「且」匹配 */
export function tokenize(query: string): string[] {
  return query.trim().toLowerCase().split(/\s+/).filter(Boolean)
}

function scoreItem(item: SearchItem, tokens: string[]): number {
  const title = item.title.toLowerCase()
  const meta = item.meta.toLowerCase()
  const badge = item.badge.toLowerCase()
  const keywords = item.keywords.map((keyword) => keyword.toLowerCase())

  let score = 0
  for (const token of tokens) {
    let best = 0
    if (title === token) best = 120
    else if (title.startsWith(token)) best = 80
    else if (title.includes(token)) best = 60
    else if (keywords.some((keyword) => keyword === token)) best = 55
    else if (keywords.some((keyword) => keyword.includes(token))) best = 40
    else if (meta.includes(token)) best = 30
    else if (badge.includes(token)) best = 20
    // 有任一 token 未命中即视为不匹配（AND 语义，避免噪声）
    if (best === 0) return 0
    score += best
  }
  return score
}

/** 对索引做关键词匹配 + 打分排序，输出按分组聚合的结果块 */
export function searchIndex(index: SearchItem[], query: string): SearchGroupBlock[] {
  const tokens = tokenize(query)
  if (tokens.length === 0) return []

  const ranked = index
    .map((item, order) => ({ item, order, score: scoreItem(item, tokens) }))
    .filter((entry) => entry.score > 0)
    .sort(
      (a, b) =>
        b.score - a.score ||
        SEARCH_GROUP_ORDER.indexOf(a.item.group) - SEARCH_GROUP_ORDER.indexOf(b.item.group) ||
        a.order - b.order,
    )

  return SEARCH_GROUP_ORDER.flatMap<SearchGroupBlock>((key) => {
    const matched = ranked.filter((entry) => entry.item.group === key)
    if (matched.length === 0) return []
    return [
      {
        key,
        label: SEARCH_GROUP_LABELS[key],
        total: matched.length,
        items: matched.slice(0, SEARCH_GROUP_LIMIT).map((entry) => entry.item),
      },
    ]
  })
}

/* ------------------------------ 高亮切分 ------------------------------ */

const escapeRegExp = (value: string): string => value.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')

/** 把文本按关键词切成命中/未命中片段，供 `<mark>` 渲染 */
export function highlightSegments(text: string, query: string): HighlightSegment[] {
  if (!text) return []
  const tokens = tokenize(query)
  if (tokens.length === 0) return [{ text, hit: false }]

  // 长词优先，避免「公摊电费」被「公摊」抢先切碎
  const pattern = new RegExp(`(${tokens.slice().sort((a, b) => b.length - a.length).map(escapeRegExp).join('|')})`, 'gi')
  const segments: HighlightSegment[] = []
  let lastIndex = 0

  for (const match of text.matchAll(pattern)) {
    const index = match.index ?? 0
    if (index > lastIndex) segments.push({ text: text.slice(lastIndex, index), hit: false })
    segments.push({ text: match[0], hit: true })
    lastIndex = index + match[0].length
  }
  if (lastIndex < text.length) segments.push({ text: text.slice(lastIndex), hit: false })
  return segments
}

/* ------------------------------ 最近搜索（localStorage） ------------------------------ */

function storage(): Storage | undefined {
  if (typeof window === 'undefined') return undefined
  try {
    return window.localStorage
  } catch {
    // 隐私模式等场景下 localStorage 可能不可用
    return undefined
  }
}

export function loadRecentSearches(): string[] {
  const store = storage()
  if (!store) return []
  try {
    const raw = store.getItem(RECENT_SEARCH_KEY)
    if (!raw) return []
    const parsed: unknown = JSON.parse(raw)
    if (!Array.isArray(parsed)) return []
    return parsed.filter((item): item is string => typeof item === 'string').slice(0, RECENT_SEARCH_LIMIT)
  } catch {
    return []
  }
}

export function saveRecentSearches(list: string[]): void {
  const store = storage()
  if (!store) return
  try {
    store.setItem(RECENT_SEARCH_KEY, JSON.stringify(list.slice(0, RECENT_SEARCH_LIMIT)))
  } catch {
    // 写入失败（配额/隐私模式）不影响搜索本身
  }
}

/** 纯函数：把关键词插入最近搜索（去重 + 截断，新的在前） */
export function pushRecentSearch(term: string, list: string[]): string[] {
  const value = term.trim()
  if (!value) return list
  return [value, ...list.filter((item) => item !== value)].slice(0, RECENT_SEARCH_LIMIT)
}

/* ------------------------------ 组合式封装 ------------------------------ */

/** GlobalSearch.vue 使用的状态容器：query / 分组结果 / 最近搜索 */
export function useGlobalSearch(role: Ref<Role>) {
  const query = ref('')
  const recents = ref<string[]>(loadRecentSearches())

  const index = computed<SearchItem[]>(() => buildSearchIndex(role.value))
  const groups = computed<SearchGroupBlock[]>(() => searchIndex(index.value, query.value))
  const flatItems = computed<SearchItem[]>(() => groups.value.flatMap((block) => block.items))
  const totalMatches = computed<number>(() => groups.value.reduce((sum, block) => sum + block.total, 0))
  const hasQuery = computed<boolean>(() => query.value.trim().length > 0)

  function remember(term: string) {
    recents.value = pushRecentSearch(term, recents.value)
    saveRecentSearches(recents.value)
  }

  function clearRecents() {
    recents.value = []
    saveRecentSearches([])
  }

  return { query, recents, index, groups, flatItems, totalMatches, hasQuery, remember, clearRecents }
}
