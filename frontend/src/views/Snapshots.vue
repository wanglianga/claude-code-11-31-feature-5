<template>
  <div>
    <el-card class="page-card">
      <template #header><b>按维修时点编译配置快照（每次生成新版本，历史快照不可变）</b></template>
      <el-form :inline="true">
        <el-form-item label="设备">
          <el-select v-model="form.equipmentId" placeholder="选择设备" style="width:230px">
            <el-option v-for="e in equipments" :key="e.id" :label="`${e.entity_code} ${e.display_name}`" :value="e.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="维修时点">
          <el-date-picker v-model="form.pit" type="datetime" value-format="YYYY-MM-DDTHH:mm:ss[Z]" placeholder="选择维修时点" />
        </el-form-item>
        <el-form-item label="已知边界（接收时点，留空=当前全部已知）">
          <el-date-picker v-model="form.asOf" type="datetime" value-format="YYYY-MM-DDTHH:mm:ss[Z]" placeholder="用于迟到凭证重放" />
        </el-form-item>
        <el-form-item>
          <el-button type="primary" :loading="loading" :disabled="!auth.canWrite" @click="compile">编译新版本快照</el-button>
        </el-form-item>
      </el-form>
      <el-alert type="info" :closable="false"
        title="将“已知边界”设为 2024-12-01 可重放迟到凭证接收前的认知（冷却位为空）；留空则纳入全部已接收凭证生成新版本，旧快照保留不改写。" />
    </el-card>

    <el-card class="page-card">
      <template #header><b>版本化快照列表（同一设备多个版本并存）</b></template>
      <el-table :data="snapshots" stripe size="small">
        <el-table-column prop="snapshot_no" label="快照号" width="200" class-name="mono" />
        <el-table-column prop="equipment_code" label="设备" width="170" class-name="mono" />
        <el-table-column prop="point_in_time" label="维修时点" width="180" />
        <el-table-column prop="compiled_at" label="编译时间" width="180" />
        <el-table-column label="冲突" width="90">
          <template #default="{ row }">
            <el-tag :type="row.has_conflict ? 'danger' : 'success'" size="small">{{ row.has_conflict ? '有冲突' : '无' }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="100">
          <template #default="{ row }"><el-tag :type="statusType(row.status)" size="small">{{ statusText(row.status) }}</el-tag></template>
        </el-table-column>
        <el-table-column prop="min_trust_score" label="最低可信度" width="100" />
        <el-table-column label="操作" width="120">
          <template #default="{ row }"><el-button size="small" type="primary" @click="detail(row)">详情/回放</el-button></template>
        </el-table-column>
      </el-table>
    </el-card>

    <el-drawer v-model="drawer" size="62%" :title="`快照回放 ${d?.snapshot?.snapshot_no || ''}`">
      <div v-if="d">
        <el-descriptions :column="2" border size="small">
          <el-descriptions-item label="维修时点">{{ d.snapshot.point_in_time }}</el-descriptions-item>
          <el-descriptions-item label="状态">
            <el-tag :type="statusType(d.snapshot.status)" size="small">{{ statusText(d.snapshot.status) }}</el-tag>
          </el-descriptions-item>
          <el-descriptions-item label="重放指纹" :span="2"><span class="mono">{{ d.snapshot.replay_digest }}</span></el-descriptions-item>
          <el-descriptions-item label="重放事件ID" :span="2"><span class="mono">{{ json(d.snapshot.source_event_ids) }}</span></el-descriptions-item>
        </el-descriptions>

        <h4>位置级不可变事实</h4>
        <el-table :data="d.positions" size="small" border>
          <el-table-column prop="position_code" label="装配位置" width="150" class-name="mono" />
          <el-table-column prop="model_code" label="部件型号" min-width="140" class-name="mono" />
          <el-table-column prop="physical_code" label="实物序列号" min-width="140" class-name="mono" />
          <el-table-column label="依据" width="130">
            <template #default="{ row }">
              <el-tag size="small">{{ { FACTORY: '出厂基线', INSTALL_EVENT: '拆装凭证', REMOVE_EMPTY: '拆卸空位', CONFLICT: '冲突' }[row.basis] }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column label="位置状态" width="100">
            <template #default="{ row }">
              <el-tag :type="{ RESOLVED: 'success', FROZEN: 'danger', CONFLICT: 'danger', VACANT: 'info' }[row.position_state]" size="small">
                {{ { RESOLVED: '已解析', FROZEN: '冻结', CONFLICT: '冲突', VACANT: '空位' }[row.position_state] }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="trust_score" label="可信度" width="80" />
        </el-table>

        <h4>冲突事实（{{ d.conflicts.length }}）</h4>
        <el-table :data="d.conflicts" size="small" border>
          <el-table-column prop="conflict_code" label="编号" width="170" class-name="mono" />
          <el-table-column prop="conflict_type" label="类型" width="210" />
          <el-table-column prop="description" label="描述" min-width="260" />
        </el-table>
      </div>
    </el-drawer>
  </div>
</template>

<script setup>
import { onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import http from '../api/http'
import { useAuthStore } from '../stores/auth'
import { statusType, statusText } from '../utils/status'

const auth = useAuthStore()
const equipments = ref([]); const snapshots = ref([])
const loading = ref(false); const drawer = ref(false); const d = ref(null)
const form = reactive({ equipmentId: 10, pit: '2024-12-01T00:00:00Z', asOf: '' })
const json = (v) => JSON.stringify(v)

async function load() {
  equipments.value = (await http.get('/entities', { params: { entityType: 'EQUIPMENT' } })).data
  snapshots.value = (await http.get('/snapshots')).data
}
async function compile() {
  if (!form.equipmentId || !form.pit) { ElMessage.warning('请选择设备和维修时点'); return }
  loading.value = true
  try {
    const payload = { equipmentId: form.equipmentId, pointInTime: form.pit }
    if (form.asOf) payload.asOfReceived = form.asOf
    const r = await http.post('/snapshots/compile', payload)
    ElMessage.success(`已生成新版本快照 ${r.data.snapshot_no}`)
    load()
  } finally { loading.value = false }
}
async function detail(row) {
  d.value = (await http.get(`/snapshots/${row.id}`)).data
  drawer.value = true
}
onMounted(load)
</script>
<style scoped>h4 { margin: 16px 0 8px; }</style>
