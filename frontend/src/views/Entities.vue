<template>
  <div>
    <el-card class="page-card">
      <template #header>
        <div class="card-head">
          <b>五类实体（型号 / 实物 / 位置 / 设备 / 供应商分开建模）</b>
          <div>
            <el-select v-model="filterType" placeholder="实体类型" clearable style="width:150px" @change="load">
              <el-option v-for="t in types" :key="t.v" :label="t.l" :value="t.v" />
            </el-select>
            <el-input v-model="keyword" placeholder="编码/名称" clearable style="width:180px;margin-left:8px" @change="load" />
          </div>
        </div>
      </template>
      <el-table :data="entities" stripe size="small">
        <el-table-column label="类型" width="110">
          <template #default="{ row }"><el-tag size="small">{{ entityTypeText(row.entity_type) }}</el-tag></template>
        </el-table-column>
        <el-table-column prop="entity_code" label="业务编码" width="180" class-name="mono" />
        <el-table-column prop="display_name" label="名称（仅展示，不参与合并）" min-width="200" />
        <el-table-column prop="supplier_code" label="供应商编码" width="140" class-name="mono" />
        <el-table-column label="规格" min-width="220">
          <template #default="{ row }"><span class="mono">{{ JSON.stringify(row.spec_payload) }}</span></template>
        </el-table-column>
        <el-table-column label="状态" width="100">
          <template #default="{ row }"><el-tag :type="statusType(row.status)" size="small">{{ statusText(row.status) }}</el-tag></template>
        </el-table-column>
      </el-table>
    </el-card>

    <el-card class="page-card">
      <template #header><b>同名消歧工作队列（名称相似只登记候选，绝不自动合并）</b></template>
      <el-table :data="candidates" stripe size="small">
        <el-table-column prop="name_text" label="同名文本" width="140" />
        <el-table-column prop="candidate_code" label="候选实体编码" width="170" class-name="mono" />
        <el-table-column prop="candidate_name" label="候选名称" min-width="140" />
        <el-table-column label="裁定状态" width="110">
          <template #default="{ row }">
            <el-tag :type="{ PENDING: 'warning', KEEP_DISTINCT: 'success', MERGED: 'danger' }[row.decision]" size="small">
              {{ { PENDING: '待裁定', KEEP_DISTINCT: '保持分立', MERGED: '已合并' }[row.decision] }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="note" label="说明" min-width="200" />
        <el-table-column label="操作" width="160">
          <template #default="{ row }">
            <el-button v-if="row.decision === 'PENDING'" type="primary" size="small"
                       :disabled="!auth.canWrite" @click="decide(row, 'KEEP_DISTINCT')">保持分立</el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <el-card>
      <template #header><b>冲突事实与凭证裁定（双重安装 / 图号区间重叠）</b></template>
      <el-table :data="conflicts" stripe size="small">
        <el-table-column prop="conflict_code" label="冲突编号" width="170" class-name="mono" />
        <el-table-column prop="conflict_type" label="类型" width="210" />
        <el-table-column prop="description" label="描述" min-width="280" />
        <el-table-column label="状态" width="120">
          <template #default="{ row }">
            <el-tag :type="row.resolution_status === 'OPEN' ? 'danger' : 'success'" size="small">
              {{ { OPEN: '待裁定', ADJUDICATED: '已裁定', SUPERSEDED: '已取代' }[row.resolution_status] }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="140">
          <template #default="{ row }">
            <el-button v-if="row.resolution_status === 'OPEN'" type="warning" size="small"
                       :disabled="!auth.canWrite" @click="adjudicate(row)">凭证裁定</el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-card>
  </div>
</template>

<script setup>
import { onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import http from '../api/http'
import { useAuthStore } from '../stores/auth'
import { statusType, statusText, entityTypeText } from '../utils/status'

const auth = useAuthStore()
const types = [
  { v: 'PART_MODEL', l: '部件型号' }, { v: 'PHYSICAL_PART', l: '实物序列号' },
  { v: 'MOUNT_POSITION', l: '装配位置' }, { v: 'EQUIPMENT', l: '设备序列号' },
  { v: 'SUPPLIER', l: '供应商编码' }
]
const entities = ref([]); const candidates = ref([]); const conflicts = ref([])
const filterType = ref(''); const keyword = ref('')

async function load() {
  entities.value = (await http.get('/entities', { params: { entityType: filterType.value, keyword: keyword.value } })).data
  candidates.value = (await http.get('/entities/alias-candidates')).data
  conflicts.value = (await http.get('/entities/conflicts')).data
}
async function decide(row, decision) {
  const { value } = await ElMessageBox.prompt('请输入人工裁定依据（系统不会自动合并）', '保持分立裁定', {
    confirmButtonText: '确认分立', inputValue: '供应商/协议不同，永久分立为两个实体'
  })
  await http.post(`/entities/alias-candidates/${row.id}/decide`, { decision, note: value })
  ElMessage.success('已记录人工裁定：保持分立')
  load()
}
async function adjudicate(row) {
  const { value } = await ElMessageBox.prompt('请输入凭证裁定结论', '凭证裁定', {
    confirmButtonText: '确认裁定', inputValue: '以现场签收凭证为准，另一条记录为误录'
  })
  await http.post(`/entities/conflicts/${row.id}/adjudicate`, { resolutionNote: value, keepFirstVoucher: true })
  ElMessage.success('冲突已裁定，相关配置解除冻结（冲突记录保留审计）')
  load()
}
onMounted(load)
</script>
<style scoped>.card-head { display: flex; justify-content: space-between; align-items: center; }</style>
