import type { CopilotWorkspaceKey, CopilotWorkspaceMeta } from '@/types'

/**
 * Copilot 8 个工作区（顺序与需求一致）。
 * W2 每个工作区替换对应 workspaces/*.vue 内容，本文件保留左栏与路由元数据。
 */
export const COPILOT_WORKSPACE_ORDER: CopilotWorkspaceKey[] = [
  'knowledge',
  'script',
  'summary',
  'profile',
  'daily',
  'group',
  'build',
  'history',
]

export const COPILOT_WORKSPACES: Record<CopilotWorkspaceKey, CopilotWorkspaceMeta> = {
  knowledge: {
    key: 'knowledge',
    path: '/copilot/knowledge',
    label: '知识秒查',
    icon: 'book',
    description: '问题 → 答案 + 出处 + 版本 + 生效期，可复制或一键生成话术。',
    w2Focus: '知识秒查：检索、出处、版本、复制/生成话术',
    panels: [
      { title: '检索输入', note: '问题输入 + 项目级隔离提示，W2 填充。', icon: 'search' },
      { title: '答案与出处', note: '知识 ID、版本、生效日期、责任人，W2 填充。', icon: 'book' },
      { title: '动作', note: '复制答案 / 生成话术（W3 接动作弹层）。', icon: 'copy' },
    ],
  },
  script: {
    key: 'script',
    path: '/copilot/script',
    label: '话术生成',
    icon: 'chat',
    description: '场景 + 业主情绪 + 渠道三组条件，生成 3 版话术，可复制或插入企微。',
    w2Focus: '话术生成：条件组合、3 版话术、复制/插入企微',
    panels: [
      { title: '生成条件', note: '场景 / 情绪 / 渠道三组条件，W2 填充。', icon: 'filter' },
      { title: '3 版话术', note: '不同语气版本与红线提示，W2 填充。', icon: 'chat' },
      { title: '动作', note: '复制 / 插入企微预览（W3 弹层）。', icon: 'copy' },
    ],
  },
  summary: {
    key: 'summary',
    path: '/copilot/summary',
    label: '工单摘要',
    icon: 'report',
    description: '50 条群消息 → 5 行摘要 + 3 条待办，可建工单、复制、发送主管。',
    w2Focus: '工单摘要：消息摘要、待办提取、建工单入口',
    panels: [
      { title: '消息摘要', note: '群消息折叠为 5 行摘要，W2 填充。', icon: 'report' },
      { title: '待办提取', note: '3 条待办与责任人建议，W2 填充。', icon: 'list-check' },
      { title: '动作', note: '建工单 / 复制 / 发送主管（W3 弹层）。', icon: 'plus' },
    ],
  },
  profile: {
    key: 'profile',
    path: '/copilot/profile',
    label: '业主画像',
    icon: 'idcard',
    description: '基础档案、服务历史、缴费与合同、风险与偏好、最近 6 次互动（脱敏展示）。',
    w2Focus: '业主画像：脱敏档案、服务历史、风险偏好',
    panels: [
      { title: '基础档案', note: '房号/联系人按权限脱敏展示，W2 填充。', icon: 'idcard' },
      { title: '缴费与服务历史', note: '账单、工单、投诉摘要，W2 填充。', icon: 'wallet' },
      { title: '风险与偏好', note: '敏感标签与沟通偏好，W2 填充。', icon: 'shield' },
    ],
  },
  daily: {
    key: 'daily',
    path: '/copilot/daily',
    label: '日报周报',
    icon: 'chart',
    description: '今日 4 项指标、AI 日报、本周闭环趋势，导出日报/周报。',
    w2Focus: '日报周报：指标、AI 日报、趋势、导出',
    panels: [
      { title: '今日指标', note: '4 项指标卡（W2 接 mock 数据，不编造数字）。', icon: 'chart' },
      { title: 'AI 日报', note: '自动汇总的当日概览，W2 填充。', icon: 'report' },
      { title: '导出', note: '日报 / 周报导出（W3 动作弹层）。', icon: 'download' },
    ],
  },
  group: {
    key: 'group',
    path: '/copilot/group',
    label: '群服务',
    icon: 'users',
    description: 'B 企微客户群：存档摘要 → 草稿 → 人工确认群发；C 个人微信群：只发入口卡片，1v1 承接。',
    w2Focus: '群服务 B+C：人工确认群发、个人群入口；不做群内自动回复',
    panels: [
      { title: 'B · 企微客户群', note: '存档摘要、群发草稿、人工确认发送，W2 填充。', icon: 'users' },
      { title: 'C · 个人微信群', note: '只生成小程序码/服务号卡片，管家手工发群，W2 填充。', icon: 'chat' },
      { title: '边界', note: '不读取群消息、不自动回复；AI 仅在 1v1 承接。', icon: 'shield' },
    ],
  },
  build: {
    key: 'build',
    path: '/copilot/build',
    label: '知识共建',
    icon: 'edit',
    description: '提交 AI 答错/答不上的问题，查看最近提交与采纳状态。',
    w2Focus: '知识共建：提交、最近提交、采纳状态',
    panels: [
      { title: '提交问题', note: '问题 + 场景 + 期望答案，W2 填充表单。', icon: 'edit' },
      { title: '最近提交', note: '提交记录与采纳状态，W2 填充。', icon: 'list-check' },
      { title: '流转', note: '进入知识库运营的缺口池（W3 打通）。', icon: 'arrow-right' },
    ],
  },
  history: {
    key: 'history',
    path: '/copilot/history',
    label: '历史会话',
    icon: 'clock',
    description: '按意图/结果筛选历史会话，跳转全链路回溯。',
    w2Focus: '历史会话：筛选、列表、跳转回溯',
    panels: [
      { title: '筛选', note: '意图 / 结果 / 时间筛选，W2 填充。', icon: 'filter' },
      { title: '会话列表', note: '会话摘要与结果标记，W2 填充。', icon: 'clock' },
      { title: '跳转', note: '跳转 /trace 全链路回溯（W2 打通）。', icon: 'arrow-right' },
    ],
  },
}

export const COPILOT_DEFAULT_WORKSPACE: CopilotWorkspaceKey = 'knowledge'
