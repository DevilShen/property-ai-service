/**
 * 共享组件统一出口。
 * 页面只从 '@/components' 导入，避免深层相对路径；组件实现放在
 * components/ui（原子）、components/layout（布局）、components/feedback（反馈）。
 */
export { default as WIcon } from './ui/Icon.vue'
export { default as WButton } from './ui/Button.vue'
export { default as WCard } from './ui/Card.vue'
export { default as WTag } from './ui/Tag.vue'
export { default as WModal } from './ui/Modal.vue'
export { default as WDrawer } from './ui/Drawer.vue'
export { default as WTabs } from './ui/Tabs.vue'
export { default as WTimeline } from './ui/Timeline.vue'
export { default as WTable } from './ui/Table.vue'
export { default as WEmpty } from './ui/Empty.vue'
export { default as WLoading } from './ui/Loading.vue'
export { default as WPageHeader } from './layout/PageHeader.vue'
export { default as WToastHost } from './feedback/ToastHost.vue'

export { useToast } from '@/composables/useToast'
