<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted } from 'vue'
import { RouterView, useRoute } from 'vue-router'
import { WToastHost } from '@/components'
import { useAppStore } from '@/stores/app'
import { useProjectStore } from '@/stores/project'
import AppSidebar from './AppSidebar.vue'
import AppTopbar from './AppTopbar.vue'

const app = useAppStore()
const project = useProjectStore()
const route = useRoute()

/**
 * 过渡 key 取一级业务路由：/copilot/knowledge → /copilot，
 * 这样切换 8 个工作区只替换 pane，不重建 Copilot 壳。
 *
 * P5 W19 增量：key 追加当前项目 → **切换项目后当前业务页强制重挂载**，
 * 各页 `onMounted` 重新拉取自己项目的数据（不串数据）；项目未确定（空串）时不拼 key，
 * 避免首屏因项目异步加载而二次挂载。
 */
const routeKey = computed(() => {
  const base = route.matched[1]?.path ?? route.path
  return project.activeProjectId ? `${base}::${project.activeProjectId}` : base
})

function onKeydown(event: KeyboardEvent) {
  const key = event.key.toLowerCase()
  if ((event.ctrlKey || event.metaKey) && key === 'k') {
    event.preventDefault()
    app.setSearchOpen(!app.searchOpen)
    return
  }
  if (event.key === 'Escape') app.closeOverlays()
}

onMounted(() => {
  window.addEventListener('keydown', onKeydown)
})

onBeforeUnmount(() => {
  window.removeEventListener('keydown', onKeydown)
})
</script>

<template>
  <div class="app-shell">
    <AppSidebar />
    <div class="app-shell__main">
      <AppTopbar />
      <main id="main-content" class="app-stage" role="main">
        <RouterView v-slot="{ Component }">
          <Transition name="fade" mode="out-in">
            <component :is="Component" :key="routeKey" />
          </Transition>
        </RouterView>
      </main>
    </div>
    <WToastHost />
  </div>
</template>

<style scoped>
.app-shell {
  display: flex;
  height: 100vh;
  overflow: hidden;
  background: var(--c-bg);
}

.app-shell__main {
  display: flex;
  flex-direction: column;
  flex: 1;
  min-width: 0;
  height: 100vh;
  overflow: hidden;
}

.app-stage {
  flex: 1;
  min-height: 0;
  overflow-y: auto;
  padding: var(--layout-stage-pad);
}
</style>
