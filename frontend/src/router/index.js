import { createRouter, createWebHistory } from 'vue-router'
import { ElMessage } from 'element-plus'
import { useUserStore } from '@/stores/user'

// 视图全部懒加载：首屏只需要列表页，把发帖页、详情页打进同一个包
// 会让首屏多下载几十 KB 的、当下用不到的代码
const routes = [
  { path: '/', name: 'home', component: () => import('@/views/PostListView.vue') },
  { path: '/board/:boardId', name: 'board', component: () => import('@/views/PostListView.vue') },
  {
    path: '/post/create',
    name: 'post-create',
    component: () => import('@/views/PostCreateView.vue'),
    meta: { requiresAuth: true }
  },
  { path: '/post/:id', name: 'post-detail', component: () => import('@/views/PostDetailView.vue') },
  { path: '/login', name: 'login', component: () => import('@/views/LoginView.vue') },
  { path: '/register', name: 'register', component: () => import('@/views/RegisterView.vue') },

  // 积分与商城。三者都要求登录：积分是「我的」数据，商城要按余额算能不能兑换
  { path: '/points', name: 'points', component: () => import('@/views/PointsView.vue'), meta: { requiresAuth: true } },
  { path: '/mall', name: 'mall', component: () => import('@/views/MallView.vue'), meta: { requiresAuth: true } },
  {
    path: '/mall/orders',
    name: 'mall-orders',
    component: () => import('@/views/MallOrdersView.vue'),
    meta: { requiresAuth: true }
  },
  {
    path: '/admin/mall',
    name: 'admin-mall',
    component: () => import('@/views/AdminMallView.vue'),
    meta: { requiresAuth: true, requiresAdmin: true }
  },

  // 秒杀：入口在商城页的「限时抢购」区块，这里只放「我的秒杀」
  // （抢到的单要在这里支付/取消，不能只有抢购入口而没有回看入口）
  {
    path: '/seckill/orders',
    name: 'seckill-orders',
    component: () => import('@/views/SeckillOrdersView.vue'),
    meta: { requiresAuth: true }
  },
  {
    path: '/admin/seckill',
    name: 'admin-seckill',
    component: () => import('@/views/AdminSeckillView.vue'),
    meta: { requiresAuth: true, requiresAdmin: true }
  },

  { path: '/:pathMatch(.*)*', redirect: '/' }
]

const router = createRouter({
  history: createWebHistory(),
  routes,
  scrollBehavior: () => ({ top: 0 })
})

router.beforeEach((to) => {
  const store = useUserStore()

  if (to.meta.requiresAuth && !store.isLogin) {
    ElMessage.warning('请先登录')
    // 记下原目标，登录后回到用户本来想去的地方，而不是一律丢回首页
    return { name: 'login', query: { redirect: to.fullPath } }
  }

  if (to.meta.requiresAdmin && !store.isAdmin) {
    // 这层拦截只改善体验（普通用户看不到管理页），不构成安全边界：
    // 真正的防线是后端 @RequireRole(ROLE_ADMIN)，绕过这里也只会拿到 10003。
    // 用户资料还没加载出来时 isAdmin 为 false，会走到这里返首页——
    // 而 main.js 在挂载前已经等过了 restore()，正常路径下不会发生
    ElMessage.error('需要管理员权限')
    return { name: 'home' }
  }

  return true
})

export default router
