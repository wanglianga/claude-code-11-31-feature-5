<template>
  <div>
    <el-row :gutter="16">
      <el-col :span="6" v-for="card in cards" :key="card.label">
        <el-card class="stat">
          <div class="stat-label">{{ card.label }}</div>
          <div class="stat-num" :style="{ color: card.color }">{{ card.value }}</div>
        </el-card>
      </el-col>
    </el-row>

    <el-card class="page-card">
      <template #header><b>五大核验场景（均可在本平台回放）</b></template>
      <el-timeline>
        <el-timeline-item v-for="s in scenarios" :key="s.title" :type="s.type" :timestamp="s.tag">
          <el-card shadow="never">
            <div class="sc-title">{{ s.title }}</div>
            <div class="sc-desc">{{ s.desc }}</div>
            <el-button link type="primary" @click="$router.push(s.route)">前往核验 →</el-button>
          </el-card>
        </el-timeline-item>
      </el-timeline>
    </el-card>
  </div>
</template>

<script setup>
import { onMounted, ref } from 'vue'
import http from '../api/http'

const stats = ref({ snapshots: 0, facts: 0, conflicts: 0, quality: 0 })
const cards = ref([])

const scenarios = [
  { title: 'A. 跨修订序列号区间重叠', tag: '冲突阻止发布', type: 'danger',
    desc: '设备 SN-0018 同时命中图号 DRW-MOTOR-100 的 revA/revB，编译器产生冲突事实并冻结快照，发布闸门拦截，不默认采用最新上传。',
    route: '/snapshots' },
  { title: 'B. 迟到拆装凭证', tag: '版本化重放', type: 'primary',
    desc: '冷却泵凭证事件时间 2024-08-20、2025-03-01 才接收。按接收边界重放出 v1（空位）/v2（已装）两个快照，旧结论不改写。',
    route: '/snapshots' },
  { title: 'C. 同名异件', tag: '禁止自动合并', type: 'warning',
    desc: '三个“伺服驱动单元”分属不同供应商与协议，系统只建消歧候选，由人工 KEEP_DISTINCT 永久分立。',
    route: '/entities' },
  { title: 'D. 供应商规则撤回', tag: '历史保留+更正链', type: 'info',
    desc: 'SUB-RULE-001 撤回后，已签发事实保留锁定规则版本，另发更正事实；引用快照进入待复核而非静默改写。',
    route: '/impact' },
  { title: 'E. 两条约束联动', tag: '最小证据链', type: 'success',
    desc: '电源 380V + 协议 EtherCAT（或 + 尺寸 2U）同时满足才 PASS；502 因尺寸 3U≠2U 判 FAIL，逐项约束进入证据链。',
    route: '/compatibility' },
  { title: 'F. 替代件批准后的可比观察', tag: '风险差 + 删失检验', type: 'warning',
    desc: '冷却泵原件 PM-PUMP-200 vs 替代件 PM-PUMP-210：同一故障定义与随访窗口，按型号×工况输出固定窗口比例、KM 生存风险与风险差；迟到工单另发新版本，原因不明拆除单列。',
    route: '/reliability' }
]

onMounted(async () => {
  const [snaps, facts, conflicts, quality] = await Promise.all([
    http.get('/snapshots'), http.get('/facts/public'),
    http.get('/entities/conflicts'), http.get('/quality-events')
  ])
  cards.value = [
    { label: '配置快照（版本化）', value: snaps.data.length, color: '#1d4ed8' },
    { label: '已对外发布兼容事实', value: facts.data.length, color: '#059669' },
    { label: '未裁定冲突事实', value: conflicts.data.filter(c => c.resolution_status === 'OPEN').length, color: '#dc2626' },
    { label: '质量事件', value: quality.data.length, color: '#d97706' }
  ]
})
</script>

<style scoped>
.stat { text-align: center; }
.stat-label { color: #64748b; font-size: 13px; }
.stat-num { font-size: 32px; font-weight: 700; margin-top: 6px; }
.sc-title { font-weight: 600; margin-bottom: 4px; }
.sc-desc { color: #475569; font-size: 13px; line-height: 1.6; margin-bottom: 6px; }
</style>
