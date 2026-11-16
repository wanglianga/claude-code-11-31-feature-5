// 闸门/事实/快照状态展示映射
export function statusText(s) {
  return {
    DRAFT: '草稿', VERIFIED: '已验证', RETIRED: '已废止', REVIEW: '待复核', FROZEN: '冻结'
  }[s] || s
}
export function statusType(s) {
  return {
    DRAFT: 'info', VERIFIED: 'success', RETIRED: 'danger', REVIEW: 'warning', FROZEN: 'danger'
  }[s] || 'info'
}
export function verdictText(v) {
  return { PASS: '通过', FAIL: '不通过', INSUFFICIENT_EVIDENCE: '证据不足' }[v] || v
}
export function verdictType(v) {
  return { PASS: 'success', FAIL: 'danger', INSUFFICIENT_EVIDENCE: 'warning' }[v] || 'info'
}
export function entityTypeText(t) {
  return {
    PART_MODEL: '部件型号', PHYSICAL_PART: '实物序列号', MOUNT_POSITION: '装配位置',
    EQUIPMENT: '设备序列号', SUPPLIER: '供应商编码'
  }[t] || t
}
