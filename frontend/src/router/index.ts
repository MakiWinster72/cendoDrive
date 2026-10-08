import { createRouter, createWebHistory } from "vue-router";
import { useAuth } from "../stores/auth";

const router = createRouter({
  history: createWebHistory(),
  routes: [
    {
      path: "/",
      name: "home",
      component: () => import("../views/HomeView.vue"),
      meta: { requiresAuth: true },
    },
    {
      path: "/login",
      name: "login",
      component: () => import("../views/LoginView.vue"),
      meta: { guestOnly: true },
    },
    {
      path: "/register",
      name: "register",
      component: () => import("../views/RegisterView.vue"),
      meta: { guestOnly: true },
    },
    {
      path: "/membership",
      name: "membership",
      component: () => import("../views/MembershipView.vue"),
      meta: { requiresAuth: true },
    },
    {
      path: "/ai-points",
      name: "ai-points",
      component: () => import("../views/AiPointsView.vue"),
      meta: { requiresAuth: true },
    },
    {
      path: "/ai-search",
      name: "ai-search",
      component: () => import("../views/AiSearchView.vue"),
      meta: { requiresAuth: true },
    },
    {
      path: "/my-assets",
      name: "my-assets",
      component: () => import("../views/MyAssetsView.vue"),
      meta: { requiresAuth: true },
    },
    {
      path: "/videos",
      name: "videos",
      component: () => import("../views/VideoHubView.vue"),
      meta: { requiresAuth: true },
    },
    {
      path: "/novels",
      name: "novels",
      component: () => import("../views/NovelHubView.vue"),
      meta: { requiresAuth: true },
    },
    {
      path: "/game-center",
      name: "game-center",
      component: () => import("../views/GameCenterView.vue"),
      meta: { requiresAuth: true },
    },
    {
      path: "/share/:token",
      name: "share",
      component: () => import("../views/ShareView.vue"),
    },
    { path: "/:pathMatch(.*)*", redirect: "/" },
  ],
});

router.beforeEach(async (to) => {
  const auth = useAuth();
  const loggedIn = await auth.ensureSession();
  if (to.meta.requiresAuth && !loggedIn)
    return { name: "login", query: { redirect: to.fullPath } };
  if (to.meta.guestOnly && loggedIn) return { name: "home" };
});

export default router;
