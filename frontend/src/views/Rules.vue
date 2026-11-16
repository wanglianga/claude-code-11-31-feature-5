<template>
  <div>
    <el-card class="page-card">
      <template #header><b>供应商替代规则（替代方向 / 联换条件 / 生效版本 / 血缘）</b></template>
      <el-table :data="rules" stripe size="small">
        <el-table-column prop="rule_code" label="规则号" width="140" class-name="mono" />
        <el-table-column prop="rule_version" label="版本" width="80" />
        <el-table-column label="替代方向（单向）" min-width="200">
          <template #default="{ row }">
            <span class="mono">{{ row.from_model }} → {{ row.to_model }}</span>
          </template>
        </el-table-column>
        <el-table-column prop="supplier_name" label="供应商" width="150" />
        <el-table-column prop="effective_from" label="生效起" width="170" />
        <el-table-column prop="effective_to" label="生效止" width="170" />
        <el-table-column label="状态" width="100">
          <template #default="{ row }"><el-tag :type="statusType(row.status)" size="small">{{ statusText(row.status) }}</el-tag></template>
        </el-table-column>
        <el-table-column label="操作" width="180">
          <template #default="{ row }">
            <el-button size="small" @click="showLineage(row)">血缘</el-button>
            <el-button v-if="row.status !== 'RETIRED'" type="danger" size="small" :disabled="!auth.canWrite"
                       @click="retire(row)">撤回</el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <el-card class="page-card">
      <template #header><b>规则硬约束（协议 / 尺寸 / 电源，全部满足才 PASS —— 联动判定）</b></template>
      <el-table :data="constraints" stripe size="small">
        <el-table-column prop="rule_id" label="规则ID" width="90" />
        <el-table-column label="约束类型" width="110">
          <template #default="{ row }">
            <el-tag size="small" :type="typeColor(row.constraint_type)">{{ typeText(row.constraint_type) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="attribute_name" label="属性" width="130" />
        <el-table-column prop="compare_op" label="比较" width="90" />
        <el-table-column prop="expected_value" label="期望值" width="120" class-name="mono" />
        <el-table-column prop="actual_source" label="取值来源" min-width="140" class-name="mono" />
      </el-table>
    </el-card>

    <el-drawer v-model="drawer" size="55%" title="规则血缘解析（连回图号 / 测试报告 / 生效版本 / 引用结论）">
      <div v-if="lineage">
        <el-descriptions :column="1" border size="small">
          <el-descriptions-item label="规则">{{ lineage.rule.rule_code }} {{ lineage.rule.rule_version }}（{{ lineage.rule.status }}）</el-descriptions-item>
          <el-descriptions-item label="方向">{{ lineage.rule.direction_note }}</el-descriptions-item>
          <el-descriptions-item label="图号修订">
            <span v-if="lineage.drawingRevision?.id">
              {{ lineage.drawingRevision.drawing_no }} rev{{ lineage.drawingRevision.revision }}
              （{{ lineage.drawingRevision.serial_range_from }}~{{ lineage.drawingRevision.serial_range_to }}）
              <div class="mono sub">来源 {{ lineage.drawingRevision.source_ref }} / {{ lineage.drawingRevision.payload_digest }}</div>
            </span>
            <span v-else>—</span>
          </el-descriptions-item>
          <el-descriptions-item label="测试报告">
            <span v-if="lineage.testReport?.id">
              {{ lineage.testReport.inspection_code }} / {{ lineage.testReport.test_item }} /
              <el-tag size="small" :type="lineage.testReport.result === 'PASS' ? 'success' : 'warning'">{{ lineage.testReport.result }}</el-tag>
            </span>
            <span v-else>—</span>
          </el-descriptions-item>
        </el-descriptions>
        <h4>约束（{{ lineage.constraints.length }} 条联动）</h4>
        <el-table :data="lineage.constraints" size="small" border>
          <el-table-column prop="constraint_type" label="类型" width="100" />
          <el-table-column prop="attribute_name" label="属性" width="110" />
          <el-table-column prop="expected_value" label="期望值" width="100" />
        </el-table>
        <h4>引用该规则的兼容事实（{{ lineage.referencingFacts.length }}）</h4>
        <el-table :data="lineage.referencingFacts" size="small" border>
          <el-table-column prop="fact_no" label="事实号" width="150" class-name="mono" />
          <el-table-column label="结论" width="110">
            <template #default="{ row }"><el-tag :type="verdictType(row.verdict)" size="small">{{ verdictText(row.verdict) }}</el-tag></template>
          </el-table-column>
          <el-table-column prop="rule_version_locked" label="锁定版本" width="100" />
          <el-table-column label="状态" width="100">
            <template #default="{ row }"><el-tag :type="statusType(row.status)" size="small">{{ statusText(row.status) }}</el-tag></template>
          </el-table-column>
        </el-table>
      </div>
    </el-drawer>
  </div>
</template>

<script setup>
import { onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import http from '../api/http'
import { useAuthStore } from '../stores/auth'
import { statusType, statusText, verdictType, verdictText } from '../utils/status'

const auth = useAuthStore()
const rules = ref([]); const constraints = ref([])
const drawer = ref(false); const lineage = ref(null)

const typeText = (t) => ({ PROTOCOL: '协议', DIMENSION: '尺寸', POWER: '电源', INTERFACE: '接口', ENVIRONMENT: '环境' }[t] || t)
const typeColor = (t) => ({ PROTOCOL: '', DIMENSION: 'success', POWER: 'warning' }[t] || 'info')

async function showLineage(row) {
  lineage.value = (await http.get(`/rules/${row.id}/lineage`)).data[0]
  drawer.value = true
}
async function retire(row) {
  const { value } = await ElMessageBox.prompt(`供应商收回规则 ${row.rule_code} 的原因`, '规则撤回（影响分析将自动触发）', {
    confirmButtonText: '确认撤回', inputValue: '供应商通知该批次存在缺陷，收回替代授权'
  })
  await http.post(`/rules/${row.id}/retire`, { reason: value })
  ElMessage.success('规则已撤回；已签发结论保留版本并另发更正，引用快照进入待复核')
  load()
}
async function load() {
  rules.value = (await http.get('/rules')).data
  constraints.value = (await http.get('/constraints')).data
}
onMounted(load)
</script>
<style scoped>
.sub { color: #64748b; font-size: 12px; }
h4 { margin: 16px 0 8px; }
</style>
