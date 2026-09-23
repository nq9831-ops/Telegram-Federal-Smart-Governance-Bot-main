import { createRouter, createWebHistory, type RouteRecordRaw } from 'vue-router'
import { useSession } from './stores/session'

/**
 * 控制台路由表（模块十二 · Wave 2a：引入 vue-router）。
 *
 * <p><b>为什么现在引路由</b>：此前六个视图用条件渲染足够（`App.vue` 的联合类型 `view`）。
 * 交易域是第一条**三级导航**（订单列表 → 详情 → 争议），且需要可分享深链
 * （"帮我看看 #42 那笔"）——这正是 `main.ts` 当年写下的引入条件（"等真需要深链或嵌套路由时再引入"）。
 *
 * <p><b>懒加载</b>：各视图用动态 import。此前六个视图全打进主包（构建产物 1.08 MB），
 * 交易域会让它继续膨胀；按路由分包后首屏只加载登录与当前页。
 *
 * <p><b>权限放在 meta + 守卫</b>，不放组件：组件内的检查换个入口就绕过了
 * （本项目既有教训：把校验埋在组件里等于给另一个入口留后门）。
 * 三档与 `session` 的既有判定一致：审计按 `canReadAudit`、信用按 `canReadCredit`、
 * 账号管理仅 `SUPER_ADMIN`。
 */
const routes: RouteRecordRaw[] = [
  { path: '/', redirect: '/todos' },
  { path: '/todos', name: 'todos', component: () => import('./views/TodoCenter.vue') },
  { path: '/config', name: 'config', component: () => import('./views/ConfigCenter.vue') },
  { path: '/mine', name: 'mine', component: () => import('./views/MyContent.vue') },
  {
    path: '/audit',
    name: 'audit',
    component: () => import('./views/AuditCenter.vue'),
    meta: { requires: 'audit' },
  },
  {
    path: '/credit',
    name: 'credit',
    component: () => import('./views/CreditCenter.vue'),
    meta: { requires: 'credit' },
  },
  {
    path: '/accounts',
    name: 'accounts',
    component: () => import('./views/AccountCenter.vue'),
    meta: { requires: 'super' },
  },
  // 兜底：未知路径回待办中心（不显示空白页——空白页看起来像坏了）。
  { path: '/:pathMatch(.*)*', redirect: '/todos' },
]

export const router = createRouter({
  history: createWebHistory(import.meta.env.BASE_URL),
  routes,
})

/**
 * 守卫判定（**纯函数**，可独立测）：{@code true} 放行、字符串为重定向目标、{@code false} 取消导航。
 *
 * <p>抽成纯函数而非直接写在 {@code beforeEach} 里，是因为它承载权限语义——
 * "越权回退"必须能被测试复现，而不是只能靠手点界面确认。
 */
export function resolveGuard(
  to: { meta: Record<string, unknown> },
  session: {
    authenticated: boolean
    canReadAudit: boolean
    canReadCredit: boolean
    /** 会话未落地时可能为空（`/auth/me` 之前）——`null` 不等于超管。 */
    role: string | null
  },
): true | string | false {
  if (!session.authenticated) {
    return false
  }
  const requires = to.meta.requires
  if (requires === 'audit' && !session.canReadAudit) {
    return '/todos'
  }
  if (requires === 'credit' && !session.canReadCredit) {
    return '/todos'
  }
  if (requires === 'super' && session.role !== 'SUPER_ADMIN') {
    return '/todos'
  }
  return true
}

/**
 * 全局前置守卫：未登录放行到门禁页（由 `App.vue` 渲染），越权回退到待办中心。
 *
 * <p>未登录时返回 `false` 取消导航而**不是**重定向——登录门禁不是一条路由，
 * 而是 `App.vue` 的一个渲染分支（`!session.authenticated`）；重定向会与它打架。
 */
router.beforeEach((to) => resolveGuard(to, useSession()))
