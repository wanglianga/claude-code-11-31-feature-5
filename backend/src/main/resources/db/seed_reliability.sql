-- 替代件可靠性对比场景（基础 seed.sql 之外单独成文：全新建库与老库升级都执行这一份）
-- 固定主键，仅在 reliability_program 为空时执行（由 DemoDataInitializer 判定）

-- =====================================================================
-- 可靠性对比场景：冷却泵原件 PM-PUMP-200 vs 替代件 PM-PUMP-210
--     覆盖：集中安装 / 观察不足 / 转装 / 无故障返库 / 故障后仍报修（重复）/ 原因不明拆除 / 迟到工单
--     观察段与报告由后端 ReliabilityService 按截止点和接收边界派生
--     建议截止点 2025-09-30；旧版接收边界 2025-08-01 用于演示迟到工单另起版本
-- =====================================================================

-- 替代件型号（原件 38 已在基础主数据中登记）
INSERT INTO master_entity (id, entity_type, entity_code, display_name, supplier_code, spec_payload, status) VALUES
 (39,'PART_MODEL','PM-PUMP-210','冷却泵 210（替代件）','SUP-DELTA',
  '{"voltage":"380V","flow":"40L/min","dimension":"120mm"}','VERIFIED');

-- 工况体现在设备规格中：HIGH_TEMP / STANDARD / HUMID
INSERT INTO master_entity (id, entity_type, entity_code, display_name, spec_payload, status) VALUES
 (110,'EQUIPMENT','EQ-HT-01','高温工况加工中心 HT-01','{"model":"CNC-X5","environment":"HIGH_TEMP"}','VERIFIED'),
 (111,'EQUIPMENT','EQ-HT-02','高温工况加工中心 HT-02','{"model":"CNC-X5","environment":"HIGH_TEMP"}','VERIFIED'),
 (112,'EQUIPMENT','EQ-HT-03','高温工况加工中心 HT-03','{"model":"CNC-X5","environment":"HIGH_TEMP"}','VERIFIED'),
 (113,'EQUIPMENT','EQ-HT-04','高温工况加工中心 HT-04','{"model":"CNC-X5","environment":"HIGH_TEMP"}','VERIFIED'),
 (114,'EQUIPMENT','EQ-HT-05','高温工况加工中心 HT-05','{"model":"CNC-X5","environment":"HIGH_TEMP"}','VERIFIED'),
 (115,'EQUIPMENT','EQ-HT-06','高温工况加工中心 HT-06','{"model":"CNC-X5","environment":"HIGH_TEMP"}','VERIFIED'),
 (116,'EQUIPMENT','EQ-HT-07','高温工况加工中心 HT-07','{"model":"CNC-X5","environment":"HIGH_TEMP"}','VERIFIED'),
 (117,'EQUIPMENT','EQ-HT-08','高温工况加工中心 HT-08','{"model":"CNC-X5","environment":"HIGH_TEMP"}','VERIFIED'),
 (120,'EQUIPMENT','EQ-ST-01','标准工况加工中心 ST-01','{"model":"CNC-X5","environment":"STANDARD"}','VERIFIED'),
 (121,'EQUIPMENT','EQ-ST-02','标准工况加工中心 ST-02','{"model":"CNC-X5","environment":"STANDARD"}','VERIFIED'),
 (122,'EQUIPMENT','EQ-ST-03','标准工况加工中心 ST-03','{"model":"CNC-X5","environment":"STANDARD"}','VERIFIED'),
 (123,'EQUIPMENT','EQ-ST-04','标准工况加工中心 ST-04','{"model":"CNC-X5","environment":"STANDARD"}','VERIFIED'),
 (124,'EQUIPMENT','EQ-ST-05','标准工况加工中心 ST-05','{"model":"CNC-X5","environment":"STANDARD"}','VERIFIED'),
 (130,'EQUIPMENT','EQ-HM-01','潮湿工况加工中心 HM-01','{"model":"CNC-X5","environment":"HUMID"}','VERIFIED'),
 (131,'EQUIPMENT','EQ-HM-02','潮湿工况加工中心 HM-02','{"model":"CNC-X5","environment":"HUMID"}','VERIFIED');

INSERT INTO master_entity (id, entity_type, entity_code, display_name, spec_payload, status) VALUES
 (140,'MOUNT_POSITION','POS-PUMP-A','冷却泵安装位 A','{}','VERIFIED'),
 (141,'MOUNT_POSITION','POS-PUMP-B','冷却泵安装位 B','{}','VERIFIED');

-- 实物序列号：原件 PP-PUMP-2xxx / 替代件 PP-PUMP-3xxx（同一实物同一时间只在一台设备）
INSERT INTO master_entity (id, entity_type, entity_code, display_name, supplier_code, spec_payload, status) VALUES
 (200,'PHYSICAL_PART','PP-PUMP-2001','冷却泵实物 2001','SUP-DELTA','{"modelCode":"PM-PUMP-200"}','VERIFIED'),
 (201,'PHYSICAL_PART','PP-PUMP-2002','冷却泵实物 2002','SUP-DELTA','{"modelCode":"PM-PUMP-200"}','VERIFIED'),
 (202,'PHYSICAL_PART','PP-PUMP-2003','冷却泵实物 2003','SUP-DELTA','{"modelCode":"PM-PUMP-200"}','VERIFIED'),
 (203,'PHYSICAL_PART','PP-PUMP-2004','冷却泵实物 2004','SUP-DELTA','{"modelCode":"PM-PUMP-200"}','VERIFIED'),
 (204,'PHYSICAL_PART','PP-PUMP-2005','冷却泵实物 2005','SUP-DELTA','{"modelCode":"PM-PUMP-200"}','VERIFIED'),
 (205,'PHYSICAL_PART','PP-PUMP-2006','冷却泵实物 2006','SUP-DELTA','{"modelCode":"PM-PUMP-200"}','VERIFIED'),
 (300,'PHYSICAL_PART','PP-PUMP-3001','冷却泵实物 3001','SUP-DELTA','{"modelCode":"PM-PUMP-210"}','VERIFIED'),
 (301,'PHYSICAL_PART','PP-PUMP-3002','冷却泵实物 3002','SUP-DELTA','{"modelCode":"PM-PUMP-210"}','VERIFIED'),
 (302,'PHYSICAL_PART','PP-PUMP-3003','冷却泵实物 3003','SUP-DELTA','{"modelCode":"PM-PUMP-210"}','VERIFIED'),
 (303,'PHYSICAL_PART','PP-PUMP-3004','冷却泵实物 3004','SUP-DELTA','{"modelCode":"PM-PUMP-210"}','VERIFIED'),
 (304,'PHYSICAL_PART','PP-PUMP-3005','冷却泵实物 3005','SUP-DELTA','{"modelCode":"PM-PUMP-210"}','VERIFIED'),
 (305,'PHYSICAL_PART','PP-PUMP-3006','冷却泵实物 3006','SUP-DELTA','{"modelCode":"PM-PUMP-210"}','VERIFIED'),
 (314,'PHYSICAL_PART','PP-PUMP-3015','冷却泵实物 3015','SUP-DELTA','{"modelCode":"PM-PUMP-210"}','VERIFIED'),
 (311,'PHYSICAL_PART','PP-PUMP-3011','冷却泵实物 3011','SUP-DELTA','{"modelCode":"PM-PUMP-210"}','VERIFIED'),
 (206,'PHYSICAL_PART','PP-PUMP-2007','冷却泵实物 2007','SUP-DELTA','{"modelCode":"PM-PUMP-200"}','VERIFIED'),
 (207,'PHYSICAL_PART','PP-PUMP-2008','冷却泵实物 2008','SUP-DELTA','{"modelCode":"PM-PUMP-200"}','VERIFIED'),
 (208,'PHYSICAL_PART','PP-PUMP-2009','冷却泵实物 2009','SUP-DELTA','{"modelCode":"PM-PUMP-200"}','VERIFIED'),
 (209,'PHYSICAL_PART','PP-PUMP-2010','冷却泵实物 2010','SUP-DELTA','{"modelCode":"PM-PUMP-200"}','VERIFIED'),
 (210,'PHYSICAL_PART','PP-PUMP-2011','冷却泵实物 2011','SUP-DELTA','{"modelCode":"PM-PUMP-200"}','VERIFIED'),
 (306,'PHYSICAL_PART','PP-PUMP-3007','冷却泵实物 3007','SUP-DELTA','{"modelCode":"PM-PUMP-210"}','VERIFIED'),
 (307,'PHYSICAL_PART','PP-PUMP-3008','冷却泵实物 3008','SUP-DELTA','{"modelCode":"PM-PUMP-210"}','VERIFIED'),
 (308,'PHYSICAL_PART','PP-PUMP-3009','冷却泵实物 3009','SUP-DELTA','{"modelCode":"PM-PUMP-210"}','VERIFIED'),
 (309,'PHYSICAL_PART','PP-PUMP-3010','冷却泵实物 3010','SUP-DELTA','{"modelCode":"PM-PUMP-210"}','VERIFIED'),
 (310,'PHYSICAL_PART','PP-PUMP-3012','冷却泵实物 3012','SUP-DELTA','{"modelCode":"PM-PUMP-210"}','VERIFIED'),
 (312,'PHYSICAL_PART','PP-PUMP-3013','冷却泵实物 3013','SUP-DELTA','{"modelCode":"PM-PUMP-210"}','VERIFIED'),
 (313,'PHYSICAL_PART','PP-PUMP-3014','冷却泵实物 3014','SUP-DELTA','{"modelCode":"PM-PUMP-210"}','VERIFIED');
SELECT setval('master_entity_id_seq', 1000);

-- 对比项目：同一故障定义（LEAK / SEAL_LEAK / NO_FLOW），随访窗口 180 天
INSERT INTO reliability_program (id, program_code, program_name, original_model_id, replacement_model_id,
    failure_criteria, followup_days, approved_at, status) VALUES
 (1,'RELPROG-COOLPUMP','冷却泵替代件现场可靠性对比',38,39,
  '{"codes":["LEAK","SEAL_LEAK","NO_FLOW"],"definition":"密封渗漏、失压或无流量三类故障；噪声 NOISE 不计入"}',
  180,'2025-01-01T00:00:00Z','VERIFIED');
SELECT setval('reliability_program_id_seq', 10);

-- 追加式拆装凭证（每个实物同一时间只在一台设备上；3004 为无故障拆除后转装）
INSERT INTO install_event (event_code, equipment_id, mount_position_id, physical_part_id, part_model_id,
    event_type, event_time, received_at, source_contract_id, source_ref, payload_digest, trust_score, remove_reason) VALUES
 ('EV-REL-INST-R001',110,140,200,38,'INSTALL','2024-06-01T08:00:00Z','2024-06-01T08:00:00Z',3,'VCH-REL-2001-IN','sha256:ev-rel-inst-r001',72,NULL),
 ('EV-REL-INST-R002',111,140,201,38,'INSTALL','2024-06-01T08:00:00Z','2024-06-01T08:00:00Z',3,'VCH-REL-2002-IN','sha256:ev-rel-inst-r002',72,NULL),
 ('EV-REL-INST-R003',112,140,202,38,'INSTALL','2024-06-15T08:00:00Z','2024-06-15T08:00:00Z',3,'VCH-REL-2003-IN','sha256:ev-rel-inst-r003',72,NULL),
 ('EV-REL-INST-R004',113,140,203,38,'INSTALL','2024-07-01T08:00:00Z','2024-07-01T08:00:00Z',3,'VCH-REL-2004-IN','sha256:ev-rel-inst-r004',72,NULL),
 ('EV-REL-INST-R005',114,140,204,38,'INSTALL','2024-08-01T08:00:00Z','2024-08-01T08:00:00Z',3,'VCH-REL-2005-IN','sha256:ev-rel-inst-r005',72,NULL),
 ('EV-REL-INST-R006',115,140,205,38,'INSTALL','2024-09-01T08:00:00Z','2024-09-01T08:00:00Z',3,'VCH-REL-2006-IN','sha256:ev-rel-inst-r006',72,NULL),
 ('EV-REL-INST-R007',110,141,300,39,'INSTALL','2025-01-10T08:00:00Z','2025-01-10T08:00:00Z',3,'VCH-REL-3001-IN','sha256:ev-rel-inst-r007',72,NULL),
 ('EV-REL-INST-R008',111,141,301,39,'INSTALL','2025-01-10T08:00:00Z','2025-01-10T08:00:00Z',3,'VCH-REL-3002-IN','sha256:ev-rel-inst-r008',72,NULL),
 ('EV-REL-INST-R009',112,141,302,39,'INSTALL','2025-01-20T08:00:00Z','2025-01-20T08:00:00Z',3,'VCH-REL-3003-IN','sha256:ev-rel-inst-r009',72,NULL),
 ('EV-REL-INST-R010',113,141,303,39,'INSTALL','2025-01-20T08:00:00Z','2025-01-20T08:00:00Z',3,'VCH-REL-3004-IN','sha256:ev-rel-inst-r010',72,NULL),
 ('EV-REL-REM-R011',113,141,303,39,'REMOVE','2025-03-01T08:00:00Z','2025-03-01T08:00:00Z',3,'VCH-REL-3004-OUT','sha256:ev-rel-rem-r011',72,'NO_FAULT'),
 ('EV-REL-INST-R012',115,141,303,39,'INSTALL','2025-03-02T08:00:00Z','2025-03-02T08:00:00Z',3,'VCH-REL-3004-IN','sha256:ev-rel-inst-r012',72,NULL),
 ('EV-REL-INST-R013',114,141,304,39,'INSTALL','2025-01-20T08:00:00Z','2025-01-20T08:00:00Z',3,'VCH-REL-3005-IN','sha256:ev-rel-inst-r013',72,NULL),
 ('EV-REL-INST-R014',116,141,305,39,'INSTALL','2025-01-20T08:00:00Z','2025-01-20T08:00:00Z',3,'VCH-REL-3006-IN','sha256:ev-rel-inst-r014',72,NULL),
 ('EV-REL-INST-R015',117,141,314,39,'INSTALL','2025-01-20T08:00:00Z','2025-01-20T08:00:00Z',3,'VCH-REL-3015-IN','sha256:ev-rel-inst-r015',72,NULL),
 ('EV-REL-REM-R016',117,141,314,39,'REMOVE','2025-05-01T08:00:00Z','2025-05-01T08:00:00Z',3,'VCH-REL-3015-OUT','sha256:ev-rel-rem-r016',72,'NO_FAULT_SPARE_RETURN'),
 ('EV-REL-INST-R017',116,141,311,39,'INSTALL','2025-06-15T08:00:00Z','2025-06-15T08:00:00Z',3,'VCH-REL-3011-IN','sha256:ev-rel-inst-r017',72,NULL),
 ('EV-REL-INST-R018',120,140,206,38,'INSTALL','2024-07-01T08:00:00Z','2024-07-01T08:00:00Z',3,'VCH-REL-2007-IN','sha256:ev-rel-inst-r018',72,NULL),
 ('EV-REL-INST-R019',121,140,207,38,'INSTALL','2024-07-01T08:00:00Z','2024-07-01T08:00:00Z',3,'VCH-REL-2008-IN','sha256:ev-rel-inst-r019',72,NULL),
 ('EV-REL-INST-R020',122,140,208,38,'INSTALL','2024-08-01T08:00:00Z','2024-08-01T08:00:00Z',3,'VCH-REL-2009-IN','sha256:ev-rel-inst-r020',72,NULL),
 ('EV-REL-INST-R021',123,140,209,38,'INSTALL','2024-08-15T08:00:00Z','2024-08-15T08:00:00Z',3,'VCH-REL-2010-IN','sha256:ev-rel-inst-r021',72,NULL),
 ('EV-REL-INST-R022',124,140,210,38,'INSTALL','2024-09-01T08:00:00Z','2024-09-01T08:00:00Z',3,'VCH-REL-2011-IN','sha256:ev-rel-inst-r022',72,NULL),
 ('EV-REL-INST-R023',120,141,306,39,'INSTALL','2025-01-15T08:00:00Z','2025-01-15T08:00:00Z',3,'VCH-REL-3007-IN','sha256:ev-rel-inst-r023',72,NULL),
 ('EV-REL-INST-R024',121,141,307,39,'INSTALL','2025-01-15T08:00:00Z','2025-01-15T08:00:00Z',3,'VCH-REL-3008-IN','sha256:ev-rel-inst-r024',72,NULL),
 ('EV-REL-INST-R025',122,141,308,39,'INSTALL','2025-01-15T08:00:00Z','2025-01-15T08:00:00Z',3,'VCH-REL-3009-IN','sha256:ev-rel-inst-r025',72,NULL),
 ('EV-REL-REM-R026',122,141,308,39,'REMOVE','2025-03-16T08:00:00Z','2025-03-16T08:00:00Z',3,'VCH-REL-3009-OUT','sha256:ev-rel-rem-r026',72,'UNKNOWN'),
 ('EV-REL-INST-R027',123,141,309,39,'INSTALL','2025-02-01T08:00:00Z','2025-02-01T08:00:00Z',3,'VCH-REL-3010-IN','sha256:ev-rel-inst-r027',72,NULL),
 ('EV-REL-INST-R028',124,141,310,39,'INSTALL','2025-02-01T08:00:00Z','2025-02-01T08:00:00Z',3,'VCH-REL-3012-IN','sha256:ev-rel-inst-r028',72,NULL),
 ('EV-REL-INST-R029',130,141,312,39,'INSTALL','2025-01-05T08:00:00Z','2025-01-05T08:00:00Z',3,'VCH-REL-3013-IN','sha256:ev-rel-inst-r029',72,NULL),
 ('EV-REL-INST-R030',131,141,313,39,'INSTALL','2025-01-05T08:00:00Z','2025-01-05T08:00:00Z',3,'VCH-REL-3014-IN','sha256:ev-rel-inst-r030',72,NULL);
SELECT setval('install_event_id_seq', 100);

-- 故障工单（只追加）：3002 重复报修；3005 工单迟到接收（2025-09-05）；3012 为定义外噪声
INSERT INTO failure_ticket (ticket_no, program_id, physical_part_id, part_model_id, equipment_id,
    occurred_at, received_at, fault_code, fault_desc, cause_category, source_ref) VALUES
 ('FT-REL-001',1,202,38,112,'2024-07-30T08:00:00Z','2024-07-31T08:00:00Z','SEAL_LEAK','轴封渗漏','SEAL','QMS/rel/001'),
 ('FT-REL-002',1,203,38,113,'2024-09-19T08:00:00Z','2024-09-20T08:00:00Z','LEAK','冷却液渗漏','SEAL','QMS/rel/002'),
 ('FT-REL-003',1,205,38,115,'2024-12-30T08:00:00Z','2024-12-31T08:00:00Z','NO_FLOW','冷却流量不足','HYDRAULIC','QMS/rel/003'),
 ('FT-REL-004',1,301,39,111,'2025-03-11T08:00:00Z','2025-03-12T08:00:00Z','SEAL_LEAK','轴封渗漏','SEAL','QMS/rel/004'),
 ('FT-REL-005',1,301,39,111,'2025-03-26T08:00:00Z','2025-03-27T08:00:00Z','SEAL_LEAK','轴封渗漏','SEAL','QMS/rel/005'),
 ('FT-REL-006',1,302,39,112,'2025-04-15T08:00:00Z','2025-04-16T08:00:00Z','LEAK','冷却液渗漏','SEAL','QMS/rel/006'),
 ('FT-REL-007',1,304,39,114,'2025-06-20T08:00:00Z','2025-09-05T08:00:00Z','LEAK','冷却液渗漏','SEAL','QMS/rel/007'),
 ('FT-REL-008',1,207,38,121,'2024-10-09T08:00:00Z','2024-10-10T08:00:00Z','SEAL_LEAK','轴封渗漏','SEAL','QMS/rel/008'),
 ('FT-REL-009',1,209,38,123,'2025-01-12T08:00:00Z','2025-01-13T08:00:00Z','LEAK','冷却液渗漏','SEAL','QMS/rel/009'),
 ('FT-REL-010',1,306,39,120,'2025-03-26T08:00:00Z','2025-03-27T08:00:00Z','LEAK','冷却液渗漏','SEAL','QMS/rel/010'),
 ('FT-REL-011',1,310,39,124,'2025-03-23T08:00:00Z','2025-03-24T08:00:00Z','NOISE','运行噪声异常（不在故障定义内）','OTHER','QMS/rel/011'),
 ('FT-REL-012',1,312,39,130,'2025-02-14T08:00:00Z','2025-02-15T08:00:00Z','NO_FLOW','冷却流量不足','HYDRAULIC','QMS/rel/012');
SELECT setval('failure_ticket_id_seq', 100);
