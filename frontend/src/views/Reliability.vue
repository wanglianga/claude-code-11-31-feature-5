<template>
  <div>
    <el-card class="page-card">
      <template #header>
        <div class="card-head">
          <b>替代件现场可靠性观察（同一故障定义、同一随访窗口，按型号 × 工况分层）</b>
          <el-tag type="info" size="small">观察段由追加式装拆事件派生 · 报告按截止点/接收边界版本化</el-tag>
        </div>
      </template>
      <el-form :inline="true">
        <el-form-item label="对比项目">
          <el-select v-model="programId" style="width:300px" @change="loadAll">
            <el-option v-for="p in programs" :key="p.id"
                       :label="`${p.program_name}（${p.original_model_code} → ${p.replacement_model_code}）`"
                       :value="p.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="截止点">
          <el-date-picker v-model="form.cutoffAt" type="date" value-format="YYYY-MM-DDT00:00:00[Z]"
                          style="width:170px" />
        </el-form-item>
        <el-form-item label="已知边界（接收时点）">
          <el-date-picker v-model="form.asOfReceived" type="date" value-format="YYYY-MM-DDT00:00:00[Z]"
                          style="width:170px" placeholder="留空=全部已知" />
        </el-form-item>
        <el-form-item>
          <el-button type="primary" :disabled="!auth.canWrite" :loading="generating" @click="generate">
            生成新版本报告
          </el-button>
        </el-form-item>
      </el-form>
      <el-alert type="info" :closable="false"
        title="固定窗口比例把新近安装件排除在外（漏失），Kaplan-Meier 把期末在役/转装/无故障拆除作为删失（能更早用资料，但须检验删失机制）；原因不明拆除既不当作故障也不当作完成观察。" />
    </el-card>

    <el-card class="page-card">
      <template #header>
        <div class="card-head">
          <b>报告版本（迟到工单不改写旧报告，另起版本并保留截止点）</b>
          <el-tag size="small">{{ reports.length }} 个版本</el-tag>
        </div>
      </template>
      <el-table :data="reports" stripe size="small" @row-click="selectReport" highlight-current-row>
        <el-table-column prop="report_no" label="报告号" width="260" class-name="mono" />
        <el-table-column prop="version" label="版本" width="70" />
        <el-table-column prop="cutoff_at" label="截止点" width="180" />
        <el-table-column label="已知边界" width="180">
          <template #default="{ row }">{{ row.as_of_received || '全部已接收' }}</template>
        </el-table-column>
        <el-table-column prop="followup_days" label="随访期(天)" width="100" />
        <el-table-column label="故障段" width="90">
          <template #default="{ row }">{{ row.summary?.nFailures ?? '—' }}</template>
        </el-table-column>
        <el-table-column label="原因不明拆除" width="120">
          <template #default="{ row }">{{ row.summary?.nUnknownRemoval ?? '—' }}</template>
        </el-table-column>
        <el-table-column label="版本关系" min-width="240">
          <template #default="{ row }">
            <span v-if="row.supersedes_report_id" class="mono">
              取代 v{{ versionOf(row.supersedes_report_id) }}（新增故障 {{ delta(row) }}）
            </span>
            <span v-else>首版</span>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <template v-if="detail">
      <el-card class="page-card">
        <template #header>
          <div class="card-head">
            <b>{{ detail.report.report_no }} · 型号 × 工况分层风险</b>
            <el-tag type="success" size="small">截止 {{ fmtDay(detail.report.cutoff_at) }} · {{ detail.report.followup_days }} 天窗口</el-tag>
          </div>
        </template>
        <el-table :data="detail.strata" stripe size="small">
          <el-table-column prop="environment" label="工况" width="120" />
          <el-table-column label="部件" width="170">
            <template #default="{ row }">
              <el-tag size="small" :type="row.part_role === 'REPLACEMENT' ? 'warning' : ''">
                {{ row.part_role === 'REPLACEMENT' ? '替代件' : '原件' }}
              </el-tag>
              <span class="mono" style="margin-left:6px">{{ row.model_code }}</span>
            </template>
          </el-table-column>
          <el-table-column prop="n_segments" label="观察段" width="80" />
          <el-table-column label="固定窗口" width="190">
            <template #default="{ row }">
              <span class="mono">{{ row.n_fixed_events }}/{{ row.n_fixed_denominator }}</span>
              <span class="muted">（满窗 {{ row.n_completed_window }}）</span>
            </template>
          </el-table-column>
          <el-table-column label="固定窗口风险" width="160">
            <template #default="{ row }">
              <b v-if="row.risk_fixed != null">{{ pct(row.risk_fixed) }}</b>
              <span v-else class="muted">不可用</span>
              <div v-if="row.risk_fixed != null" class="muted">
                [{{ pct(row.risk_fixed_low) }}, {{ pct(row.risk_fixed_high) }}]
              </div>
            </template>
          </el-table-column>
          <el-table-column label="KM 风险" width="150">
            <template #default="{ row }">
              <b>{{ pct(row.km_risk) }}</b>
              <div class="muted">[{{ pct(row.km_risk_low) }}, {{ pct(row.km_risk_high) }}]</div>
            </template>
          </el-table-column>
          <el-table-column prop="person_days" label="人·天" width="90">
            <template #default="{ row }">{{ Math.round(row.person_days) }}</template>
          </el-table-column>
          <el-table-column prop="median_followup_days" label="中位随访(天)" width="110">
            <template #default="{ row }">
              {{ row.median_followup_days == null ? '—' : Math.round(row.median_followup_days) }}
            </template>
          </el-table-column>
          <el-table-column label="删失构成" width="190">
            <template #default="{ row }">
              <div class="muted">
                截止 {{ row.censor_end_of_cutoff }} · 转装 {{ row.censor_transfer }} ·
                无故障拆 {{ row.censor_removed_no_fault }} · 不明 {{ row.censor_unknown }}
              </div>
            </template>
          </el-table-column>
          <el-table-column label="提示" min-width="220">
            <template #default="{ row }">
              <el-tag v-for="w in parseJson(row.warnings)" :key="w" size="small" type="warning"
                      style="margin:2px 4px 2px 0">{{ warningText(w) }}</el-tag>
            </template>
          </el-table-column>
        </el-table>
      </el-card>

      <el-card class="page-card">
        <template #header>
          <b>环境内 原件 vs 替代件：风险差、观察时间与删失机制检验</b>
        </template>
        <el-table :data="detail.comparisons" stripe size="small">
          <el-table-column prop="environment" label="工况" width="120" />
          <el-table-column label="样本（原件/替代件）" width="150">
            <template #default="{ row }">{{ row.original_n }} / {{ row.replacement_n }}</template>
          </el-table-column>
          <el-table-column label="可比性" width="230">
            <template #default="{ row }">
              <el-tag v-if="row.comparable" type="success" size="small">可比</el-tag>
              <el-tooltip v-else :content="row.not_comparable_reason || ''" placement="top">
                <el-tag type="info" size="small">不作比较</el-tag>
              </el-tooltip>
              <div v-if="!row.comparable" class="muted">{{ row.not_comparable_reason }}</div>
            </template>
          </el-table-column>
          <el-table-column label="风险差（固定窗口）" width="190">
            <template #default="{ row }">
              <template v-if="row.comparable">
                <b :class="diffClass(row.risk_diff_fixed)">{{ signedPct(row.risk_diff_fixed) }}</b>
                <div class="muted">[{{ signedPct(row.risk_diff_fixed_low) }}, {{ signedPct(row.risk_diff_fixed_high) }}]</div>
              </template>
              <span v-else class="muted">—</span>
            </template>
          </el-table-column>
          <el-table-column label="风险差（KM）" width="180">
            <template #default="{ row }">
              <template v-if="row.comparable">
                <b :class="diffClass(row.risk_diff_km)">{{ signedPct(row.risk_diff_km) }}</b>
                <div class="muted">[{{ signedPct(row.risk_diff_km_low) }}, {{ signedPct(row.risk_diff_km_high) }}]</div>
              </template>
              <span v-else class="muted">—</span>
            </template>
          </el-table-column>
          <el-table-column label="log-rank p" width="120">
            <template #default="{ row }">
              <span v-if="row.logrank_p != null" class="mono">{{ round(row.logrank_p, 3) }}</span>
              <span v-else class="muted">—</span>
            </template>
          </el-table-column>
          <el-table-column label="删失检验" min-width="220">
            <template #default="{ row }">
              <template v-if="row.censoring_check?.checked">
                <el-tag :type="row.censoring_check.verdict === 'NO_EVIDENCE_OF_INFORMATIVE_CENSORING' ? 'success' : 'warning'"
                        size="small">{{ censoringText(row.censoring_check.verdict) }}</el-tag>
                <div class="muted mono">
                  p(删失尺度)={{ row.censoring_check.logRankPOnCensoring == null
                    ? '—' : round(row.censoring_check.logRankPOnCensoring, 3) }}
                </div>
              </template>
              <span v-else class="muted">未检验</span>
            </template>
          </el-table-column>
          <el-table-column label="提示" min-width="200">
            <template #default="{ row }">
              <el-tag v-for="f in parseJson(row.flags)" :key="f" size="small" type="warning"
                      style="margin:2px 4px 2px 0">{{ flagText(f) }}</el-tag>
            </template>
          </el-table-column>
        </el-table>
        <el-alert type="warning" :closable="false" style="margin-top:10px"
          title="风险差 = 替代件 − 原件（正值=替代件风险更高）。小样本区间很宽；删失检验 p<0.05 表示删失可能与分组相关，此时 KM 结论需谨慎。" />
      </el-card>

      <el-card class="page-card">
        <template #header>
          <div class="card-head">
            <b>报告事件账（首发故障 / 删失 / 重复报修 / 定义外 / 超范围）</b>
            <el-radio-group v-model="eventFilter" size="small">
              <el-radio-button value="">全部</el-radio-button>
              <el-radio-button value="FIRST_FAILURE">首发故障</el-radio-button>
              <el-radio-button value="CENSORED">删失</el-radio-button>
              <el-radio-button value="RECURRENCE">重复报修</el-radio-button>
              <el-radio-button value="EXCLUDED">排除</el-radio-button>
            </el-radio-group>
          </div>
        </template>
        <el-table :data="filteredEvents" stripe size="small" max-height="360">
          <el-table-column prop="segment_code" label="观察段" width="230" class-name="mono" />
          <el-table-column prop="physical_code" label="实物" width="140" class-name="mono" />
          <el-table-column prop="model_code" label="型号" width="130" class-name="mono" />
          <el-table-column prop="environment" label="工况" width="110" />
          <el-table-column label="事件" width="200">
            <template #default="{ row }">
              <el-tag size="small" :type="eventTagType(row.event_type)">{{ eventText(row.event_type) }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column label="时间点(天)" width="110">
            <template #default="{ row }">{{ row.time_days == null ? '—' : round(row.time_days, 1) }}</template>
          </el-table-column>
          <el-table-column prop="ticket_no" label="工单" width="120" class-name="mono" />
          <el-table-column prop="reason" label="说明" min-width="260" />
        </el-table>
      </el-card>

      <el-card>
        <template #header><b>故障工单（只追加；接收时间晚于故障时间 24h 记为迟到）</b></template>
        <el-table :data="tickets" stripe size="small" max-height="300">
          <el-table-column prop="ticket_no" label="工单号" width="120" class-name="mono" />
          <el-table-column prop="physical_code" label="实物" width="140" class-name="mono" />
          <el-table-column prop="model_code" label="型号" width="130" class-name="mono" />
          <el-table-column prop="fault_code" label="故障编码" width="120" class-name="mono" />
          <el-table-column prop="occurred_at" label="故障时间" width="180" />
          <el-table-column label="接收时间" width="220">
            <template #default="{ row }">
              <span :class="{ late: isLate(row) }">{{ row.received_at }}</span>
              <el-tag v-if="isLate(row)" type="danger" size="small" style="margin-left:4px">迟到</el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="fault_desc" label="描述" min-width="200" />
        </el-table>
      </el-card>
    </template>
  </div>
</template>

<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import http from '../api/http'
import { useAuthStore } from '../stores/auth'

const auth = useAuthStore()
const programs = ref([]); const reports = ref([]); const detail = ref(null)
const tickets = ref([]); const programId = ref(null)
const generating = ref(false); const eventFilter = ref('')
const form = reactive({ cutoffAt: '2025-09-30T00:00:00Z', asOfReceived: '' })

const parseJson = (v) => {
  if (!v) return []
  if (Array.isArray(v)) return v
  if (typeof v === 'object') return v
  try { return JSON.parse(v) } catch { return [] }
}
const pct = (v) => (v == null || Number.isNaN(v)) ? '—' : `${(v * 100).toFixed(1)}%`
const signedPct = (v) => (v == null || Number.isNaN(v)) ? '—'
  : `${v >= 0 ? '+' : ''}${(v * 100).toFixed(1)}%`
const round = (v, n = 2) => (v == null ? '—' : Number(v).toFixed(n))
const fmtDay = (v) => (v ? String(v).slice(0, 10) : '—')
const diffClass = (v) => (v == null ? '' : v > 0 ? 'diff-worse' : v < 0 ? 'diff-better' : '')
const versionOf = (id) => reports.value.find(r => r.id === id)?.version ?? '?'
const delta = (row) => {
  const prev = row.summary?.previousVersion
  if (!prev) return '—'
  return (row.summary?.nFailures ?? 0) - (prev.nFailures ?? 0)
}
const warningText = (w) => ({
  SMALL_SAMPLE: '小样本', INSUFFICIENT_FOLLOWUP: '观察不足',
  UNKNOWN_REMOVAL_EXCLUDED: '原因不明拆除已排除', NO_EVENTS: '无故障',
  CLUSTERED_INSTALL: '集中安装'
}[w] || w)
const flagText = (f) => ({
  SMALL_SAMPLE_WIDE_INTERVAL: '小样本区间宽', CENSORING_DEPENDS_ON_GROUP: '删失与分组相关',
  UNKNOWN_REMOVAL_EXCLUDED: '已排除不明拆除', RECENT_INSTALL_NOT_IN_FIXED_WINDOW: '新装件未进固定窗口',
  NOT_COMPARABLE: '不可比较', OUT_OF_SCOPE: '范围外', EMPTY_DENOMINATOR: '分母为空'
}[f] || f)
const censoringText = (v) => ({
  NO_EVIDENCE_OF_INFORMATIVE_CENSORING: '未见依赖性删失',
  CENSORING_MAY_BE_INFORMATIVE: '删失可能携带信息',
  INSUFFICIENT_DATA: '资料不足'
}[v] || v)
const eventText = (t) => ({
  FIRST_FAILURE: '首发故障', CENSORED: '删失', FIXED_WINDOW_INCOMPLETE: '未满窗口删失',
  EXCLUDED_UNKNOWN_REMOVAL: '原因不明拆除（排除）', RECURRENCE: '重复报修（不计数）',
  NOT_IN_DEFINITION: '定义外故障', OUT_OF_SEGMENT: '段外故障', SCOPE_EXCLUDED: '范围外'
}[t] || t)
const eventTagType = (t) => ({
  FIRST_FAILURE: 'danger', CENSORED: 'info', FIXED_WINDOW_INCOMPLETE: 'warning',
  EXCLUDED_UNKNOWN_REMOVAL: 'warning', RECURRENCE: '', NOT_IN_DEFINITION: 'info',
  OUT_OF_SEGMENT: 'info', SCOPE_EXCLUDED: 'info'
}[t] || 'info')
const isLate = (row) => (new Date(row.received_at) - new Date(row.occurred_at)) > 24 * 3600 * 1000

const filteredEvents = computed(() => {
  const events = detail.value?.events || []
  if (!eventFilter.value) return events
  if (eventFilter.value === 'EXCLUDED') {
    return events.filter(e => ['EXCLUDED_UNKNOWN_REMOVAL', 'SCOPE_EXCLUDED', 'NOT_IN_DEFINITION'].includes(e.event_type))
  }
  if (eventFilter.value === 'CENSORED') {
    return events.filter(e => ['CENSORED', 'FIXED_WINDOW_INCOMPLETE'].includes(e.event_type))
  }
  return events.filter(e => e.event_type === eventFilter.value)
})

async function loadAll() {
  reports.value = (await http.get('/reliability/reports', { params: { programId: programId.value } })).data
  tickets.value = (await http.get('/reliability/tickets', { params: { programId: programId.value } })).data
  if (reports.value.length) await selectReport(reports.value[0])
  else detail.value = null
}
async function selectReport(row) {
  detail.value = (await http.get(`/reliability/reports/${row.id}`)).data
}
async function generate() {
  if (!programId.value || !form.cutoffAt) { ElMessage.warning('请选择项目与截止点'); return }
  generating.value = true
  try {
    const payload = { programId: programId.value, cutoffAt: form.cutoffAt }
    if (form.asOfReceived) payload.asOfReceived = form.asOfReceived
    const r = await http.post('/reliability/reports/generate', payload)
    ElMessage.success(`已生成 ${r.data.report.report_no}`)
    await loadAll()
  } finally { generating.value = false }
}
onMounted(async () => {
  programs.value = (await http.get('/reliability/programs')).data
  programId.value = programs.value[0]?.id ?? null
  await loadAll()
})
</script>

<style scoped>
.card-head { display: flex; justify-content: space-between; align-items: center; gap: 10px; }
.muted { color: #94a3b8; font-size: 12px; }
.late { color: #dc2626; font-weight: 600; }
.diff-worse { color: #dc2626; }
.diff-better { color: #059669; }
</style>
