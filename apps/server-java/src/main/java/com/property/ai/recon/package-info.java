/**
 * 只读对账（P2-013 / P2-014）：{@code POST /api/v1/recon/run} + {@code GET /api/v1/recon/report/{runId}}。
 *
 * <h2>对账口径（P2 门禁：单 ERP 只读对账通过，差异率 ≤ 2%）</h2>
 *
 * <p><b>事实源</b>：ERP 实时只读（经连接器出站客户端 {@code HttpErpClient} + {@code ErpConnector}，
 * 不做任何重算/补齐）。P2 第 1 周的 ERP 是 {@code services/mock-erp} 测试替身（{@code mock:true}），
 * 报告/接口一律标注 mock，不得说成生产 ERP。
 *
 * <p><b>对比对象</b>：平台侧「展示快照 / 规范映射结果」——即 BFF 读路径真正返回给前端的契约 DTO
 * （{@code BillInfo} / {@code RoomInfo} / {@code TicketListItem}，由 {@code NcErpConnector + ErpFieldMapper}
 * 从 ERP 私有载荷映射而来）。对账即逐字段核对「ERP 原值 → 平台展示值」是否逐分/逐字一致，
 * 并校验 ERP 侧金额恒等式与口径（物业费=面积×2.5、公摊电费=round(面积×0.38)、车位费 120、Σitems=total、
 * total-prevTotal=diff）。
 *
 * <p><b>范围</b>（{@code ReconScope}）：
 * <ul>
 *   <li>{@code bill}（默认 200 条）：账期 {@code period}（默认当月，可 {@code property.recon.period} 覆盖）
 *       全量账单样本；逐条校验金额恒等式、缺失字段、金额逐分比对、费项明细、知识引用（cite）；</li>
 *   <li>{@code room}：以账单样本的房号为房档样本，校验房号/面积/车位/绑定状态 + 面积费用口径；</li>
 *   <li>{@code ticket}：ERP 工单列表样本，校验状态映射/房号/班组/分类/等级等字段。</li>
 * </ul>
 *
 * <p><b>差异统计</b>：{@code checked} = 实际对账对象数；{@code matched} = 无差异对象数；
 * {@code diffCount} = 有差异对象数；{@code diffRate = diffCount / checked}（0 条时记 0）；
 * {@code passed = status==completed && diffRate <= threshold}（阈值默认 {@code 0.02}）。
 * 差异明细 {@code items} 为字段级差异（{@code ReconDiffItem}），脱敏口径：房号保留，业主名一律掩码（ERP 侧已掩码）。
 *
 * <p><b>降级与断链（P2-014）</b>：本包不重写连接器；对账失败（ERP 超时/熔断/降级）时 run 置
 * {@code status=failed}、{@code passed=false}，并在 {@code items} 追加一条 {@code missing/erp_read}
 * 说明失败原因——**不返回假数据、不把失败当通过**。
 *
 * <p>契约：{@code docs/openapi/openapi.yaml} v0.3.0（异步 202 受理 + 轮询 report），本包不新增/不改字段。
 */
package com.property.ai.recon;
