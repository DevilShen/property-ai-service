package com.property.ai.domain.complaint;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import com.property.ai.bff.dto.Complaint;
import com.property.ai.bff.dto.ComplaintAuditEntry;
import com.property.ai.bff.dto.ComplaintLevel;
import com.property.ai.bff.dto.ComplaintStatus;

/**
 * 投诉领域实体（P3-021，平台侧内存记录；<b>不写 ERP</b>）。
 *
 * <p>边界（契约 v1.1.0 {@code Complaint} + 架构数据范围口径）：
 * <ul>
 *   <li>{@code ownerActorId}/{@code roomCode} 是数据范围归属（owner 只能看/重开本户投诉）；</li>
 *   <li>{@code contactTail4}/{@code attachments}/{@code resolution} 是平台侧内部字段，
 *       <b>不回传</b>（契约不回手机号；附件 P3 只计数；结案说明落 {@code audit[].note}）；</li>
 *   <li>{@code audit[]} 按时间升序留痕（submitted / accept / first_response / escalate / close / reopen）；</li>
 *   <li>{@code version} 是 transition 乐观锁（每次成功迁移 +1）。</li>
 * </ul>
 *
 * <p>P3 存储：进程内 {@code ConcurrentHashMap}（见 {@link ComplaintService}）；重启会丢投诉单，
 * P5 按契约迁 PostgreSQL（表名 {@code complaint_record}，version 列乐观锁）。禁止把内存实现当生产存储。
 */
public class ComplaintRecord {

    public String complaintId = "";
    /**
     * 项目归属（P5 W19 多项目隔离 / v1.3.0）：提交时按 {@code Actor.projectId} 落归属，
     * 服务层据它做项目级列表过滤（跨项目 → 403 {@code E-2002} {@code cross_project}）；
     * 契约 DTO 不回传（{@code Complaint} 无 projectId 字段，不改契约）。
     */
    public String projectId = "";
    public String roomCode = "";
    /** 业主主体 ID（数据范围归属；staff 代提时为空 = 仅按房号归属） */
    public String ownerActorId = "";
    public String ownerNameMasked = "";
    public String category = "";
    public ComplaintLevel level = ComplaintLevel.L3;
    public ComplaintStatus status = ComplaintStatus.SUBMITTED;
    /** 已脱敏的投诉内容（不落完整手机号/证件号） */
    public String content = "";
    /** 回访用手机号后四位（仅内部，不回传） */
    public String contactTail4;
    /** 附件计数（P3 只计数，不传文件） */
    public int attachments;

    public String firstResponseAt;
    public String slaDueAt;
    public String escalatedTo;
    public String ticketNo;
    /** 结案说明（结案 note，内部留痕；契约通过 audit[].note 暴露） */
    public String resolution;

    public String createdAt = "";
    public String updatedAt = "";
    public int version = 1;

    /** 动作留痕（按时间升序；写入由领域服务串行加锁） */
    public List<ComplaintAuditEntry> audit = new CopyOnWriteArrayList<>();

    /** 追加一条动作留痕（{@code note} 为 {@code null}/空时不落该字段）。 */
    public void appendAudit(String at, String action, String who, String note) {
        audit.add(new ComplaintAuditEntry(at, action, who, note == null || note.isBlank() ? null : note.trim()));
    }

    /** 是否已被首响（{@code firstResponseAt} 只记第一次）。 */
    public boolean responded() {
        return firstResponseAt != null && !firstResponseAt.isBlank();
    }

    /** 契约 {@code Complaint} DTO（可选字段为 null 时不序列化）。 */
    public Complaint toDto() {
        List<ComplaintAuditEntry> auditCopy = new ArrayList<>(audit);
        return new Complaint(
                complaintId,
                roomCode,
                ownerNameMasked,
                category,
                level,
                status,
                content,
                firstResponseAt,
                slaDueAt,
                escalatedTo,
                ticketNo,
                createdAt,
                updatedAt,
                version,
                List.copyOf(auditCopy));
    }
}
