# @property/web · 物业 AI 智能客服工作台（P1）

> P1 W1 交付：App Shell、12 路由、8 工作区、共享组件、设计 token 与骨架页。
> W2 起在此包内逐屏填充业务内容（`src/views/**`），**不依赖** `@property/shared`（W2 再接入）。

## 快速开始

```bash
# 在仓库根目录
pnpm install
pnpm --filter @property/web dev     # http://localhost:5173（绑定 0.0.0.0，127.0.0.1 也可访问）
pnpm --filter @property/web build   # vue-tsc 类型检查 + vite build
pnpm --filter @property/web test    # vitest（角色可见性单测）
```

## 目录结构

```
apps/web/
├─ index.html
├─ vite.config.ts            # 端口 5173；W3 接 mock API 时在此加 server.proxy
├─ tsconfig.json
└─ src/
   ├─ main.ts / App.vue      # 挂载 Pinia + Router；App.vue 只放 RouterView
   ├─ types.ts               # W1 本地类型（W2 切 shared）
   ├─ tokens/
   │  ├─ tokens.css          # ★ 色彩/间距/圆角/阴影/字体/层级的唯一来源
   │  └─ index.ts            # 同一组数值的 TS 版本（图表/画布场景用）
   ├─ styles/
   │  ├─ index.css           # 入口：token → reset → global
   │  ├─ reset.css
   │  └─ global.css          # .w-page / .row / .between 等工具类 + 路由过渡
   ├─ app/
   │  ├─ screens.ts          # ★ 12 屏单一数据源（导航/路由 meta 都从这里派生）
   │  ├─ copilot.ts          # ★ 8 个工作区元数据
   │  ├─ roles.ts            # 6 角色定义、默认角色
   │  ├─ permissions.ts      # 角色可见性占位 + 路由拦截判断
   │  ├─ projects.ts         # 项目切换占位（云栖苑…）
   │  └─ permissions.spec.ts
   ├─ router/index.ts        # 路由表 + 角色守卫 + document.title
   ├─ stores/app.ts          # 当前角色/项目、搜索/通知浮层状态
   ├─ layouts/
   │  ├─ AppShell.vue        # 侧栏 + 顶栏 + 内容区 + ToastHost + Ctrl+K
   │  ├─ AppSidebar.vue      # 12 项导航（按角色过滤、按分组渲染）
   │  └─ AppTopbar.vue       # 面包屑、搜索、通知、项目/角色切换
   ├─ components/
   │  ├─ index.ts            # ★ 统一出口：页面只从 '@/components' 导入
   │  ├─ ui/                 # Button/Card/Tag/Modal/Drawer/Tabs/Timeline/Table/Empty/Loading/Icon
   │  ├─ layout/PageHeader.vue
   │  └─ feedback/ToastHost.vue
   ├─ composables/           # useToast、useClickOutside
   └─ views/
      ├─ shared/             # ScreenSkeleton / WorkspaceSkeleton（W1 骨架，W2 可留作未开工屏）
      ├─ overview | owner | progress | desk | billing | erp | mapping |
      │  orchestration | trace | knowledge | dashboard   # 11 个单屏
      ├─ copilot/            # CopilotView（左栏 8 工作区）+ workspaces/ 8 个 pane
      └─ system/             # UiKitView（/ui-kit 组件预览）、ForbiddenView（403）
```

## 路由

| 路径 | 页面 | 说明 |
|---|---|---|
| `/overview` | 01 方案总览 | W2 由 P1-010 填充 |
| `/owner` | 02 业主端 AI 客服 | P1-011 |
| `/progress` | 03 工单进度 · 闭环 | P1-012 |
| `/copilot` | 04 管家 Copilot | 壳；默认重定向 `/copilot/knowledge` |
| `/copilot/knowledge` 等 8 个 | Copilot 工作区 | knowledge/script/summary/profile/daily/group/build/history |
| `/desk` | 05 坐席工作台 | frontend-2（P1-017） |
| `/billing` | 06 费用与催缴 | frontend-2（P1-018） |
| `/erp` | 07 ERP 适配中心 | frontend-2（P1-019） |
| `/mapping` | 08 字段与状态映射 | frontend-2（P1-020） |
| `/orchestration` | 09 能力编排 | frontend-2（P1-021） |
| `/trace` | 10 全链路回溯 | frontend-2（P1-022） |
| `/knowledge` | 11 知识库运营 | frontend-2（P1-023） |
| `/dashboard` | 12 运营看板 | frontend-2（P1-024） |
| `/ui-kit` | 共享组件预览 | 不进导航，开发/验收用 |
| `/forbidden` | 越权占位页 | 守卫自动跳转，带 `?from=` 来源 |
| 其它 | 重定向 `/overview` | — |

## 角色可见性（W1 占位，不做真实鉴权）

| 页面 | 业主 | 管家 | 坐席 | 主管 | IT | 管理员 |
|---|:-:|:-:|:-:|:-:|:-:|:-:|
| 方案总览 / 业主端 / 工单进度 | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ |
| 管家 Copilot | — | ✓ | — | ✓ | ✓ | ✓ |
| 坐席工作台 | — | — | ✓ | ✓ | — | ✓ |
| 费用与催缴 | — | ✓ | — | ✓ | — | ✓ |
| ERP / 映射 / 编排 | — | — | — | — | ✓ | ✓ |
| 全链路回溯 | — | — | — | ✓ | ✓ | ✓ |
| 知识库运营 | — | ✓ | — | ✓ | ✓ | ✓ |
| 运营看板 | — | — | — | ✓ | ✓ | ✓ |

- 真源：`src/app/screens.ts` 每个屏的 `roles`；新角色接入只需改这里 + `roles.ts`。
- 守卫：`router.beforeEach` 调 `canAccess(role, path)`，不可见 → `/forbidden`；切换角色后若当前页不可见，自动回落到该角色第一个可见页。

## 设计 token

- 主品牌深绿 `#0A6B5B`、深色文字 `#0E1D2B`、页面浅底 `#F3F7F8`；字体 Microsoft YaHei。
- 全部尺寸/色值走 CSS 变量（`--c-*` `--sp-*` `--rd-*` `--sh-*` `--fs-*` `--layout-*`），组件内不硬编码。
- 深色 token 已提供 `[data-theme='dark']` 覆盖（W4 再补组件级细节；原型无深色稿）。

## 共享组件

| 组件 | 关键 props / 用法 |
|---|---|
| `WButton` | `variant: primary / secondary / ghost / ai / danger`，`size: sm/md/lg`，`icon`、`loading`、`disabled`、`block`；含 hover/active/focus-visible 四态 |
| `WCard` | `title`、`subtitle`、`padded`、`flat`；插槽 `head/extra/default/footer` |
| `WTag` | `tone: g/b/a/r/v/n`、`plain`（去色点） |
| `WModal` | `v-model:open`、`title`、`width`；ESC/遮罩关闭；插槽 `title/headExtra/footer` |
| `WDrawer` | `v-model:open`、`title`、`width`、`placement` |
| `WTabs` | `v-model` + `tabs: [{key,label,badge?}]` |
| `WTimeline` | `items: TimelineItem[]`（status: done/active/pending/error） |
| `WTable` | `columns`、`rows`、`loading`、`emptyText`、`rowKey`；列插槽名 = 列 key |
| `WEmpty` | `title`、`description`、`icon`、`compact` |
| `WLoading` | `label`、`size`、`center` |
| `useToast()` | `info/success/warning/error(message)`，由 AppShell 的 `WToastHost` 统一渲染 |

示例页：`/ui-kit`（含全部组件与四态按钮）。

## W2 接入约定

1. **只改 `src/views/<屏>/`**：把骨架页替换为业务实现；路由、导航、角色表不用动。
2. **页面状态**：必须覆盖加载/空/错误/降级（可直接复用 `WEmpty`/`WLoading`/`WTable` 的 `loading`）。
3. **数据**：W2 先用 `src/types.ts` 的本地类型 + 组件内 mock；shared 就绪后从 `@property/shared` 导入并删除本地业务类型。
4. **不新增第三方 UI 库**；缺组件提给 frontend-1，避免各屏重复实现。
5. **按钮必须有反馈**：W3 统一由 P1-032/P1-033 的动作用弹层补齐，临时用 `useToast()` 占位。
6. 新增页面时：`app/screens.ts` 加一条 → 路由自动生成；侧栏按 `group` 归入「业务工作台 / 适配与运营」。
