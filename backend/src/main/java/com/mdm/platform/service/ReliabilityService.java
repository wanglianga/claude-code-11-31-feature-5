package com.mdm.platform.service;

import com.mdm.platform.repository.JdbcSupport;
import com.mdm.platform.security.CurrentUser;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 替代件现场可靠性观察（批准替代件之后的可比观察段）。
 *
 * 关键口径：
 *  1. 观察段按实物序列号 + 安装事件派生，段内只取“首发故障”；同段后续报修记为 RECURRENCE，不重复计数。
 *  2. 段结束原因分四类：首发故障 / 截止点删失 / 转装删失 / 无故障拆除删失；原因不明拆除既不当作故障，
 *     也不当作完成观察 -> 主分析排除，并单独计数，避免“尚未报修=低风险”的误判。
 *  3. 两套口径并列：
 *       - 固定随访窗口：分母只含观察满窗口（或窗口内故障）的段，比例直观但漏掉新近安装件；
 *       - Kaplan-Meier：把期末在役、转装、无故障拆除作为删失，能更早使用资料，但依赖删失机制可忽略。
 *  4. 分层 = 型号 × 工况。某层缺一类部件时只出单层描述，不计算风险差。
 *  5. 迟到工单：旧报告保留原截止点与接收边界，重新生成即新版本（supersedes 链），旧版本不改写。
 */
@Service
public class ReliabilityService {

    private static final double EPS = 1e-9;

    private final JdbcSupport db;
    private final QualityEventService qualityEvents;

    public ReliabilityService(JdbcSupport db, QualityEventService qualityEvents) {
        this.db = db;
        this.qualityEvents = qualityEvents;
    }

    public record GenerateRequest(long programId, OffsetDateTime cutoffAt, OffsetDateTime asOfReceived,
                                  String operator) {}

    // ------------------------------------------------------------------
    // 观察段派生
    // ------------------------------------------------------------------

    private record SegmentEvent(String type, OffsetDateTime time, Double timeDays, String ticketNo,
                                String reason, Map<String, Object> detail) {}

    private record SegmentSkeleton(long modelId, String role, long physicalId, long equipmentId,
                                   long positionId, String environment, boolean inScope,
                                   long installEventId, OffsetDateTime installedAt,
                                   OffsetDateTime boundary, String boundaryKind,
                                   Map<String, Object> install, List<String> warnings) {}

    private record Segment(SegmentSkeleton skeleton, String code, OffsetDateTime endedAt, String endKind,
                           String censorReason, Long firstFailureTicketId, double observedDays,
                           List<SegmentEvent> events) {}

    private Map<String, Object> program(long programId) {
        Map<String, Object> p = db.queryOne("SELECT * FROM reliability_program WHERE id=?", programId);
        if (p == null) {
            throw new IllegalArgumentException("可靠性对比项目不存在: " + programId);
        }
        return p;
    }

    private Set<String> failureCodes(Map<String, Object> program) {
        Map<String, Object> criteria = db.toMap(program.get("failure_criteria"));
        Object codes = criteria.get("codes");
        if (!(codes instanceof List<?> list) || list.isEmpty()) {
            return Set.of();
        }
        return list.stream().map(String::valueOf).collect(Collectors.toSet());
    }

    /**
     * 按截止点与接收边界派生观察段。
     * asOfReceived 非空时只使用此边界之前接收到的安装/拆卸/工单（模拟“当时已知”）。
     */
    private List<Segment> deriveSegments(Map<String, Object> program, OffsetDateTime cutoff,
                                         OffsetDateTime asOf) {
        long programId = ((Number) program.get("id")).longValue();
        long originalModel = ((Number) program.get("original_model_id")).longValue();
        long replacementModel = ((Number) program.get("replacement_model_id")).longValue();
        String programCode = String.valueOf(program.get("program_code"));
        Set<String> codes = failureCodes(program);

        String eventSql = "SELECT e.*, eq.spec_payload AS equipment_spec " +
                "FROM install_event e JOIN master_entity eq ON eq.id=e.equipment_id " +
                "WHERE e.part_model_id IN (?,?) AND e.event_time<=?";
        List<Object> args = new ArrayList<>(List.of(originalModel, replacementModel, cutoff));
        if (asOf != null) {
            eventSql += " AND e.received_at<=?";
            args.add(asOf);
        }
        eventSql += " ORDER BY e.physical_part_id, e.event_time, e.id";
        List<Map<String, Object>> events = db.query(eventSql, args.toArray());

        String ticketSql = "SELECT * FROM failure_ticket WHERE program_id=? AND occurred_at<=?";
        List<Object> ticketArgs = new ArrayList<>(List.of(programId, cutoff));
        if (asOf != null) {
            ticketSql += " AND received_at<=?";
            ticketArgs.add(asOf);
        }
        ticketSql += " ORDER BY physical_part_id, occurred_at, id";
        List<Map<String, Object>> tickets = db.query(ticketSql, ticketArgs.toArray());

        Map<Long, List<Map<String, Object>>> eventsByPart = new LinkedHashMap<>();
        for (Map<String, Object> e : events) {
            eventsByPart.computeIfAbsent(((Number) e.get("physical_part_id")).longValue(),
                    k -> new ArrayList<>()).add(e);
        }
        Map<Long, List<Map<String, Object>>> ticketsByPart = new LinkedHashMap<>();
        for (Map<String, Object> t : tickets) {
            ticketsByPart.computeIfAbsent(((Number) t.get("physical_part_id")).longValue(),
                    k -> new ArrayList<>()).add(t);
        }

        List<Segment> segments = new ArrayList<>();
        Set<Long> allParts = new LinkedHashSet<>(eventsByPart.keySet());
        allParts.addAll(ticketsByPart.keySet());

        for (Long physicalId : allParts) {
            List<Map<String, Object>> partEvents = eventsByPart.getOrDefault(physicalId, List.of());
            List<Map<String, Object>> partTickets = ticketsByPart.getOrDefault(physicalId, List.of());

            // 1) 由安装事件切出段骨架：段边界 = 同实物的下一次拆/装（或截止点）
            List<SegmentSkeleton> skeletons = new ArrayList<>();
            for (int idx = 0; idx < partEvents.size(); idx++) {
                Map<String, Object> install = partEvents.get(idx);
                if (!"INSTALL".equals(install.get("event_type"))) {
                    continue;
                }
                OffsetDateTime installedAt = db.toOffsetDateTime(install.get("event_time"));
                OffsetDateTime boundary = cutoff;
                String boundaryKind = "END_OF_CUTOFF";
                for (int j = idx + 1; j < partEvents.size(); j++) {
                    Map<String, Object> next = partEvents.get(j);
                    OffsetDateTime nextTime = db.toOffsetDateTime(next.get("event_time"));
                    if (nextTime.isBefore(installedAt)) {
                        continue;
                    }
                    boundary = nextTime.isAfter(cutoff) ? cutoff : nextTime;
                    if ("INSTALL".equals(next.get("event_type"))) {
                        boundaryKind = "TRANSFER";
                    } else {
                        // 拆卸后同实物又被安装 -> 转装；否则按拆卸原因分类
                        boolean reInstalled = false;
                        for (int k = j + 1; k < partEvents.size(); k++) {
                            Map<String, Object> later = partEvents.get(k);
                            if ("INSTALL".equals(later.get("event_type"))
                                    && !db.toOffsetDateTime(later.get("event_time")).isBefore(nextTime)) {
                                reInstalled = true;
                                break;
                            }
                        }
                        Object reason = next.get("remove_reason");
                        if (reInstalled) {
                            boundaryKind = "TRANSFER";
                        } else {
                            boundaryKind = reason == null ? "REMOVED_UNKNOWN" : String.valueOf(reason);
                        }
                    }
                    break;
                }
                Map<String, Object> eqSpec = db.toMap(install.get("equipment_spec"));
                // 项目口径：设备必须声明工况才进入可比分层；未声明工况的安装单独列为“范围外”，
                // 不静默混入 STANDARD，避免不同工况/无关设备污染分母
                boolean inScope = eqSpec.get("environment") != null;
                String environment = inScope ? String.valueOf(eqSpec.get("environment")) : "UNSPECIFIED";
                long modelId = ((Number) install.get("part_model_id")).longValue();
                String role = modelId == originalModel ? "ORIGINAL" : "REPLACEMENT";
                List<String> warnings = new ArrayList<>();
                String partStatus = partStatus(physicalId);
                if ("FROZEN".equals(partStatus) || !"NORMAL".equals(install.get("adjudication"))) {
                    warnings.add("ENTITY_FROZEN_OR_ADJUDICATED");
                }
                skeletons.add(new SegmentSkeleton(modelId, role, physicalId,
                        ((Number) install.get("equipment_id")).longValue(),
                        ((Number) install.get("mount_position_id")).longValue(), environment, inScope,
                        ((Number) install.get("id")).longValue(), installedAt, boundary, boundaryKind,
                        install, warnings));
            }
            if (skeletons.isEmpty()) {
                // 只有故障工单、没有安装凭证：无法建立可比观察起点，不进入分母
                continue;
            }

            // 2) 每张工单只归属一个段：包含发生时刻的段（取最近安装），否则归为段外
            Map<Integer, List<Map<String, Object>>> ticketsBySegment = new LinkedHashMap<>();
            for (Map<String, Object> ticket : partTickets) {
                OffsetDateTime occurred = db.toOffsetDateTime(ticket.get("occurred_at"));
                int owner = -1;
                for (int i = 0; i < skeletons.size(); i++) {
                    SegmentSkeleton s = skeletons.get(i);
                    boolean within = !occurred.isBefore(s.installedAt()) && !occurred.isAfter(s.boundary());
                    if (within && (owner < 0 || s.installedAt().isAfter(skeletons.get(owner).installedAt()))) {
                        owner = i;
                    }
                }
                if (owner < 0) {
                    // 段外故障：归属到发生前最近一段，留痕但不计数
                    for (int i = 0; i < skeletons.size(); i++) {
                        if (!skeletons.get(i).installedAt().isAfter(occurred)) {
                            owner = i;
                        }
                    }
                    if (owner < 0) {
                        owner = 0;
                    }
                }
                ticketsBySegment.computeIfAbsent(owner, k -> new ArrayList<>()).add(ticket);
            }

            // 3) 段内首发故障判定与事件账
            for (int i = 0; i < skeletons.size(); i++) {
                SegmentSkeleton s = skeletons.get(i);
                List<Map<String, Object>> assigned = ticketsBySegment.getOrDefault(i, List.of());
                List<Map<String, Object>> inSegment = new ArrayList<>();
                List<Map<String, Object>> outOfSegment = new ArrayList<>();
                for (Map<String, Object> t : assigned) {
                    OffsetDateTime occurred = db.toOffsetDateTime(t.get("occurred_at"));
                    if (!occurred.isBefore(s.installedAt()) && !occurred.isAfter(s.boundary())) {
                        inSegment.add(t);
                    } else {
                        outOfSegment.add(t);
                    }
                }
                inSegment.sort(Comparator.comparing(t -> db.toOffsetDateTime(t.get("occurred_at"))));

                Map<String, Object> first = null;
                List<SegmentEvent> segmentEvents = new ArrayList<>();
                for (Map<String, Object> t : inSegment) {
                    String faultCode = String.valueOf(t.get("fault_code"));
                    if (!codes.contains(faultCode)) {
                        segmentEvents.add(new SegmentEvent("NOT_IN_DEFINITION",
                                db.toOffsetDateTime(t.get("occurred_at")),
                                days(s.installedAt(), db.toOffsetDateTime(t.get("occurred_at"))),
                                String.valueOf(t.get("ticket_no")),
                                "故障编码 " + faultCode + " 不在本项目故障定义内，不计数",
                                Map.of("faultCode", faultCode)));
                    } else if (first == null) {
                        first = t;
                    } else {
                        segmentEvents.add(new SegmentEvent("RECURRENCE",
                                db.toOffsetDateTime(t.get("occurred_at")),
                                days(s.installedAt(), db.toOffsetDateTime(t.get("occurred_at"))),
                                String.valueOf(t.get("ticket_no")),
                                "同一观察段内重复报修，只取首发故障，不重复计数",
                                Map.of("faultCode", faultCode)));
                    }
                }
                for (Map<String, Object> t : outOfSegment) {
                    segmentEvents.add(new SegmentEvent("OUT_OF_SEGMENT",
                            db.toOffsetDateTime(t.get("occurred_at")),
                            days(s.installedAt(), db.toOffsetDateTime(t.get("occurred_at"))),
                            String.valueOf(t.get("ticket_no")),
                            "故障发生在本观察段之外（转装或拆装之后），不归属本段",
                            Map.of("faultCode", t.get("fault_code"))));
                }

                OffsetDateTime endedAt;
                String endKind;
                String censorReason = null;
                Long firstTicketId = null;
                if (first != null) {
                    endedAt = db.toOffsetDateTime(first.get("occurred_at"));
                    endKind = "FAILURE";
                    firstTicketId = ((Number) first.get("id")).longValue();
                    segmentEvents.add(0, new SegmentEvent("FIRST_FAILURE", endedAt,
                            days(s.installedAt(), endedAt), String.valueOf(first.get("ticket_no")),
                            "首发故障计入分析",
                            Map.of("faultCode", first.get("fault_code"),
                                    "receivedAt", first.get("received_at"),
                                    "lateReceived", asOf == null
                                            || db.toOffsetDateTime(first.get("received_at")).isAfter(asOf))));
                } else {
                    endedAt = s.boundary();
                    endKind = "CENSORED";
                    censorReason = switch (s.boundaryKind()) {
                        case "TRANSFER" -> "TRANSFER";
                        case "NO_FAULT", "NO_FAULT_SPARE_RETURN", "PLANNED_SERVICE" -> "REMOVED_NO_FAULT";
                        case "REMOVED_UNKNOWN", "UNKNOWN" -> "UNKNOWN_REMOVAL";
                        default -> "END_OF_CUTOFF";
                    };
                }
                double observed = Math.max(0.0, days(s.installedAt(), endedAt));
                String code = programCode + "-" + physicalId + "-" + s.installEventId();
                segments.add(new Segment(s, code, endedAt, endKind, censorReason, firstTicketId,
                        observed, segmentEvents));
            }
        }
        segments.sort(Comparator.comparing((Segment s) -> s.skeleton().installedAt())
                .thenComparing(Segment::code));
        return segments;
    }

    private String partStatus(long physicalId) {
        Map<String, Object> row = db.queryOne("SELECT status FROM master_entity WHERE id=?", physicalId);
        return row == null ? "UNKNOWN" : String.valueOf(row.get("status"));
    }

    private static double days(OffsetDateTime from, OffsetDateTime to) {
        return Duration.between(from, to).toSeconds() / 86400.0;
    }

    // ------------------------------------------------------------------
    // 报告生成
    // ------------------------------------------------------------------

    @Transactional
    public Map<String, Object> generateReport(GenerateRequest req) {
        Map<String, Object> program = program(req.programId());
        OffsetDateTime cutoff = req.cutoffAt();
        if (cutoff == null) {
            throw new IllegalArgumentException("必须指定报告截止点 cutoffAt");
        }
        int followupDays = ((Number) program.get("followup_days")).intValue();

        Map<String, Object> existing = db.queryOne(
                "SELECT id FROM reliability_report WHERE program_id=? AND cutoff_at=? AND " +
                "((as_of_received IS NULL AND CAST(? AS timestamptz) IS NULL) OR as_of_received=?)",
                req.programId(), cutoff, req.asOfReceived(), req.asOfReceived());
        if (existing != null) {
            return reportDetail(((Number) existing.get("id")).longValue());
        }

        List<Segment> segments = deriveSegments(program, cutoff, req.asOfReceived());
        persistSegments(program, segments, cutoff, req.asOfReceived());

        long reportCount = db.count("SELECT count(*) FROM reliability_report WHERE program_id=?",
                req.programId());
        Long supersedes = null;
        Map<String, Object> prev = db.queryOne(
                "SELECT id FROM reliability_report WHERE program_id=? ORDER BY version DESC LIMIT 1",
                req.programId());
        if (prev != null) {
            supersedes = ((Number) prev.get("id")).longValue();
        }
        int version = (int) reportCount + 1;
        String reportNo = String.format("REL-%s-v%d", program.get("program_code"), version);

        long reportId = db.insertReturningId(
                "INSERT INTO reliability_report(report_no,program_id,version,cutoff_at,as_of_received," +
                "followup_days,supersedes_report_id,status,generated_by) " +
                "VALUES (?,?,?,?,?,?,?,?,?) RETURNING id",
                reportNo, req.programId(), version, cutoff, req.asOfReceived(), followupDays,
                supersedes, "VERIFIED",
                req.operator() == null ? CurrentUser.username() : req.operator());

        Map<String, Map<String, Object>> strata = computeStrata(segments, followupDays);
        for (Map<String, Object> s : strata.values()) {
            persistStratum(reportId, s);
        }
        List<Map<String, Object>> comparisons = computeComparisons(segments, strata, followupDays);
        for (Map<String, Object> c : comparisons) {
            persistComparison(reportId, c);
        }
        persistReportEvents(reportId, segments, followupDays);

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("nSegments", segments.size());
        summary.put("nStrata", strata.size());
        summary.put("nComparisons", comparisons.size());
        summary.put("nNotComparable", comparisons.stream()
                .filter(c -> !Boolean.TRUE.equals(c.get("comparable"))).count());
        summary.put("nFailures", segments.stream()
                .filter(s -> s.skeleton().inScope() && "FAILURE".equals(s.endKind())).count());
        summary.put("nOutOfScope", segments.stream().filter(s -> !s.skeleton().inScope()).count());
        summary.put("nAnalysedSegments", segments.stream().filter(s -> s.skeleton().inScope()).count());
        summary.put("nUnknownRemoval", segments.stream()
                .filter(s -> s.skeleton().inScope() && "UNKNOWN_REMOVAL".equals(s.censorReason())).count());
        summary.put("nNotInDefinition", segments.stream().flatMap(s -> s.events().stream())
                .filter(ev -> "NOT_IN_DEFINITION".equals(ev.type())).count());
        summary.put("nRecurrence", segments.stream().flatMap(s -> s.events().stream())
                .filter(ev -> "RECURRENCE".equals(ev.type())).count());
        summary.put("nOutOfSegment", segments.stream().flatMap(s -> s.events().stream())
                .filter(ev -> "OUT_OF_SEGMENT".equals(ev.type())).count());
        summary.put("followupDays", followupDays);
        summary.put("failureDefinition", program.get("failure_criteria"));
        summary.put("methodology", List.of(
                "同一观察段只取首发故障；重复报修留档但不重复计数",
                "固定窗口比例的分母只含观察满窗口或窗口内故障的段（完成病例口径）",
                "Kaplan-Meier 把期末在役、转装、无故障拆除作为删失，风险 = 1 - S(窗口)",
                "原因不明拆除既不当作故障，也不当作完成观察：主分析排除并单独计数",
                "迟到工单不进入旧版本：旧报告保留截止点，按最新接收边界另发新版本"));
        if (supersedes != null) {
            Map<String, Object> prevReport = db.queryOne(
                    "SELECT summary FROM reliability_report WHERE id=?", supersedes);
            if (prevReport != null && prevReport.get("summary") != null) {
                Map<String, Object> pm = db.toMap(prevReport.get("summary"));
                Map<String, Object> delta = new LinkedHashMap<>();
                delta.put("reportId", supersedes);
                delta.put("nFailures", pm.getOrDefault("nFailures", 0));
                delta.put("nUnknownRemoval", pm.getOrDefault("nUnknownRemoval", 0));
                delta.put("nSegments", pm.getOrDefault("nSegments", 0));
                summary.put("previousVersion", delta);
            }
        }
        db.update("UPDATE reliability_report SET summary=?::jsonb WHERE id=?",
                db.toJson(summary), reportId);
        return reportDetail(reportId);
    }

    private void persistSegments(Map<String, Object> program, List<Segment> segments,
                                 OffsetDateTime cutoff, OffsetDateTime asOf) {
        long programId = ((Number) program.get("id")).longValue();
        // 观察段按（截止点, 接收边界）重算：同口径先清后插，旧口径的版本原样保留
        String sameScope = "program_id=? AND cutoff_at=? AND " +
                "((as_of_received IS NULL AND CAST(? AS timestamptz) IS NULL) OR as_of_received=?)";
        db.update("DELETE FROM reliability_segment WHERE " + sameScope,
                programId, cutoff, asOf, asOf);
        for (Segment s : segments) {
            SegmentSkeleton sk = s.skeleton();
            Map<String, Object> detail = new LinkedHashMap<>();
            detail.put("events", s.events().stream().map(ev -> {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("type", ev.type());
                m.put("time", ev.time() == null ? null : ev.time().toString());
                m.put("timeDays", ev.timeDays());
                m.put("ticketNo", ev.ticketNo());
                m.put("reason", ev.reason());
                m.put("detail", ev.detail());
                return m;
            }).toList());
            detail.put("warnings", sk.warnings());
            detail.put("boundaryKind", sk.boundaryKind());
            db.update("INSERT INTO reliability_segment(segment_code,program_id,part_model_id,part_role," +
                            "physical_part_id,equipment_id,mount_position_id,environment,install_event_id," +
                            "installed_at,ended_at,end_kind,censor_reason,first_failure_ticket_id," +
                            "observed_days,cutoff_at,as_of_received,detail) " +
                            "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?::jsonb)",
                    s.code(), programId, sk.modelId(), sk.role(), sk.physicalId(), sk.equipmentId(),
                    sk.positionId(), sk.environment(), sk.installEventId(), sk.installedAt(), s.endedAt(),
                    s.endKind(), s.censorReason(), s.firstFailureTicketId(), s.observedDays(),
                    cutoff, asOf, db.toJson(detail));
        }
    }

    // ------------------------------------------------------------------
    // 分层与比较
    // ------------------------------------------------------------------

    private Map<String, Map<String, Object>> computeStrata(List<Segment> segments, int followupDays) {
        Map<String, List<Segment>> grouped = new LinkedHashMap<>();
        for (Segment s : segments) {
            if (!s.skeleton().inScope()) {
                continue; // 范围外：单独登记，不进入任何分层
            }
            grouped.computeIfAbsent(s.skeleton().modelId() + "|" + s.skeleton().environment(),
                    k -> new ArrayList<>()).add(s);
        }
        Map<String, Map<String, Object>> result = new LinkedHashMap<>();
        for (Map.Entry<String, List<Segment>> e : grouped.entrySet()) {
            List<Segment> group = e.getValue();
            Segment sample = group.get(0);
            SegmentSkeleton sk = sample.skeleton();
            List<Segment> eligible = group.stream()
                    .filter(s -> !"UNKNOWN_REMOVAL".equals(s.censorReason())).toList();

            int nFixed = 0;
            int eventsFixed = 0;
            int completed = 0;
            double personDays = 0.0;
            List<Double> kmTimes = new ArrayList<>();
            List<Boolean> kmEvents = new ArrayList<>();
            int cEnd = 0, cTransfer = 0, cNoFault = 0, cUnknown = 0;

            for (Segment s : group) {
                personDays += s.observedDays();
                switch (String.valueOf(s.censorReason())) {
                    case "END_OF_CUTOFF" -> cEnd++;
                    case "TRANSFER" -> cTransfer++;
                    case "REMOVED_NO_FAULT" -> cNoFault++;
                    case "UNKNOWN_REMOVAL" -> cUnknown++;
                    default -> { }
                }
            }
            for (Segment s : eligible) {
                boolean failedInWindow = "FAILURE".equals(s.endKind()) && s.observedDays() <= followupDays;
                boolean completedWindow = s.observedDays() + EPS >= followupDays;
                if (failedInWindow) {
                    nFixed++;
                    eventsFixed++;
                    completed++;
                } else if (completedWindow) {
                    nFixed++;
                    completed++;
                }
                kmTimes.add(s.observedDays());
                kmEvents.add("FAILURE".equals(s.endKind()));
            }

            SurvivalMath.KmPoint km = SurvivalMath.kmAt(kmTimes, kmEvents, followupDays);
            double risk = 1.0 - km.survival();
            double[] wilson = SurvivalMath.wilson(eventsFixed, nFixed);
            double medianFollow = SurvivalMath.medianFollowUp(kmTimes, kmEvents);

            List<String> warnings = new ArrayList<>();
            if (eligible.size() < 10 || km.events() < 3) {
                warnings.add("SMALL_SAMPLE");
            }
            if (completed < eligible.size()) {
                warnings.add("INSUFFICIENT_FOLLOWUP");
            }
            if (cUnknown > 0) {
                warnings.add("UNKNOWN_REMOVAL_EXCLUDED");
            }
            if (km.events() == 0) {
                warnings.add("NO_EVENTS");
            }
            boolean clustered = isClusteredInstall(group);
            if (clustered) {
                warnings.add("CLUSTERED_INSTALL");
            }

            Map<String, Object> model = db.queryOne(
                    "SELECT entity_code FROM master_entity WHERE id=?", sk.modelId());
            Map<String, Object> stratum = new LinkedHashMap<>();
            stratum.put("partRole", sk.role());
            stratum.put("partModelId", sk.modelId());
            stratum.put("modelCode", model == null ? String.valueOf(sk.modelId()) : model.get("entity_code"));
            stratum.put("environment", sk.environment());
            stratum.put("nSegments", group.size());
            stratum.put("nFixedDenominator", nFixed);
            stratum.put("nFixedEvents", eventsFixed);
            stratum.put("nCompletedWindow", completed);
            stratum.put("riskFixed", nFixed == 0 ? null : (double) eventsFixed / nFixed);
            stratum.put("riskFixedLow", nFixed == 0 ? null : wilson[0]);
            stratum.put("riskFixedHigh", nFixed == 0 ? null : wilson[1]);
            stratum.put("personDays", personDays);
            stratum.put("medianFollowupDays", Double.isNaN(medianFollow) ? null : medianFollow);
            stratum.put("nKmRisk", km.sampleSize());
            stratum.put("kmEvents", km.events());
            stratum.put("kmCensored", km.censored());
            stratum.put("kmSurvival", km.survival());
            stratum.put("kmRisk", risk);
            stratum.put("kmRiskLow", 1.0 - km.ciHigh());
            stratum.put("kmRiskHigh", 1.0 - km.ciLow());
            stratum.put("censorEndOfCutoff", cEnd);
            stratum.put("censorTransfer", cTransfer);
            stratum.put("censorRemovedNoFault", cNoFault);
            stratum.put("censorUnknown", cUnknown);
            stratum.put("warnings", warnings);
            Map<String, Object> detail = new LinkedHashMap<>();
            detail.put("kmTimes", kmTimes);
            detail.put("kmEvents", kmEvents);
            detail.put("excludedUnknownRemoval", group.stream()
                    .filter(s -> "UNKNOWN_REMOVAL".equals(s.censorReason()))
                    .map(Segment::code).toList());
            detail.put("segmentCodes", group.stream().map(Segment::code).toList());
            stratum.put("detail", detail);
            result.put(e.getKey(), stratum);
        }
        return result;
    }

    /** 集中安装：同一批（同一天）安装占比 >= 50%，说明批次效应与日历时间混淆，年龄段间不可直接外推。 */
    private static boolean isClusteredInstall(List<Segment> group) {
        if (group.size() < 4) {
            return false;
        }
        Map<java.time.LocalDate, Long> byDay = group.stream()
                .collect(Collectors.groupingBy(s -> s.skeleton().installedAt().toLocalDate(),
                        Collectors.counting()));
        long max = byDay.values().stream().mapToLong(Long::longValue).max().orElse(0);
        return max * 2 >= group.size();
    }

    private List<Map<String, Object>> computeComparisons(List<Segment> segments,
                                                         Map<String, Map<String, Object>> strata,
                                                         int followupDays) {
        Set<String> environments = segments.stream().filter(s -> s.skeleton().inScope())
                .map(s -> s.skeleton().environment())
                .collect(Collectors.toCollection(LinkedHashSet::new));
        List<Map<String, Object>> result = new ArrayList<>();
        for (String env : environments) {
            Map<String, Object> original = null;
            Map<String, Object> replacement = null;
            for (Map<String, Object> s : strata.values()) {
                if (!env.equals(s.get("environment"))) {
                    continue;
                }
                if ("ORIGINAL".equals(s.get("partRole"))) {
                    original = s;
                } else if ("REPLACEMENT".equals(s.get("partRole"))) {
                    replacement = s;
                }
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("environment", env);
            row.put("originalModelCode", original == null ? null : original.get("modelCode"));
            row.put("replacementModelCode", replacement == null ? null : replacement.get("modelCode"));
            row.put("originalN", original == null ? 0 : original.get("nSegments"));
            row.put("replacementN", replacement == null ? 0 : replacement.get("nSegments"));

            if (original == null || replacement == null) {
                row.put("comparable", false);
                row.put("notComparableReason", original == null
                        ? "该工况只有替代件、缺少原件观察段，不作比较"
                        : "该工况只有原件、缺少替代件观察段，不作比较");
                row.put("note", "单层结果仅作描述性统计，不输出风险差");
                row.put("flags", List.of("NOT_COMPARABLE"));
                row.put("censoringCheck", Map.of("checked", false));
                result.add(row);
                continue;
            }

            int nO = (int) original.get("nFixedDenominator");
            int xO = (int) original.get("nFixedEvents");
            int nR = (int) replacement.get("nFixedDenominator");
            int xR = (int) replacement.get("nFixedEvents");
            if (nO == 0 || nR == 0) {
                row.put("comparable", false);
                row.put("notComparableReason", "某类部件在该工况没有完成窗口的观察段，风险差不可定义");
                row.put("note", "单层结果仅作描述性统计，不输出风险差");
                row.put("flags", List.of("NOT_COMPARABLE", "EMPTY_DENOMINATOR"));
                row.put("censoringCheck", Map.of("checked", false));
                result.add(row);
                continue;
            }
            double[] diffFixed = SurvivalMath.newcombeRiskDiff(xO, nO, xR, nR);

            List<Segment> originalSegments = segments.stream()
                    .filter(s -> s.skeleton().inScope() && "ORIGINAL".equals(s.skeleton().role())
                            && env.equals(s.skeleton().environment()))
                    .filter(s -> !"UNKNOWN_REMOVAL".equals(s.censorReason())).toList();
            List<Segment> replacementSegments = segments.stream()
                    .filter(s -> s.skeleton().inScope() && "REPLACEMENT".equals(s.skeleton().role())
                            && env.equals(s.skeleton().environment()))
                    .filter(s -> !"UNKNOWN_REMOVAL".equals(s.censorReason())).toList();
            List<Double> tO = originalSegments.stream().map(Segment::observedDays).toList();
            List<Boolean> eO = originalSegments.stream().map(s -> "FAILURE".equals(s.endKind())).toList();
            List<Double> tR = replacementSegments.stream().map(Segment::observedDays).toList();
            List<Boolean> eR = replacementSegments.stream().map(s -> "FAILURE".equals(s.endKind())).toList();

            SurvivalMath.KmPoint kmO = SurvivalMath.kmAt(tO, eO, followupDays);
            SurvivalMath.KmPoint kmR = SurvivalMath.kmAt(tR, eR, followupDays);
            double[] diffKm = SurvivalMath.kmRiskDiff(kmO, kmR);
            SurvivalMath.LogRankResult logrank = SurvivalMath.logRank(tO, eO, tR, eR);

            // 删失机制检验：把删失当作事件再做一次对数秩，检验删失是否与分组相关
            List<Boolean> flipO = eO.stream().map(b -> !b).toList();
            List<Boolean> flipR = eR.stream().map(b -> !b).toList();
            double censorP = SurvivalMath.logRankP(tO, flipO, tR, flipR);

            long unknownO = segments.stream().filter(s -> s.skeleton().inScope()
                    && "ORIGINAL".equals(s.skeleton().role())
                    && env.equals(s.skeleton().environment())
                    && "UNKNOWN_REMOVAL".equals(s.censorReason())).count();
            long unknownR = segments.stream().filter(s -> s.skeleton().inScope()
                    && "REPLACEMENT".equals(s.skeleton().role())
                    && env.equals(s.skeleton().environment())
                    && "UNKNOWN_REMOVAL".equals(s.censorReason())).count();
            List<String> flags = new ArrayList<>();
            if (nO < 10 || nR < 10 || xO + xR < 3) {
                flags.add("SMALL_SAMPLE_WIDE_INTERVAL");
            }
            if (!Double.isNaN(censorP) && censorP < 0.05) {
                flags.add("CENSORING_DEPENDS_ON_GROUP");
            }
            if (unknownO + unknownR > 0) {
                flags.add("UNKNOWN_REMOVAL_EXCLUDED");
            }
            int completedO = ((Number) original.get("nCompletedWindow")).intValue();
            int completedR = ((Number) replacement.get("nCompletedWindow")).intValue();
            if (completedO < ((Number) original.get("nSegments")).intValue()
                    || completedR < ((Number) replacement.get("nSegments")).intValue()) {
                flags.add("RECENT_INSTALL_NOT_IN_FIXED_WINDOW");
            }

            Map<String, Object> censoring = new LinkedHashMap<>();
            censoring.put("checked", true);
            censoring.put("logRankPOnCensoring", Double.isNaN(censorP) ? null : censorP);
            censoring.put("unknownRemovalOriginal", unknownO);
            censoring.put("unknownRemovalReplacement", unknownR);
            censoring.put("verdict", Double.isNaN(censorP) ? "INSUFFICIENT_DATA"
                    : censorP < 0.05 ? "CENSORING_MAY_BE_INFORMATIVE"
                    : "NO_EVIDENCE_OF_INFORMATIVE_CENSORING");

            row.put("comparable", true);
            row.put("riskDiffFixed", diffFixed[0]);
            row.put("riskDiffFixedLow", diffFixed[1]);
            row.put("riskDiffFixedHigh", diffFixed[2]);
            row.put("riskDiffKm", diffKm[0]);
            row.put("riskDiffKmLow", diffKm[1]);
            row.put("riskDiffKmHigh", diffKm[2]);
            row.put("logrankChi2", logrank.valid() ? logrank.chi2() : null);
            row.put("logrankP", logrank.valid() ? logrank.p() : null);
            row.put("censoringCheck", censoring);
            row.put("flags", flags);
            row.put("note", "风险差 = 替代件风险 − 原件风险；正值表示替代件风险更高");
            result.add(row);
        }
        // 范围外（设备未声明工况 / 非本项目设备）单独成行，明确标注不可比较
        Map<String, Long> outOfScope = segments.stream().filter(s -> !s.skeleton().inScope())
                .collect(Collectors.groupingBy(s -> s.skeleton().environment(), Collectors.counting()));
        for (Map.Entry<String, Long> e : outOfScope.entrySet()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("environment", e.getKey());
            row.put("originalModelCode", null);
            row.put("replacementModelCode", null);
            row.put("originalN", 0);
            row.put("replacementN", 0);
            row.put("comparable", false);
            row.put("notComparableReason", "设备未声明工况/不属于本项目设备，观察段 " + e.getValue()
                    + " 个整体排除，不参与分层比较");
            row.put("note", "范围外观察段仅留痕，不进入任何分母");
            row.put("flags", List.of("NOT_COMPARABLE", "OUT_OF_SCOPE"));
            row.put("censoringCheck", Map.of("checked", false));
            result.add(row);
        }
        return result;
    }

    private void persistStratum(long reportId, Map<String, Object> s) {
        db.update("INSERT INTO reliability_report_stratum(report_id,part_role,part_model_id,model_code," +
                "environment,n_segments,n_fixed_denominator,n_fixed_events,n_completed_window,risk_fixed," +
                "risk_fixed_low,risk_fixed_high,person_days,median_followup_days,n_km_risk,km_events," +
                "km_censored,km_survival,km_risk,km_risk_low,km_risk_high,censor_end_of_cutoff," +
                "censor_transfer,censor_removed_no_fault,censor_unknown,warnings,detail) " +
                "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?::jsonb,?::jsonb)",
                reportId, s.get("partRole"), s.get("partModelId"), s.get("modelCode"),
                s.get("environment"), s.get("nSegments"), s.get("nFixedDenominator"),
                s.get("nFixedEvents"), s.get("nCompletedWindow"), s.get("riskFixed"),
                s.get("riskFixedLow"), s.get("riskFixedHigh"), s.get("personDays"),
                s.get("medianFollowupDays"), s.get("nKmRisk"), s.get("kmEvents"), s.get("kmCensored"),
                s.get("kmSurvival"), s.get("kmRisk"), s.get("kmRiskLow"), s.get("kmRiskHigh"),
                s.get("censorEndOfCutoff"), s.get("censorTransfer"), s.get("censorRemovedNoFault"),
                s.get("censorUnknown"), db.toJson(s.get("warnings")), db.toJson(s.get("detail")));
    }

    private void persistComparison(long reportId, Map<String, Object> c) {
        db.update("INSERT INTO reliability_risk_comparison(report_id,environment,original_model_code," +
                "replacement_model_code,original_n,replacement_n,comparable,not_comparable_reason," +
                "risk_diff_fixed,risk_diff_fixed_low,risk_diff_fixed_high,risk_diff_km,risk_diff_km_low," +
                "risk_diff_km_high,logrank_chi2,logrank_p,censoring_check,flags,note) " +
                "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?::jsonb,?::jsonb,?)",
                reportId, c.get("environment"), c.get("originalModelCode"), c.get("replacementModelCode"),
                c.get("originalN"), c.get("replacementN"), c.get("comparable"),
                c.get("notComparableReason"), c.get("riskDiffFixed"), c.get("riskDiffFixedLow"),
                c.get("riskDiffFixedHigh"), c.get("riskDiffKm"), c.get("riskDiffKmLow"),
                c.get("riskDiffKmHigh"), c.get("logrankChi2"), c.get("logrankP"),
                db.toJson(c.get("censoringCheck")), db.toJson(c.get("flags")), c.get("note"));
    }

    private void persistReportEvents(long reportId, List<Segment> segments, int followupDays) {
        for (Segment s : segments) {
            if (!s.skeleton().inScope()) {
                insertReportEvent(reportId, s, new SegmentEvent("SCOPE_EXCLUDED", s.endedAt(),
                        s.observedDays(), null,
                        "设备未声明工况，属于对比项目范围外，不进入分层与分母", Map.of()));
                continue;
            }
            boolean failure = "FAILURE".equals(s.endKind());
            if (failure) {
                insertReportEvent(reportId, s, new SegmentEvent("FIRST_FAILURE", s.endedAt(),
                        s.observedDays(), null, "首发故障", Map.of()));
            } else if ("UNKNOWN_REMOVAL".equals(s.censorReason())) {
                insertReportEvent(reportId, s, new SegmentEvent("EXCLUDED_UNKNOWN_REMOVAL", s.endedAt(),
                        s.observedDays(), null, "原因不明拆除：不当作故障，也不当作完成观察", Map.of()));
            } else if (s.observedDays() + EPS < followupDays) {
                insertReportEvent(reportId, s, new SegmentEvent("FIXED_WINDOW_INCOMPLETE", s.endedAt(),
                        s.observedDays(), null,
                        "未满随访窗口的删失：不进入固定窗口分母，但进入生存曲线风险集", Map.of()));
            } else {
                insertReportEvent(reportId, s, new SegmentEvent("CENSORED", s.endedAt(),
                        s.observedDays(), null, "删失：" + s.censorReason(), Map.of()));
            }
            for (SegmentEvent ev : s.events()) {
                if ("FIRST_FAILURE".equals(ev.type())) {
                    continue;
                }
                insertReportEvent(reportId, s, ev);
            }
        }
    }

    private void insertReportEvent(long reportId, Segment s, SegmentEvent ev) {
        Map<String, Object> model = db.queryOne(
                "SELECT entity_code FROM master_entity WHERE id=?", s.skeleton().modelId());
        Map<String, Object> physical = db.queryOne(
                "SELECT entity_code FROM master_entity WHERE id=?", s.skeleton().physicalId());
        db.update("INSERT INTO reliability_report_event(report_id,segment_code,part_role,model_code," +
                "environment,physical_code,event_type,event_time,time_days,ticket_no,reason,detail) " +
                "VALUES (?,?,?,?,?,?,?,?,?,?,?,?::jsonb)",
                reportId, s.code(), s.skeleton().role(),
                model == null ? "" : model.get("entity_code"),
                s.skeleton().environment(), physical == null ? "" : physical.get("entity_code"),
                ev.type(), ev.time(), ev.timeDays(), ev.ticketNo(), ev.reason(),
                db.toJson(ev.detail()));
    }

    // ------------------------------------------------------------------
    // 查询与追加
    // ------------------------------------------------------------------

    public List<Map<String, Object>> listPrograms() {
        return db.query("SELECT p.*, o.entity_code AS original_model_code, " +
                "r.entity_code AS replacement_model_code " +
                "FROM reliability_program p " +
                "JOIN master_entity o ON o.id=p.original_model_id " +
                "JOIN master_entity r ON r.id=p.replacement_model_id ORDER BY p.id");
    }

    public List<Map<String, Object>> listReports(Long programId) {
        if (programId == null) {
            return db.query("SELECT r.*, p.program_code, p.program_name FROM reliability_report r " +
                    "JOIN reliability_program p ON p.id=r.program_id " +
                    "ORDER BY r.program_id, r.version DESC");
        }
        return db.query("SELECT r.*, p.program_code, p.program_name FROM reliability_report r " +
                "JOIN reliability_program p ON p.id=r.program_id WHERE r.program_id=? " +
                "ORDER BY r.version DESC", programId);
    }

    public Map<String, Object> reportDetail(long reportId) {
        Map<String, Object> report = db.queryOne(
                "SELECT r.*, p.program_code, p.program_name FROM reliability_report r " +
                "JOIN reliability_program p ON p.id=r.program_id WHERE r.id=?", reportId);
        if (report == null) {
            throw new IllegalArgumentException("可靠性报告不存在: " + reportId);
        }
        List<Map<String, Object>> strata = db.query(
                "SELECT * FROM reliability_report_stratum WHERE report_id=? " +
                "ORDER BY environment, part_role", reportId);
        List<Map<String, Object>> comparisons = db.query(
                "SELECT * FROM reliability_risk_comparison WHERE report_id=? ORDER BY environment", reportId);
        List<Map<String, Object>> events = db.query(
                "SELECT * FROM reliability_report_event WHERE report_id=? " +
                "ORDER BY environment, model_code, event_time", reportId);
        List<Map<String, Object>> segments = db.query(
                "SELECT s.*, m.entity_code AS model_code, pp.entity_code AS physical_code, " +
                "eq.entity_code AS equipment_code FROM reliability_segment s " +
                "JOIN master_entity m ON m.id=s.part_model_id " +
                "JOIN master_entity pp ON pp.id=s.physical_part_id " +
                "JOIN master_entity eq ON eq.id=s.equipment_id " +
                "WHERE s.program_id=? AND s.cutoff_at=? AND " +
                "((s.as_of_received IS NULL AND CAST(? AS timestamptz) IS NULL) OR s.as_of_received=?) " +
                "ORDER BY s.environment, s.part_role, s.installed_at",
                report.get("program_id"), report.get("cutoff_at"),
                report.get("as_of_received"), report.get("as_of_received"));
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("report", report);
        detail.put("strata", strata);
        detail.put("comparisons", comparisons);
        detail.put("events", events);
        detail.put("segments", segments);
        return detail;
    }

    public List<Map<String, Object>> listSegments(Long programId) {
        String base = "SELECT s.*, m.entity_code AS model_code, pp.entity_code AS physical_code, " +
                "eq.entity_code AS equipment_code FROM reliability_segment s " +
                "JOIN master_entity m ON m.id=s.part_model_id " +
                "JOIN master_entity pp ON pp.id=s.physical_part_id " +
                "JOIN master_entity eq ON eq.id=s.equipment_id ";
        if (programId == null) {
            return db.query(base + "ORDER BY s.installed_at");
        }
        return db.query(base + "WHERE s.program_id=? ORDER BY s.installed_at", programId);
    }

    public List<Map<String, Object>> listTickets(Long programId) {
        String base = "SELECT t.*, m.entity_code AS model_code, pp.entity_code AS physical_code " +
                "FROM failure_ticket t JOIN master_entity m ON m.id=t.part_model_id " +
                "JOIN master_entity pp ON pp.id=t.physical_part_id ";
        if (programId == null) {
            return db.query(base + "ORDER BY t.occurred_at, t.id");
        }
        return db.query(base + "WHERE t.program_id=? ORDER BY t.occurred_at, t.id", programId);
    }

    /**
     * 追加故障工单（只追加）。接收时间晚于故障时间 24h 记为迟到，
     * 旧报告不会自动改写，需显式生成新版本。
     */
    @Transactional
    public Map<String, Object> appendTicket(Map<String, Object> body) {
        String ticketNo = String.valueOf(body.get("ticketNo"));
        if (db.queryOne("SELECT id FROM failure_ticket WHERE ticket_no=?", ticketNo) != null) {
            throw new IllegalStateException("工单号已存在，故障工单只能追加: " + ticketNo);
        }
        OffsetDateTime occurred = OffsetDateTime.parse(String.valueOf(body.get("occurredAt")));
        OffsetDateTime received = body.get("receivedAt") == null
                ? OffsetDateTime.now() : OffsetDateTime.parse(String.valueOf(body.get("receivedAt")));
        long id = db.insertReturningId(
                "INSERT INTO failure_ticket(ticket_no,program_id,physical_part_id,part_model_id," +
                "equipment_id,occurred_at,received_at,fault_code,fault_desc,cause_category,source_ref) " +
                "VALUES (?,?,?,?,?,?,?,?,?,?,?) RETURNING id",
                ticketNo, body.get("programId"), body.get("physicalPartId"), body.get("partModelId"),
                body.get("equipmentId"), occurred, received, body.get("faultCode"),
                body.get("faultDesc"), body.get("causeCategory"),
                body.getOrDefault("sourceRef", ticketNo));
        long gapHours = Duration.between(occurred, received).toHours();
        if (gapHours > 24) {
            qualityEvents.record("LATE_VOUCHER", "MEDIUM", "FAILURE_TICKET", id, null, null,
                    "故障工单 " + ticketNo + " 迟到 " + gapHours + " 小时（故障时间 " + occurred
                            + "，接收时间 " + received + "）。已签发可靠性报告保留原截止点，需生成新版本",
                    Map.of("ticketNo", ticketNo, "gapHours", gapHours, "kind", "FAILURE_TICKET"));
        }
        return db.queryOne("SELECT * FROM failure_ticket WHERE id=?", id);
    }

    /** 以同一截止点、最新接收边界重新生成一版报告（旧版本保留，形成 supersedes 链）。 */
    @Transactional
    public Map<String, Object> refreshReport(long reportId) {
        Map<String, Object> report = db.queryOne("SELECT * FROM reliability_report WHERE id=?", reportId);
        if (report == null) {
            throw new IllegalArgumentException("可靠性报告不存在: " + reportId);
        }
        return generateReport(new GenerateRequest(
                ((Number) report.get("program_id")).longValue(),
                db.toOffsetDateTime(report.get("cutoff_at")), OffsetDateTime.now(),
                CurrentUser.username()));
    }
}
