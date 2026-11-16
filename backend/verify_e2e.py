#!/usr/bin/env python3
"""端到端核验脚本：对运行中的后端做五大场景断言。用法: python3 verify_e2e.py"""
import json
import urllib.request
import urllib.error

BASE = "http://127.0.0.1:8080/api"
PASS, FAIL = [], []


def call(method, path, token=None, body=None):
    req = urllib.request.Request(BASE + path, method=method)
    if token:
        req.add_header("Authorization", "Bearer " + token)
    if body is not None:
        req.add_header("Content-Type", "application/json")
        data = json.dumps(body).encode()
    else:
        data = None
    try:
        with urllib.request.urlopen(req, data=data, timeout=15) as r:
            return r.status, json.loads(r.read().decode())
    except urllib.error.HTTPError as e:
        return e.code, json.loads(e.read().decode())


def check(name, cond, detail=""):
    (PASS if cond else FAIL).append(name)
    print(f"[{'PASS' if cond else 'FAIL'}] {name} {detail}")


# 登录
st, r = call("POST", "/auth/login", body={"username": "admin", "password": "admin123"})
token = r["data"]["token"]
check("管理员登录", st == 200 and r["data"]["role"] == "ADMIN")
st, r = call("POST", "/auth/login", body={"username": "viewer", "password": "view123"})
viewer = r["data"]["token"]
check("只读角色登录", r["data"]["role"] == "VIEWER")
st, r = call("POST", "/auth/login", body={"username": "admin", "password": "wrong"})
check("错误密码拒绝(401)", st == 401)
st, _ = call("POST", "/snapshots/compile", token=viewer,
             body={"equipmentId": 10, "pointInTime": "2024-10-01T00:00:00Z"})
check("只读角色写操作拒绝(403)", st == 403, f"http={st}")

# 场景 A：图号区间重叠
_, r = call("GET", "/entities/conflicts", token)
conflicts = r["data"]
draw = [c for c in conflicts if c["conflict_type"] == "DRAWING_RANGE_OVERLAP"]
check("场景A 图号重叠冲突存在且 OPEN", bool(draw) and draw[0]["resolution_status"] == "OPEN"
      and draw[0]["blocks_release"] is True)
_, r = call("GET", "/snapshots", token)
snaps = r["data"]
s18 = [s for s in snaps if "0018" in s["snapshot_no"]][0]
check("场景A 设备0018 快照 FROZEN+冲突", s18["status"] == "FROZEN" and s18["has_conflict"] is True)
# 闸门拦截
_, r = call("GET", f"/snapshots/{s18['id']}/facts", token)
blocked_fact = r["data"][0]
st, r = call("POST", f"/facts/{blocked_fact['id']}/gate", token,
             {"action": "publish", "reason": "测试拦截"})
check("场景A 冲突快照发布被闸门拦截", st == 409 and "冲突" in r["message"], r.get("message", ""))

# 场景 B：迟到凭证 v1/v2
v12 = [s for s in snaps if "20241201" in s["snapshot_no"]]
check("场景B 同一时点存在 v1/v2 两版本", len(v12) == 2)
_, r = call("GET", f"/snapshots/{sorted(v12, key=lambda x: x['id'])[0]['id']}", token)
pos = r["data"]["positions"]
coolant = [p for p in pos if p["position_code"] == "POS-COOLANT"]
# v1 在"接收边界"前没有任何冷却位证据 -> 该位置不应出现在快照中（无证据不虚构）
check("场景B v1 无冷却位证据（迟到凭证未纳入，位置缺失）", len(coolant) == 0)
_, r2 = call("GET", f"/snapshots/{sorted(v12, key=lambda x: x['id'])[1]['id']}", token)
coolant2 = [p for p in r2["data"]["positions"] if p["position_code"] == "POS-COOLANT"]
check("场景B v2 纳入迟到凭证后冷却位出现冷却泵",
      bool(coolant2) and coolant2[0]["model_code"] == "PM-PUMP-200")
_, r = call("GET", "/quality-events", token)
check("场景B 产生 LATE_VOUCHER 质量事件",
      any(q["event_type"] == "LATE_VOUCHER" for q in r["data"]))

# 场景 C：同名异件不自动合并
_, r = call("GET", "/entities/alias-candidates", token)
check("场景C 消歧候选全部 PENDING（无自动合并）",
      len(r["data"]) >= 2 and all(c["decision"] == "PENDING" for c in r["data"]))

# 场景 D：规则撤回 + 更正链
_, r = call("GET", "/rules", token)
rule1 = [x for x in r["data"] if x["rule_code"] == "SUB-RULE-001"][0]
check("场景D SUB-RULE-001 已撤回", rule1["status"] == "RETIRED" and rule1["effective_to"] is not None)
_, r = call("GET", "/compatibility/facts-all", token)
facts = r["data"]
verified = [f for f in facts if f["status"] == "VERIFIED"]
check("场景D 已签发结论保留锁定规则版本",
      bool(verified) and all(f["rule_version_locked"] == "v1" for f in verified))
correction = [f for f in facts if f.get("supersedes_fact_id")]
check("场景D 存在 supersedes 更正事实", bool(correction) and correction[0]["status"] == "REVIEW")

# 场景 E：两约束联动
verdicts = {f["fact_no"]: f["verdict"] for f in facts}
check("场景E 存在 PASS 结论", any(v == "PASS" for v in verdicts.values()))
fail_fact = [f for f in facts if f["verdict"] == "FAIL"][0]
check("场景E FAIL 事实含 1 项未通过约束（尺寸联动）",
      len(fail_fact["failed_constraints"]) == 1
      and fail_fact["failed_constraints"][0]["constraintType"] == "DIMENSION")
check("场景E 证据链含规则血缘+约束校验+快照位置",
      {"RULE_LINEAGE", "CONSTRAINT_CHECK", "SNAPSHOT_POSITION"}
      <= {e["step"] for e in fail_fact["evidence_chain"]})

# 双重安装冻结
double = [c for c in conflicts if c["conflict_type"] == "PHYSICAL_PART_DOUBLE_MOUNT"]
check("附加 双重安装冲突：首次运行为 OPEN 待裁定，重跑时已裁定关闭",
      (bool(double) and double[0]["resolution_status"] == "OPEN") or not any(
          c["conflict_type"] == "PHYSICAL_PART_DOUBLE_MOUNT" for c in conflicts))

# 对外发布通道
_, r = call("GET", "/facts/public", token)
pub = r["data"]
check("对外通道仅 VERIFIED+PASS",
      all(f["status"] == "VERIFIED" and f["verdict"] == "PASS" for f in pub) and len(pub) >= 1)

# ============ 可靠性观察：替代件批准后的可比观察 ============
_, r = call("GET", "/reliability/programs", token)
prog = [x for x in r["data"] if x["program_code"] == "RELPROG-COOLPUMP"][0]
check("可靠性 对比项目与同一故障定义就绪",
      prog["followup_days"] == 180 and prog["original_model_code"] == "PM-PUMP-200")

_, r = call("GET", "/reliability/reports", token)
all_reps = r["data"]
v1 = [x for x in all_reps if x["version"] == 1][0]
v2 = [x for x in all_reps if x["version"] == 2][0]
check("可靠性 同一截止点存在 v1/v2 两个版本", v1["cutoff_at"] == v2["cutoff_at"])
check("可靠性 v2 取代 v1 且旧版本保留", v2["supersedes_report_id"] == v1["id"])
check("可靠性 v1 未含迟到工单（9 件首发）", v1["summary"]["nFailures"] == 9)
check("可靠性 v2 纳入迟到工单（10 件首发）", v2["summary"]["nFailures"] == 10)

_, d1 = call("GET", f"/reliability/reports/{v1['id']}", token)
_, d2 = call("GET", f"/reliability/reports/{v2['id']}", token)
seg1 = {s["segment_code"]: s for s in d1["data"]["segments"]}
seg2 = {s["segment_code"]: s for s in d2["data"]["segments"]}
late_seg = [c for c, s in seg2.items()
            if seg2[c]["end_kind"] == "FAILURE" and seg2[c]["observed_days"] > 120]
check("可靠性 迟到工单在 v2 成为首发故障，v1 中该段为删失",
      bool(late_seg) and seg1[late_seg[0]]["end_kind"] == "CENSORED")

cmp2 = {c["environment"]: c for c in d2["data"]["comparisons"]}
check("可靠性 某工况缺原件时不作比较（HUMID）",
      cmp2.get("HUMID") is not None and cmp2["HUMID"]["comparable"] is False
      and "缺少原件" in (cmp2["HUMID"]["not_comparable_reason"] or ""))
check("可靠性 高温工况可比并给出风险差",
      cmp2["HIGH_TEMP"]["comparable"] is True
      and cmp2["HIGH_TEMP"]["risk_diff_fixed"] is not None
      and cmp2["HIGH_TEMP"]["censoring_check"]["checked"] is True)
check("可靠性 删失机制检验已执行（给出 p 值或明确资料不足）",
      cmp2["HIGH_TEMP"]["censoring_check"]["verdict"]
      in ("NO_EVIDENCE_OF_INFORMATIVE_CENSORING", "CENSORING_MAY_BE_INFORMATIVE", "INSUFFICIENT_DATA"))

ev2 = d2["data"]["events"]
check("可靠性 重复报修只留档不重复计数",
      any(e["event_type"] == "RECURRENCE" for e in ev2)
      and sum(1 for e in ev2 if e["event_type"] == "FIRST_FAILURE")
          == d2["data"]["report"]["summary"]["nFailures"])
check("可靠性 原因不明拆除不当作完成观察",
      any(e["event_type"] == "EXCLUDED_UNKNOWN_REMOVAL" for e in ev2)
      and d2["data"]["report"]["summary"]["nUnknownRemoval"] == 1)
check("可靠性 定义外故障（噪声）被排除",
      any(e["event_type"] == "NOT_IN_DEFINITION" and e["ticket_no"] == "FT-REL-011" for e in ev2))

strata2 = [s for s in d2["data"]["strata"] if s["environment"] == "HIGH_TEMP"]
repl = [s for s in strata2 if s["part_role"] == "REPLACEMENT"][0]
check("可靠性 集中安装/观察不足标注为提示",
      "CLUSTERED_INSTALL" in repl["warnings"])
check("可靠性 固定窗口与 KM 两套口径同时输出",
      repl["n_fixed_denominator"] > 0 and repl["km_risk"] is not None
      and repl["censor_transfer"] >= 1)
check("可靠性 转装与无故障返库均作为删失（不当作完成/故障）",
      repl["censor_transfer"] >= 1 and repl["censor_removed_no_fault"] >= 1)
check("可靠性 期末在役按截止点删失处理", repl["censor_end_of_cutoff"] >= 1)
check("可靠性 新近安装件被固定窗口排除但进入 KM 风险集",
      repl["n_completed_window"] < repl["n_segments"]
      and repl["n_km_risk"] >= repl["n_fixed_denominator"])

# 迟到工单追加后不自动改写旧报告，需显式生成新版本
_, r = call("GET", "/reliability/tickets", token)
already = any(t["ticket_no"] == "FT-E2E-LATE" for t in r["data"])
st, r = call("POST", "/reliability/tickets", token, {
    "ticketNo": "FT-E2E-LATE", "programId": prog["id"], "physicalPartId": 305,
    "partModelId": 39, "equipmentId": 116, "occurredAt": "2025-05-01T08:00:00Z",
    "receivedAt": "2025-09-20T08:00:00Z", "faultCode": "LEAK", "faultDesc": "E2E 迟到工单",
    "causeCategory": "SEAL"})
check("可靠性 迟到工单可追加并触发质量事件（重跑时拒绝重复）",
      st == 200 if not already else st == 409)
_, r = call("GET", "/reliability/reports", token)
max_version = max(x["version"] for x in r["data"])
latest_id = [x for x in r["data"] if x["version"] == max_version][0]["id"]
check("可靠性 追加工单后旧报告故障数不变（不自动改写）",
      [x for x in r["data"] if x["id"] == v2["id"]][0]["summary"]["nFailures"] == 10)
st, r = call("POST", f"/reliability/reports/{v2['id']}/refresh", token)
check("可靠性 重算生成新版本并沿版本链取代最新版", st == 200
      and r["data"]["report"]["version"] == max_version + 1
      and r["data"]["report"]["supersedes_report_id"] == latest_id
      and r["data"]["report"]["cutoff_at"] == v2["cutoff_at"]
      and r["data"]["report"]["summary"]["nFailures"] >= 10)
st, r = call("POST", "/reliability/tickets", token, {
    "ticketNo": "FT-E2E-LATE", "programId": prog["id"], "physicalPartId": 305,
    "partModelId": 39, "equipmentId": 116, "occurredAt": "2025-05-01T08:00:00Z",
    "faultCode": "LEAK"})
check("可靠性 故障工单只追加（重复工单号拒绝）", st == 409)
st, _ = call("POST", f"/reliability/reports/{v2['id']}/refresh", viewer)
check("可靠性 只读角色不能生成报告(403)", st == 403)

# 凭证裁定后冲突解除（写操作，验证裁定闭环；重复执行时已裁定也视为闭环）
if double:
    cid = double[0]["id"]
    st, r = call("POST", f"/entities/conflicts/{cid}/adjudicate", token,
                 {"resolutionNote": "E2E 核验裁定：以现场签收凭证为准", "keepFirstVoucher": True})
    check("凭证裁定闭环", st == 200 and r["data"]["resolution_status"] == "ADJUDICATED")
else:
    _, r = call("GET", "/entities/conflicts", token)
    check("凭证裁定闭环（前序执行已裁定，冲突已关闭）",
          not any(c["conflict_type"] == "PHYSICAL_PART_DOUBLE_MOUNT"
                  and c["resolution_status"] == "OPEN" for c in r["data"]))

print("\n================ 汇总 ================")
print(f"通过 {len(PASS)} 项，失败 {len(FAIL)} 项")
if FAIL:
    print("失败项:", FAIL)
    raise SystemExit(1)
print("全部端到端断言通过 ✅")
