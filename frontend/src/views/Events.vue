<template>
  <div>
    <el-card class="page-card">
      <template #header>
        <div class="card-head">
          <b>追加式实装事件（拆装凭证，只追加不改写）</b>
          <el-tag type="info" size="small">事件时间 vs 接收时间分离，迟到凭证可被时点重放识别</el-tag>
        </div>
      </template>
      <el-table :data="events" stripe size="small">
        <el-table-column prop="event_code" label="凭证号" width="140" class-name="mono" />
        <el-table-column prop="equipment_code" label="设备" width="160" class-name="mono" />
        <el-table-column prop="position_code" label="装配位置" width="150" class-name="mono" />
        <el-table-column prop="physical_code" label="实物序列号" width="140" class-name="mono" />
        <el-table-column prop="model_code" label="部件型号" width="130" class-name="mono" />
        <el-table-column label="动作" width="90">
          <template #default="{ row }">
            <el-tag :type="row.event_type === 'INSTALL' ? 'success' : 'info'" size="small">
              {{ row.event_type === 'INSTALL' ? '安装' : '拆卸' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="event_time" label="实际拆装时间" width="180" />
        <el-table-column prop="received_at" label="平台接收时间" width="180">
          <template #default="{ row }">
            <span :class="{ late: isLate(row) }">{{ row.received_at }}</span>
            <el-tag v-if="isLate(row)" type="danger" size="small" style="margin-left:4px">迟到</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="裁定" width="100">
          <template #default="{ row }">
            <el-tag :type="{ NORMAL: 'info', FROZEN: 'danger', ADJUDICATED: 'success' }[row.adjudication]" size="small">
              {{ { NORMAL: '正常', FROZEN: '冻结', ADJUDICATED: '已裁定' }[row.adjudication] }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="trust_score" label="可信度" width="80" />
      </el-table>
    </el-card>
    <el-card>
      <template #header><b>出厂配置记录（快照重放基线）</b></template>
      <el-table :data="factories" stripe size="small">
        <el-table-column prop="equipment_code" label="设备" width="170" class-name="mono" />
        <el-table-column prop="mount_position_id" label="位置ID" width="90" />
        <el-table-column prop="part_model_id" label="型号ID" width="90" />
        <el-table-column prop="physical_part_id" label="实物ID" width="90" />
        <el-table-column prop="factory_time" label="出厂时间" width="180" />
        <el-table-column prop="source_ref" label="来源" min-width="160" class-name="mono" />
        <el-table-column prop="trust_score" label="可信度" width="90" />
      </el-table>
    </el-card>
  </div>
</template>

<script setup>
import { onMounted, ref } from 'vue'
import http from '../api/http'
const events = ref([]); const factories = ref([])
function isLate(row) {
  const ev = new Date(row.event_time); const rc = new Date(row.received_at)
  return (rc - ev) > 24 * 3600 * 1000
}
onMounted(async () => {
  events.value = (await http.get('/install-events')).data
  factories.value = (await http.get('/factory-records')).data
})
</script>
<style scoped>
.card-head { display: flex; justify-content: space-between; align-items: center; }
.late { color: #dc2626; font-weight: 600; }
</style>
