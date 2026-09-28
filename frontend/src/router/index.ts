import { createRouter, createWebHistory } from 'vue-router'
import { isAuthenticated } from '../stores/auth'

const router = createRouter({
  history: createWebHistory(),
  routes: [
    { path: '/', name: 'home', component: () => import('../views/HomeView.vue'), meta: { requiresAuth: true } },
    { path: '/login', name: 'login', component: () => import('../views/LoginView.vue'), meta: { guestOnly: true } },
    { path: '/share/:id', name: 'share', component: () => import('../views/ShareView.vue') },
    { path: '/:pathMatch(.*)*', redirect: '/' },
  ],
})

router.beforeEach((to) => {
  const loggedIn = isAuthenticated()
  if (to.name === 'login' && Object.keys(to.query).length) return { name: 'login', replace: true }
  if (to.meta.requiresAuth && !loggedIn) return { name: 'login' }
  if (to.meta.guestOnly && loggedIn) return { name: 'home' }
})

export default router
