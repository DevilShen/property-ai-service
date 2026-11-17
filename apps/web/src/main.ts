import { createApp } from 'vue'
import { createPinia } from 'pinia'
import App from './App.vue'
import router from './router'
import { setUnauthorizedHandler } from './auth/unauthorized'
import './styles/index.css'

/**
 * 401 全局出口（P2 W8，契约 v0.3.3 §8）：
 * `api/client.ts` 不直接依赖 router（请求层/路由层解耦、便于单测），在此注册跳转实现：
 * 清 token（client 已完成）→ `router.replace('/login?returnTo=<原路径>')`，避免页面白屏。
 */
setUnauthorizedHandler((returnTo) => {
  const current = router.currentRoute.value
  if (current.name === 'login') return
  void router.replace({ name: 'login', query: { returnTo: current.fullPath || returnTo } })
})

const app = createApp(App)

app.use(createPinia())
app.use(router)
app.mount('#app')
