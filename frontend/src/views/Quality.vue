<template>
  <el-card>
    <template #header>
      <div class="card-head">
        <b>质量事件（冲突拦截 / 实体冻结 / 规则撤回 / 事实更正 / 迟到凭证 / 待复核）</b>
        <el-radio-group v-model="filter" size="small" @change="load">
          <el-radio-button value="">全部</el-radio-button>
          <el-radio-button value="OPEN">待处理</el-radio-button>
          <el-radio-button value="CLOSED">已关闭</el-radio-button>
        </el-radio-group>
      </div>
    </template>
    <el-table :data="events" stripe size="small">
      <el-table-column prop="event_no" label="事件号" width="200" class-name="mono" />
      <el-table-column prop="event_type" label="类型" width="180" />
      <el-table-column label="严重度" width="100">
        <template #default="{ row }">
          <el-tag :type="{ LOW: 'info', MEDIUM: 'warning', HIGH: 'danger', CRITICAL: 'danger' }[row.severity]" size="small">
            {{ { LOW: '低', MEDIUM: '中', HIGH: '高', CRITICAL: '紧急' }[row.severity] }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="description" label="描述" min-width="320" />
      <el-table-column label="处理状态" width="110">
        <template #default="{ row }">
          <el-tag :type="row.handling_status === 'CLOSED' ? 'success' : 'warning'" size="small">
            {{ { OPEN: '待处理', IN_PROGRESS: '处理中', CLOSED: '已关闭' }[row.handling_status] }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="created_at" label="产生时间" width="180" />
      <el-table-column label="操作" width="110">
        <template #default="{ row }">
          <el-button v-if="row.handling_status !== 'CLOSED'" size="small" type="primary"
                     :disabled="!auth.canWrite" @click="close(row)">关闭</el-button>
        </template>
      </el-table-column>
    </el-table>
  </el-card>
</template>

<script setup>
import { onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import http from '../api/http'
import { useAuthStore } from '../stores/auth'

const auth = useAuthStore()
const events = ref([]); const filter = ref('')
async function load() {
  events.value = (await http.get('/quality-events', { params: { handlingStatus: filter.value || undefined } })).data
}
async function close(row) {
  const { value } = await ElMessageBox.prompt('关闭说明', '关闭质量事件', { inputValue: '已按流程处理并留档' })
  await http.post(`/quality-events/${row.id}/close`, { note: value })
  ElMessage.success('质量事件已关闭')
  load()
}
onMounted(load)
</script>
<style scoped>.card-head { display: flex; justify-content: space-between; align-items: center; }</style>
