<template>
  <div class="login-wrap">
    <el-card class="login-card">
      <div class="brand">
        <el-icon size="28" color="#1d4ed8"><Connection /></el-icon>
        <h2>维修配置与备件兼容<br/>主数据血缘核验平台</h2>
      </div>
      <el-form :model="form" label-position="top" @keyup.enter="submit">
        <el-form-item label="账号">
          <el-input v-model="form.username" placeholder="admin / engineer / viewer" :prefix-icon="User" />
        </el-form-item>
        <el-form-item label="密码">
          <el-input v-model="form.password" type="password" show-password placeholder="请输入密码" :prefix-icon="Lock" />
        </el-form-item>
        <el-button type="primary" class="btn" :loading="loading" @click="submit">登 录</el-button>
      </el-form>
      <el-alert class="hint" type="info" :closable="false"
        title="测试账号：admin/admin123（管理员）、engineer/eng123（工程师）、viewer/view123（只读）" />
    </el-card>
  </div>
</template>

<script setup>
import { reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { User, Lock } from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus'
import { useAuthStore } from '../stores/auth'

const form = reactive({ username: 'admin', password: 'admin123' })
const loading = ref(false)
const auth = useAuthStore()
const router = useRouter()

async function submit() {
  if (!form.username || !form.password) {
    ElMessage.warning('请输入账号和密码')
    return
  }
  loading.value = true
  try {
    await auth.login(form)
    ElMessage.success('登录成功')
    router.push('/dashboard')
  } finally {
    loading.value = false
  }
}
</script>

<style scoped>
.login-wrap {
  height: 100vh; display: flex; align-items: center; justify-content: center;
  background: linear-gradient(135deg, #1e3a8a 0%, #0f172a 100%);
}
.login-card { width: 420px; padding: 12px; }
.brand { text-align: center; margin-bottom: 18px; }
.brand h2 { font-size: 19px; color: #0f172a; margin: 10px 0 0; line-height: 1.5; }
.btn { width: 100%; margin-top: 6px; }
.hint { margin-top: 16px; }
</style>
