<template>
  <div>
    <el-card class="page-card">
      <template #header><b>影响分析触发（规则 / 图号 / 实装记录变更后定位受影响快照、结论与下游数据集）</b></template>
      <el-space wrap>
        <el-button type="danger" :disabled="!auth.canWrite" @click="runRuleRetire">模拟规则撤回影响（SUB-RULE-001）</el-button>
        <el-button :disabled="!auth.canWrite" @click="runDrawing">图号修订影响（revB id=2）</el-button>
        <el-button :disabled="!auth.canWrite" @click="runEvent">迟到凭证影响（事件 id=4）</el-button>
      </el-space>
      <el-alert class="tip" type="warning" :closable="false"
        title="规则撤回纪律：仅引用该规则的快照/事实进入待复核；已签发结论保留当时锁定版本，另发更正事实，绝不静默改写历史报告。" />
    </el-card>

    <el-card>
      <template #header><b>影响分析记录</b></template>
      <el-table :data="list" stripe size="small">
        <el-table-column prop="analysis_no" label="分析号" width="210" class-name="mono" />
        <el-table-column prop="trigger_type" label="触发类型" width="180" />
        <el-table-column prop="trigger_ref" label="触发对象" width="180" class-name="mono" />
        <el-table-column label="受影响快照" width="120">
          <template #default="{ row }">{{ arr(row.affected_snapshot_ids).length }}</template>
        </el-table-column>
        <el-table-column label="受影响事实" width="120">
          <template #default="{ row }">{{ arr(row.affected_fact_ids).length }}</template>
        </el-table-column>
        <el-table-column prop="summary" label="结论摘要" min-width="280" />
        <el-table-column label="操作" width="100">
          <template #default="{ row }"><el-button size="small" @click="open(row)">明细</el-button></template>
        </el-table-column>
      </el-table>
    </el-card>

    <el-dialog v-model="dialog" title="影响分析明细" width="65%">
      <pre class="chain-json" v-if="current">{{ JSON.stringify(current, null, 2) }}</pre>
    </el-dialog>
  </div>
</template>

<script setup>
import { onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import http from '../api/http'
import { useAuthStore } from '../stores/auth'

const auth = useAuthStore()
const list = ref([]); const dialog = ref(false); const current = ref(null)
const arr = (v) => Array.isArray(v) ? v : (v ? [v] : [])

async function load() { list.value = (await http.get('/impact')).data }
async function runRuleRetire() {
  await http.post('/rules/1/retire', { reason: '供应商通知：C3 批次协议栈缺陷，收回替代授权' })
  await http.post('/impact/rule/1', { changeKind: 'RETIRE', note: '供应商收回 SUB-RULE-001' })
  ElMessage.success('规则撤回影响分析完成（若规则已撤回则仅做影响分析）')
  load()
}
async function runDrawing() { await http.post('/impact/drawing-revision/2', {}); ElMessage.success('图号修订影响分析完成'); load() }
async function runEvent() { await http.post('/impact/install-event/4', {}); ElMessage.success('实装事件影响分析完成'); load() }
function open(row) { current.value = row; dialog.value = true }
onMounted(load)
</script>
<style scoped>.tip { margin-top: 12px; }</style>
