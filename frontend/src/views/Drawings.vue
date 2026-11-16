<template>
  <el-card class="page-card">
    <template #header><b>工程图号与修订（含序列号生效边界，重叠区间在快照编译时产生冲突）</b></template>
    <el-table :data="revisions" stripe size="small" :row-class-name="rowClass">
      <el-table-column prop="drawing_no" label="图号" width="170" class-name="mono" />
      <el-table-column prop="revision" label="修订" width="80" />
      <el-table-column label="序列号区间" width="170">
        <template #default="{ row }">
          <span class="mono">{{ row.serial_range_from }} ~ {{ row.serial_range_to }}</span>
        </template>
      </el-table-column>
      <el-table-column prop="effective_from" label="生效起" width="170" />
      <el-table-column prop="effective_to" label="生效止" width="150" />
      <el-table-column prop="source_ref" label="来源文件" min-width="170" class-name="mono" />
      <el-table-column prop="payload_digest" label="文件指纹" min-width="160" class-name="mono" />
      <el-table-column prop="change_note" label="变更说明" min-width="200" />
      <el-table-column label="状态" width="100">
        <template #default="{ row }"><el-tag :type="statusType(row.status)" size="small">{{ statusText(row.status) }}</el-tag></template>
      </el-table-column>
    </el-table>
    <el-alert class="tip" type="warning" :closable="false"
      title="DRW-MOTOR-100 的 revA(SN-0001~0020) 与 revB(SN-0015~0030) 在 SN-0015~0020 重叠；设备 SN-0018 编译快照时命中两版 -> 冲突事实，阻止兼容发布。" />
  </el-card>
</template>

<script setup>
import { onMounted, ref } from 'vue'
import http from '../api/http'
import { statusType, statusText } from '../utils/status'
const revisions = ref([])
function rowClass({ row }) {
  return row.drawing_no === 'DRW-MOTOR-100' ? 'warn-row' : ''
}
onMounted(async () => { revisions.value = (await http.get('/drawing-revisions')).data })
</script>
<style scoped>
.tip { margin-top: 12px; }
:deep(.warn-row) { background: #fef3c7 !important; }
</style>
