<template>
  <el-container class="layout">
    <el-aside width="232px" class="aside">
      <div class="logo">
        <el-icon><Connection /></el-icon>
        <span>主数据血缘核验</span>
      </div>
      <el-menu :default-active="$route.path" router background-color="#0f172a" text-color="#cbd5e1"
               active-text-color="#ffffff">
        <el-menu-item index="/dashboard"><el-icon><DataAnalysis /></el-icon><span>核验总览</span></el-menu-item>
        <el-menu-item index="/contracts"><el-icon><FolderOpened /></el-icon><span>接入契约库</span></el-menu-item>
        <el-menu-item index="/entities"><el-icon><CopyDocument /></el-icon><span>实体消歧</span></el-menu-item>
        <el-menu-item index="/drawings"><el-icon><Document /></el-icon><span>图号与修订</span></el-menu-item>
        <el-menu-item index="/events"><el-icon><List /></el-icon><span>实装事件</span></el-menu-item>
        <el-menu-item index="/rules"><el-icon><Share /></el-icon><span>规则与血缘</span></el-menu-item>
        <el-menu-item index="/snapshots"><el-icon><Camera /></el-icon><span>快照与时点查询</span></el-menu-item>
        <el-menu-item index="/compatibility"><el-icon><CircleCheck /></el-icon><span>兼容与发布闸门</span></el-menu-item>
        <el-menu-item index="/reliability"><el-icon><Histogram /></el-icon><span>替代件可靠性</span></el-menu-item>
        <el-menu-item index="/impact"><el-icon><Guide /></el-icon><span>影响分析</span></el-menu-item>
        <el-menu-item index="/quality"><el-icon><Warning /></el-icon><span>质量事件</span></el-menu-item>
      </el-menu>
    </el-aside>
    <el-container>
      <el-header class="header">
        <div class="title">{{ $route.meta.title || '主数据平台' }}</div>
        <div class="user">
          <el-tag :type="roleTag" effect="dark" size="small">{{ roleText }}</el-tag>
          <span class="name">{{ auth.displayName }}（{{ auth.username }}）</span>
          <el-button text type="primary" @click="logout">退出</el-button>
        </div>
      </el-header>
      <el-main class="main">
        <router-view />
      </el-main>
    </el-container>
  </el-container>
</template>

<script setup>
import { computed } from 'vue'
import { useRouter } from 'vue-router'
import { useAuthStore } from '../stores/auth'

const auth = useAuthStore()
const router = useRouter()
const roleText = computed(() => ({ ADMIN: '管理员', ENGINEER: '工程师', VIEWER: '只读查询' }[auth.role] || auth.role))
const roleTag = computed(() => ({ ADMIN: 'danger', ENGINEER: 'warning', VIEWER: 'info' }[auth.role] || 'info'))

function logout() {
  auth.logout()
  router.push('/login')
}
</script>

<style scoped>
.layout { height: 100vh; }
.aside { background: #0f172a; overflow-x: hidden; }
.logo {
  height: 60px; display: flex; align-items: center; gap: 8px;
  color: #fff; font-weight: 700; font-size: 16px; padding: 0 18px;
  border-bottom: 1px solid #1e293b;
}
.aside :deep(.el-menu) { border-right: none; }
.header {
  background: #fff; display: flex; align-items: center; justify-content: space-between;
  border-bottom: 1px solid #e5e7eb;
}
.header .title { font-size: 18px; font-weight: 600; color: #0f172a; }
.user { display: flex; align-items: center; gap: 10px; }
.user .name { color: #475569; font-size: 13px; }
.main { background: var(--mdm-bg); padding: 18px; }
</style>
