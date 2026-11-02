import Vue from 'vue'

import App from './App.vue'
import router from './router'

import './assets/main.css'

new Vue({
  router,
  render: (h) => h(App)
}).$mount('#app')

// CI / 自动化验证模式：构建时设置 VITE_AUTOTEST=1，App 启动后直接进插件测试页并自动跑用例
if (import.meta.env.VITE_AUTOTEST === '1') {
  router.replace('/plugin-test?autotest=1').catch(() => {})
}
