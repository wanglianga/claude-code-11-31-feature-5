<template>
  <div>
    <el-card class="page-card">
      <template #header><b>兼容事实求值器（对指定快照+位置+候选备件，输出结论与最小证据链）</b></template>
      <el-form :inline="true">
        <el-form-item label="快照">
          <el-select v-model="req.snapshotId" placeholder="选择快照" style="width:240px" @change="onSnapshot">
            <el-option v-for="s in snapshots" :key="s.id"
              :label="`${s.snapshot_no}（${statusText(s.status)}${s.has_conflict ? '/冲突' : ''}）`" :value="s.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="装配位置">
          <el-select v-model="req.mountPositionId" placeholder="选择位置" style="width:200px" :disabled="!req.snapshotId">
            <el-option v-for="p in positions" :key="p.mount_position_id"
              :label="`${p.position_code}（在装:${p.model_code || '空位'}）`" :value="p.mount_position_id" />
          </el-select>
        </el-form-item>
        <el-form-item label="候选备件型号">
          <el-select v-model="req.candidateModelId" placeholder="选择候选型号" style="width:230px" filterable>
            <el-option v-for="m in partModels" :key="m.id" :label="`${m.entity_code} ${m.display_name}`" :value="m.id" />
          </el-select>
        </el-form-item>
        <el-form-item>
          <el-button type="primary" :loading="loading" :disabled="!auth.canWrite" @click="evaluate">求值</el-button>
        </el-form-item>
      </el-form>
      <el-alert type="info" :closable="false"
        title="平台仅产出兼容性事实与证据链，不生成领料单、维修工序或库存调拨——那些属于外部业务系统。" />
    </el-card>

    <el-card class="page-card" v-if="result">
      <template #header>
        <div class="card-head">
          <b>求值结果 {{ result.fact_no }}</b>
          <el-tag :type="verdictType(result.verdict)" size="large">{{ verdictText(result.verdict) }}</el-tag>
        </div>
      </template>
      <el-descriptions :column="3" border size="small">
        <el-descriptions-item label="锁定规则版本">{{ result.rule_version_locked || '无' }}</el-descriptions-item>
        <el-descriptions-item label="事实状态">
          <el-tag :type="statusType(result.status)" size="small">{{ statusText(result.status) }}</el-tag>
        </el-descriptions-item>
        <el-descriptions-item label="签发时间">{{ result.issued_at || '未签发' }}</el-descriptions-item>
      </el-descriptions>
      <h4>最小证据链（可回放）</h4>
      <div v-for="(step, i) in result.evidence_chain" :key="i" class="evidence-step">
        <el-tag size="small" :type="stepColor(step.result)" style="margin-right:8px">{{ step.result }}</el-tag>
        <b>{{ stepTitle(step.step) }}</b>
        <pre class="chain-json">{{ JSON.stringify(step.detail, null, 2) }}</pre>
      </div>
      <el-alert v-if="result.failed_constraints?.length" type="error" :closable="false" style="margin-top:8px"
        :title="`未通过约束 ${result.failed_constraints.length} 项（联动判定 FAIL）`" />
    </el-card>

    <el-card>
      <template #header>
        <div class="card-head">
          <b>兼容事实与发布闸门（草稿/已验证/待复核/已废止分通道）</b>
          <el-radio-group v-model="view" size="small" @change="loadFacts">
            <el-radio-button value="all">全部</el-radio-button>
            <el-radio-button value="public">仅对外(已验证)</el-radio-button>
          </el-radio-group>
        </div>
      </template>
      <el-table :data="facts" stripe size="small">
        <el-table-column prop="fact_no" label="事实号" width="150" class-name="mono" />
        <el-table-column prop="snapshot_id" label="快照ID" width="90" />
        <el-table-column label="结论" width="120">
          <template #default="{ row }"><el-tag :type="verdictType(row.verdict)" size="small">{{ verdictText(row.verdict) }}</el-tag></template>
        </el-table-column>
        <el-table-column prop="rule_version_locked" label="规则版本" width="100" />
        <el-table-column label="闸门状态" width="100">
          <template #default="{ row }"><el-tag :type="statusType(row.status)" size="small">{{ statusText(row.status) }}</el-tag></template>
        </el-table-column>
        <el-table-column prop="correction_note" label="更正说明" min-width="200" />
        <el-table-column label="操作" width="230">
          <template #default="{ row }">
            <el-button size="small" type="success" v-if="['DRAFT','REVIEW'].includes(row.status)"
                       :disabled="!auth.canWrite" @click="gate(row,'publish')">发布</el-button>
            <el-button size="small" type="warning" v-if="row.status==='DRAFT'"
                       :disabled="!auth.canWrite" @click="gate(row,'review')">转待复核</el-button>
            <el-button size="small" type="danger" v-if="row.status==='VERIFIED'"
                       :disabled="!auth.canWrite" @click="gate(row,'retire')">废止</el-button>
            <el-button size="small" @click="showChain(row)">证据链</el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <el-dialog v-model="chainDialog" title="证据链回放" width="70%">
      <pre class="chain-json" v-if="chainFact">{{ JSON.stringify(chainFact.evidence_chain, null, 2) }}</pre>
    </el-dialog>
  </div>
</template>

<script setup>
import { onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import http from '../api/http'
import { useAuthStore } from '../stores/auth'
import { statusType, statusText, verdictType, verdictText } from '../utils/status'

const auth = useAuthStore()
const snapshots = ref([]); const positions = ref([]); const partModels = ref([])
const facts = ref([]); const result = ref(null); const loading = ref(false)
const view = ref('all'); const chainDialog = ref(false); const chainFact = ref(null)
const req = reactive({ snapshotId: null, mountPositionId: null, candidateModelId: null })

const stepTitle = (s) => ({
  EVALUATION_REASON: '求值结论', RULE_LINEAGE: '规则血缘（图号/测试报告/版本）',
  CONSTRAINT_CHECK: '硬约束逐项校验', CO_SUBSTITUTION_NOTICE: '联换条件提示',
  SNAPSHOT_POSITION: '快照位置事实（重放指纹）', CORRECTION_NOTICE: '规则撤回更正',
  ORIGINAL_CHAIN_REF: '原始证据链（保留）', RULE_LOOKUP: '规则匹配'
}[s] || s)
const stepColor = (r) => ({ PASS: 'success', FAIL: 'danger', RESOLVED: '', NOTICE: 'warning', NOT_FOUND: 'danger', RULE_RETIRED: 'warning' }[r] || 'info')

async function onSnapshot(id) {
  const detail = (await http.get(`/snapshots/${id}`)).data
  positions.value = detail.positions
  req.mountPositionId = null
}
async function evaluate() {
  if (!req.snapshotId || !req.mountPositionId || !req.candidateModelId) { ElMessage.warning('请补全求值条件'); return }
  loading.value = true
  try {
    result.value = (await http.post('/compatibility/evaluate', { ...req })).data
    ElMessage.success(`求值完成：${verdictText(result.value.verdict)}`)
    loadFacts()
  } finally { loading.value = false }
}
async function gate(row, action) {
  let reason = ''
  if (action !== 'publish') {
    const r = await ElMessageBox.prompt('请填写操作原因', '闸门操作', { inputValue: action === 'retire' ? '事实失效，停止对外' : '需进一步复核' })
    reason = r.value
  }
  try {
    await http.post(`/facts/${row.id}/gate`, { action, reason })
    ElMessage.success('闸门操作成功')
    loadFacts()
  } catch (e) { /* 拦截提示已由拦截器/后端返回 */ }
}
function showChain(row) { chainFact.value = row; chainDialog.value = true }
async function loadFacts() {
  facts.value = view.value === 'public'
    ? (await http.get('/facts/public')).data
    : (await http.get('/compatibility/facts-all')).data
}
onMounted(async () => {
  snapshots.value = (await http.get('/snapshots')).data
  partModels.value = (await http.get('/entities', { params: { entityType: 'PART_MODEL' } })).data
  facts.value = (await http.get('/compatibility/facts-all')).data
})
</script>
<style scoped>
.card-head { display: flex; justify-content: space-between; align-items: center; }
h4 { margin: 14px 0 8px; }
</style>
