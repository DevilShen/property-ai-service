<script setup lang="ts">
/**
 * 应用根组件：只承载路由出口。
 * 真正的 App Shell（侧边栏 + 顶栏 + 内容区）在 layouts/AppShell.vue，
 * 由 "/" 路由挂载，保证所有业务页共享同一框架。
 */
</script>

<template>
  <RouterView />
</template>
