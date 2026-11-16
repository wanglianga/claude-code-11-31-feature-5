<template>
  <div>
    <el-card class="page-card">
      <template #header><b>资料接入契约（字段 / 来源 / 版本号 / 生效边界 / 可信度）</b></template>
      <el-table :data="contracts" stripe size="small">
        <el-table-column prop="contract_code" label="契约编号" width="130" class-name="mono" />
        <el-table-column prop="contract_name" label="契约名称" min-width="180" />
        <el-table-column prop="source_team" label="维护团队" width="120" />
        <el-table-column prop="source_system" label="来源系统" width="100" />
        <el-table-column prop="schema_version" label="版本" width="80" />
        <el-table-column prop="effective_from" label="生效起" width="170" />
        <el-table-column prop="trust_score" label="可信度" width="90">
          <template #default="{ row }">
            <el-progress :percentage="Number(row.trust_score)" :stroke-width="10" />
          </template>
        </el-table-column>
        <el-table-column prop="status" label="状态" width="100">
          <template #default="{ row }"><el-tag :type="statusType(row.status)" size="small">{{ statusText(row.status) }}</el-tag></template>
        </el-table-column>
      </el-table>
    </el-card>
    <el-card>
      <template #header><b>契约版本索引（来源文件指纹长期保留，支持重放）</b></template>
      <el-table :data="versions" stripe size="small">
        <el-table-column prop="contract_code" label="契约" width="140" class-name="mono" />
        <el-table-column prop="schema_version" label="版本" width="90" />
        <el-table-column prop="source_team" label="团队" width="120" />
        <el-table-column prop="source_ref" label="来源文件" min-width="200" class-name="mono" />
        <el-table-column prop="payload_digest" label="文件指纹" min-width="180" class-name="mono" />
        <el-table-column prop="trust_score" label="可信度" width="90" />
        <el-table-column prop="registered_at" label="登记时间" width="180" />
      </el-table>
    </el-card>
  </div>
</template>

<script setup>
import { onMounted, ref } from 'vue'
import http from '../api/http'
import { statusType, statusText } from '../utils/status'

const contracts = ref([])
const versions = ref([])
onMounted(async () => {
  contracts.value = (await http.get('/contracts')).data
  versions.value = (await http.get('/contracts/versions')).data
})
</script>
