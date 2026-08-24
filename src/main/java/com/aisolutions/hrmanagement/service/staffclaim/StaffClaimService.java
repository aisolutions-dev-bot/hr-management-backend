package com.aisolutions.hrmanagement.service.staffclaim;

import com.aisolutions.hrmanagement.dto.AttachmentDTO;
import com.aisolutions.hrmanagement.dto.StaffClaimDTO;
import com.aisolutions.hrmanagement.dto.StaffClaimDetailDTO;
import com.aisolutions.hrmanagement.entity.StaffClaim;
import com.aisolutions.hrmanagement.entity.StaffClaimDetail;
import com.aisolutions.hrmanagement.repository.NotificationRepository;
import com.aisolutions.hrmanagement.repository.StaffClaimDetailRepository;
import com.aisolutions.hrmanagement.repository.StaffClaimRepository;
import com.aisolutions.hrmanagement.repository.StaffRepository;
import com.aisolutions.hrmanagement.service.CurrentUserService;
import com.aisolutions.hrmanagement.service.SystemParameterService;
import com.aisolutions.hrmanagement.service.attachment.AttachmentService;
import com.aisolutions.hrmanagement.service.useractionlog.UserActionLogService;
import com.aisolutions.hrmanagement.service.useractionlog.UserActionLogService.DeviceInfo;
import com.aisolutions.hrmanagement.util.StringNormalizer;
import com.aisolutions.shared.tenancy.CompanyPoolManager;

import io.smallrye.mutiny.Uni;
import io.vertx.mutiny.sqlclient.SqlClient;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.NotFoundException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import com.aisolutions.shared.util.DateUtil;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Orchestrates the claim HEADER ({@link StaffClaim}) and its line items
 * ({@link StaffClaimDetail}).
 *
 * Flow (draft supported):
 *   createDraft  → addLine* / removeLine* (header stays DRAFT) → submit
 *
 * The header ClaimAmount is always recomputed = sum of its line ClaimAmounts,
 * never user-entered.
 */
@ApplicationScoped
public class StaffClaimService {

    // Header status values
    public static final String STATUS_DRAFT      = "DRAFT";
    public static final String STATUS_SUBMITTED  = "SUBMITTED";
    public static final String STATUS_APPROVED   = "APPROVED";
    public static final String STATUS_PARTIAL    = "PARTIALLY-APPROVED";
    public static final String STATUS_REJECTED   = "REJECTED";
    public static final String STATUS_PAID       = "PAID";
    public static final String STATUS_VOIDED     = "VOIDED";

    // Line (receipt) status values — mirror hr-administration ClaimApprovalService.
    // VOID: the staff accepted a rejection, so the receipt is struck off the claim
    // and excluded from the total.
    public static final String LINE_PENDING  = "PENDING";
    public static final String LINE_APPROVED = "APPROVED";
    public static final String LINE_REJECTED = "REJECTED";
    public static final String LINE_VOID     = "VOID";

    private static final int LEN_DESCRIPTION = 100;

    private static final int LEN_STAFF_ID = 25;
    private static final int LEN_PERIOD   = 50;

    /**
     * Claim periods read as JULY-2026. Locale is pinned to ENGLISH so the period a
     * header is filed under never depends on the server's locale — a period generated
     * as JUILLET-2026 would silently fail to match the same month's existing header
     * and auto-create a duplicate.
     */
    private static final DateTimeFormatter PERIOD_FORMAT =
            DateTimeFormatter.ofPattern("MMMM-yyyy", Locale.ENGLISH);

    // ── Audit-log + notification wiring ──
    /** m07UserActionLog.Module / m07Notifications.ModuleId for HRMS claim actions. */
    private static final String MODULE_ID = "mod18";
    /** m07Notifications.NotificationType — staff-facing (claim outcome) notifications. */
    private static final String NOTIF_TYPE = "Staff-Claims";
    /** m07Notifications.NotificationType — approver-facing (claim submitted for approval). */
    private static final String NOTIF_TYPE_ADMIN = "Admin-Claims";
    /** System parameter naming the staff who receives claim-submitted notifications. */
    private static final String PARAM_HR_APPROVER = "HR-ADMIN-APPRV-IN-CHARGE";
    private static final int LEN_LOG_REFERENCE = 45;   // m07UserActionLog.ReferenceNo
    private static final int LEN_LOG_REMARKS   = 255;  // m07UserActionLog.Remarks
    private static final int LEN_NOTIF_SUBJECT = 200;  // m07Notifications.NotificationSubject
    private static final int LEN_NOTIF_DESC    = 255;  // m07Notifications.NotificationDesc
    private static final DateTimeFormatter TS_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    @Inject StaffClaimRepository headerRepo;
    @Inject StaffClaimDetailRepository detailRepo;
    @Inject StaffClaimDetailService detailService;
    @Inject CurrentUserService currentUserService;
    @Inject AttachmentService attachmentService;
    @Inject UserActionLogService userActionLogService;
    @Inject NotificationRepository notificationRepo;
    @Inject StaffRepository staffRepo;
    @Inject SystemParameterService systemParameterService;
    @Inject CompanyPoolManager companyPoolManager;

    // ─────────────────────────────────────────────────────────
    //  CREATE DRAFT
    // ─────────────────────────────────────────────────────────

    public Uni<StaffClaimDTO> createDraft(StaffClaimDTO dto) {
        return resolveStaffId(dto.getStaffId()).flatMap(staffId ->
                createDraftFor(staffId, dto.getClaimPeriod())
                        .map(saved -> toHeaderDto(saved, null)));
    }

    /**
     * The claim period the current date falls in, e.g. JULY-2026.
     * A claim is filed under the month it is entered in, not the month of the
     * receipt — a June receipt handed in late lands on the July claim.
     */
    public static String currentPeriod() {
        return DateUtil.nowSGT().toLocalDate().format(PERIOD_FORMAT).toUpperCase(Locale.ENGLISH);
    }

    /**
     * The staff member's open (DRAFT) claim for the current period, creating one if
     * they have none. This is what "Add Receipt" lands on, so a staff member never
     * has to create a claim before entering a receipt.
     *
     * Only DRAFT counts as open — it is the sole status a line can still be added to
     * ({@link #requireDraft}). A period whose claim is already SUBMITTED gets a fresh
     * DRAFT header alongside it, which is the intended behaviour: a late receipt for a
     * submitted month still needs somewhere to go.
     *
     * Not atomic — two Add Receipt taps racing each other could each find no draft and
     * create one. The window is small and the damage is a spare empty header rather
     * than lost data; closing it properly needs a unique index on
     * (StaffId, ClaimPeriod, Status), which is a migration.
     */
    public Uni<StaffClaimDTO> getOrCreateCurrentDraft(String requestedStaffId) {
        String period = currentPeriod();
        return resolveStaffId(requestedStaffId).flatMap(staffId ->
            companyPoolManager.poolFor(currentUserService.getCurrentCompanyId()).flatMap(pool ->
                headerRepo.findByStaffPeriodStatus(pool, staffId, period, STATUS_DRAFT)
                    .flatMap(existing -> existing != null
                            ? Uni.createFrom().item(existing)
                            : createDraftFor(staffId, period))
                    .flatMap(h -> getWithLines(h.getUniqId()))
            ));
    }

    /** Resolves the claimant: the logged-in user wins, falling back to the requested id. */
    private Uni<String> resolveStaffId(String requestedStaffId) {
        return currentUserService.getCurrentUser().flatMap(user -> {
            String staffId = (user != null && user.getStaffId() != null)
                    ? user.getStaffId()
                    : requestedStaffId;
            if (StringNormalizer.isBlank(staffId)) {
                return Uni.createFrom().failure(
                        new IllegalArgumentException("Cannot resolve the claimant (staffId)"));
            }
            return Uni.createFrom().item(staffId);
        });
    }

    private Uni<StaffClaim> createDraftFor(String staffId, String period) {
        LocalDateTime now = DateUtil.nowSGT();
        StaffClaim h = new StaffClaim();
        h.setStaffId(StringNormalizer.truncate(staffId, LEN_STAFF_ID));
        h.setClaimPeriod(StringNormalizer.truncate(period, LEN_PERIOD));
        h.setClaimAmount(BigDecimal.ZERO);
        h.setStatus(STATUS_DRAFT);
        h.setEntryStaff(h.getStaffId());
        h.setEntryDate(now);
        h.setLastEditStaff(h.getStaffId());
        h.setLastEditDate(now);
        return companyPoolManager.poolFor(currentUserService.getCurrentCompanyId())
            .flatMap(pool -> pool.withTransaction(tx -> headerRepo.save(tx, h)));
    }

    // ─────────────────────────────────────────────────────────
    //  READ
    // ─────────────────────────────────────────────────────────

    /** Header-level list for a staff member (no line items, but with line counts). */
    public Uni<List<StaffClaimDTO>> listByStaff(String staffId) {
        return companyPoolManager.poolFor(currentUserService.getCurrentCompanyId()).flatMap(pool ->
            headerRepo.findByStaff(pool, staffId).flatMap(headers -> {
                if (headers.isEmpty()) {
                    return Uni.createFrom().item(List.<StaffClaimDTO>of());
                }
                List<Long> ids = headers.stream().map(h -> h.getUniqId()).toList();
                return detailRepo.countByHeaderIds(pool, ids).map(rows -> {
                    java.util.Map<Long, Integer> counts = new java.util.HashMap<>();
                    for (io.vertx.mutiny.sqlclient.Row r : rows) {
                        counts.put(r.getLong("ClaimId"), r.getInteger("cnt"));
                    }
                    return headers.stream().map(h -> {
                        StaffClaimDTO dto = toHeaderDto(h, null);
                        dto.setLineCount(counts.getOrDefault(h.getUniqId(), 0));
                        return dto;
                    }).toList();
                });
            }));
    }

    /** One claim with all its line items populated. */
    public Uni<StaffClaimDTO> getWithLines(Long headerId) {
        return companyPoolManager.poolFor(currentUserService.getCurrentCompanyId()).flatMap(pool ->
            headerRepo.findById(pool, headerId).flatMap(h -> {
                if (h == null) return Uni.createFrom().nullItem();
                return detailRepo.findByHeaderId(pool, headerId).map(lines -> {
                    List<StaffClaimDetailDTO> lineDtos =
                            lines.stream().map(detailService::toDtoBasic).toList();
                    return toHeaderDto(h, lineDtos);
                });
            }));
    }

    // ─────────────────────────────────────────────────────────
    //  ADD / REMOVE LINES (draft only)
    // ─────────────────────────────────────────────────────────

    public Uni<StaffClaimDetailDTO> addLine(
            Long headerId, StaffClaimDetailDTO lineDto,
            byte[] photoData, String photoFileName, String photoContentType,
            DeviceInfo deviceInfo) {

        return requireDraft(headerId).flatMap(h -> {
            lineDto.setClaimId(headerId);
            return detailService.createClaim(lineDto, photoData, photoFileName, photoContentType)
                    .flatMap(saved -> recalcTotal(headerId).replaceWith(saved))
                    .call(saved -> logAction(saved.getStaffId(), h.getClaimPeriod(),
                            UserActionLogService.Action.ADD,
                            "Added receipt " + nz(saved.getReceiptNumber())
                                    + " (" + nz(saved.getClaimType()) + ")", deviceInfo));
        });
    }

    public Uni<Void> removeLine(Long headerId, Long lineId) {
        return requireDraft(headerId).flatMap(h ->
            // Remove the line's receipt attachment(s) first (FTP file + m10Attachments row),
            // so deleting a draft receipt never leaves an orphaned file/record behind.
            deleteLineAttachments(lineId)
                .flatMap(ignored -> companyPoolManager.poolFor(currentUserService.getCurrentCompanyId())
                    .flatMap(pool -> pool.withTransaction(tx -> detailRepo.deleteById(tx, lineId))))
                .flatMap(deleted -> {
                    if (Boolean.FALSE.equals(deleted)) {
                        return Uni.createFrom().failure(
                                new NotFoundException("Line " + lineId + " not found"));
                    }
                    return recalcTotal(headerId).replaceWithVoid();
                })
        );
    }

    /**
     * Deletes every receipt attachment linked to a claim line — both the FTP file and
     * the m10Attachments row — via {@link AttachmentService#deleteAttachment}. No-op when
     * the line has no receipt. Attachments are keyed by moduleType="CLAIM", referenceCode=lineId.
     */
    private Uni<Void> deleteLineAttachments(Long lineId) {
        return attachmentService
                .getAttachments(StaffClaimDetailService.MODULE_TYPE, String.valueOf(lineId))
                .flatMap(atts -> {
                    Uni<Void> chain = Uni.createFrom().voidItem();
                    if (atts == null || atts.isEmpty()) {
                        return chain;
                    }
                    for (AttachmentDTO att : atts) {
                        chain = chain.flatMap(v ->
                                attachmentService.deleteAttachment(att.getUniqId()).replaceWithVoid());
                    }
                    return chain;
                });
    }

    // ─────────────────────────────────────────────────────────
    //  SUBMIT
    // ─────────────────────────────────────────────────────────

    public Uni<StaffClaimDTO> submit(Long headerId, DeviceInfo deviceInfo) {
        return companyPoolManager.poolFor(currentUserService.getCurrentCompanyId()).flatMap(pool ->
            detailRepo.findByHeaderId(pool, headerId).flatMap(lines -> {
                if (lines.isEmpty()) {
                    return Uni.createFrom().failure(
                            new IllegalArgumentException("Cannot submit a claim with no line items"));
                }
                BigDecimal total = sumClaimAmount(lines);
                LocalDateTime now = DateUtil.nowSGT();

                return pool.withTransaction(tx -> headerRepo.findById(tx, headerId).flatMap(h -> {
                    if (h == null) {
                        return Uni.createFrom().failure(
                                new NotFoundException("Claim " + headerId + " not found"));
                    }
                    if (!STATUS_DRAFT.equals(h.getStatus())) {
                        return Uni.createFrom().failure(new IllegalArgumentException(
                                "Only a DRAFT claim can be submitted (current status: " + h.getStatus() + ")"));
                    }
                    // Number the period at submit (JULY-2026 → JULY-2026-001) so multiple
                    // claims in one month are distinguishable. Drafts keep the plain month so
                    // the get-or-create lookup still matches.
                    String basePeriod = h.getClaimPeriod();
                    Uni<String> numberedPeriod = StringNormalizer.isBlank(basePeriod)
                            ? Uni.createFrom().item(basePeriod)
                            : headerRepo.findPeriodsWithSuffix(tx, h.getStaffId(), basePeriod)
                                    .map(existing -> nextNumberedPeriod(basePeriod, existing));
                    return numberedPeriod.flatMap(period -> {
                        h.setClaimPeriod(period);
                        h.setClaimAmount(total);
                        h.setStatus(STATUS_SUBMITTED);
                        h.setSubmittedDate(now);
                        h.setLastEditDate(now);
                        return headerRepo.update(tx, h);
                    });
                })).map(saved -> toHeaderDto(saved, null))
                  .call(dto -> logAction(dto.getEntryStaff(), dto.getClaimPeriod(),
                          UserActionLogService.Action.SUBMIT,
                          "Submitted claim " + nz(dto.getClaimPeriod()) + " of amount "
                                  + plain(dto.getClaimAmount()), deviceInfo))
                  .call(this::notifyClaimSubmitted);
            }));
    }

    /**
     * Submits several drafts in one action (the Submit Claims tick-list).
     *
     * Every claim is checked before any is written, so the common mistakes — an empty
     * draft, or one already submitted in another tab — are rejected with nothing
     * submitted. The submits themselves are separate transactions: a failure after
     * that point (a dropped connection mid-run) can still leave earlier claims in the
     * batch submitted, which is recoverable and visible, since each claim's status is
     * shown in the list the user returns to.
     */
    public Uni<List<StaffClaimDTO>> submitBatch(List<Long> headerIds, DeviceInfo deviceInfo) {
        if (headerIds == null || headerIds.isEmpty()) {
            return Uni.createFrom().failure(
                    new IllegalArgumentException("Select at least one claim to submit"));
        }
        List<Long> ids = headerIds.stream().distinct().toList();

        Uni<Void> validated = Uni.createFrom().voidItem();
        for (Long id : ids) {
            validated = validated.flatMap(v -> requireSubmittable(id));
        }

        Uni<List<StaffClaimDTO>> chain = validated.replaceWith(new ArrayList<>());
        for (Long id : ids) {
            chain = chain.flatMap(acc -> submit(id, deviceInfo).map(dto -> {
                acc.add(dto);
                return acc;
            }));
        }
        return chain.map(list -> (List<StaffClaimDTO>) list);
    }

    /** Fails unless the claim exists, is still a DRAFT, and has at least one line. */
    private Uni<Void> requireSubmittable(Long headerId) {
        return requireDraft(headerId).flatMap(h ->
            companyPoolManager.poolFor(currentUserService.getCurrentCompanyId()).flatMap(pool ->
                detailRepo.findByHeaderId(pool, headerId).flatMap(lines -> {
                    if (lines.isEmpty()) {
                        return Uni.createFrom().failure(new IllegalArgumentException(
                                "Claim " + h.getClaimPeriod() + " has no receipts and cannot be submitted"));
                    }
                    return Uni.createFrom().voidItem();
                })
            ));
    }

    // ─────────────────────────────────────────────────────────
    //  HELPERS
    // ─────────────────────────────────────────────────────────

    /** Loads the header and fails unless it exists and is still a DRAFT. */
    private Uni<StaffClaim> requireDraft(Long headerId) {
        return companyPoolManager.poolFor(currentUserService.getCurrentCompanyId()).flatMap(pool ->
            headerRepo.findById(pool, headerId).flatMap(h -> {
                if (h == null) {
                    return Uni.createFrom().failure(
                            new NotFoundException("Claim " + headerId + " not found"));
                }
                if (!STATUS_DRAFT.equals(h.getStatus())) {
                    return Uni.createFrom().failure(new IllegalArgumentException(
                            "Claim is no longer a draft (status: " + h.getStatus() + ") and cannot be modified"));
                }
                return Uni.createFrom().item(h);
            }));
    }

    /** Recomputes header ClaimAmount = sum of its line ClaimAmounts. */
    private Uni<StaffClaim> recalcTotal(Long headerId) {
        return companyPoolManager.poolFor(currentUserService.getCurrentCompanyId()).flatMap(pool ->
            detailRepo.findByHeaderId(pool, headerId).flatMap(lines -> {
                BigDecimal total = sumClaimAmount(lines);
                return pool.withTransaction(tx -> headerRepo.findById(tx, headerId).flatMap(h -> {
                    if (h == null) return Uni.createFrom().nullItem();
                    h.setClaimAmount(total);
                    h.setLastEditDate(DateUtil.nowSGT());
                    return headerRepo.update(tx, h);
                }));
            }));
    }

    // ─────────────────────────────────────────────────────────
    //  REJECTED-RECEIPT WORKFLOW (staff side)
    // ─────────────────────────────────────────────────────────

    /**
     * Staff accepts a rejection (scenario 2): the receipt is voided — struck off the
     * claim and excluded from the total. Only a REJECTED receipt can be voided. The
     * header total + status are rolled up afterwards.
     */
    public Uni<StaffClaimDTO> acceptRejection(Long headerId, Long lineId, DeviceInfo deviceInfo) {
        return currentUserService.getCurrentUserLoginId().flatMap(actor ->
            companyPoolManager.poolFor(currentUserService.getCurrentCompanyId()).flatMap(pool ->
                pool.withTransaction(tx ->
                    requireLine(headerId, lineId).flatMap(line -> {
                        if (!LINE_REJECTED.equalsIgnoreCase(line.getStatus())) {
                            return Uni.<StaffClaimDetail>createFrom().failure(new IllegalArgumentException(
                                    "Only a rejected receipt can be voided (current status: "
                                            + line.getStatus() + ")"));
                        }
                        LocalDateTime now = DateUtil.nowSGT();
                        line.setStatus(LINE_VOID);
                        line.setLastEditStaff(actor);
                        line.setLastEditDate(now);
                        return detailRepo.update(tx, line);
                    })
                ))
            .flatMap(v -> recalcAndRollup(headerId, actor))
            .flatMap(h -> getWithLines(headerId))
            .call(dto -> logAction(actor, dto.getClaimPeriod(), UserActionLogService.Action.VOID,
                    "Accepted rejection — voided receipt " + lineId, deviceInfo))
        );
    }

    /**
     * Staff fixes or appeals a rejected receipt and resubmits it (scenarios 1 & 2):
     * the receipt returns to PENDING for re-review and the header goes back under
     * review. An optional appeal note is written to the receipt's description.
     * (Re-attaching the receipt photo is a separate call on the attachment endpoint.)
     */
    public Uni<StaffClaimDTO> resubmitRejectedLine(Long headerId, Long lineId,
            String appealDescription, DeviceInfo deviceInfo) {
        return currentUserService.getCurrentUserLoginId().flatMap(actor ->
            companyPoolManager.poolFor(currentUserService.getCurrentCompanyId()).flatMap(pool ->
                pool.withTransaction(tx ->
                    requireLine(headerId, lineId).flatMap(line -> {
                        if (!LINE_REJECTED.equalsIgnoreCase(line.getStatus())) {
                            return Uni.<StaffClaimDetail>createFrom().failure(new IllegalArgumentException(
                                    "Only a rejected receipt can be resubmitted (current status: "
                                            + line.getStatus() + ")"));
                        }
                        LocalDateTime now = DateUtil.nowSGT();
                        if (appealDescription != null && !appealDescription.isBlank()) {
                            String d = appealDescription.trim();
                            line.setClaimDescription(
                                    d.length() > LEN_DESCRIPTION ? d.substring(0, LEN_DESCRIPTION) : d);
                        }
                        line.setStatus(LINE_PENDING);
                        line.setApprovedBy(null);
                        line.setApprovedDate(null);
                        // RejectReason is left intact as history; the reject trail also lives
                        // in m07UserActionLog.
                        line.setLastEditStaff(actor);
                        line.setLastEditDate(now);
                        return detailRepo.update(tx, line);
                    })
                ))
            .flatMap(v -> recalcAndRollup(headerId, actor))
            .flatMap(h -> getWithLines(headerId))
            .call(dto -> logAction(actor, dto.getClaimPeriod(), UserActionLogService.Action.EDIT,
                    "Resubmitted receipt " + lineId
                            + (appealDescription != null && !appealDescription.isBlank()
                                    ? " with appeal" : ""), deviceInfo))
            .call(this::notifyClaimResubmitted)
        );
    }

    /**
     * Staff fixes a rejected receipt (details + amount + optional new photo) and resubmits
     * it (→ PENDING). Project, Claim Type, Description and Claim Date are locked.
     */
    public Uni<StaffClaimDTO> editRejectedLine(Long headerId, Long lineId, StaffClaimDetailDTO dto,
            byte[] photoData, String photoFileName, String photoContentType) {
        return currentUserService.getCurrentUserLoginId().flatMap(actor ->
            requireLine(headerId, lineId).flatMap(line -> {
                if (!LINE_REJECTED.equalsIgnoreCase(line.getStatus())) {
                    return Uni.<StaffClaimDetail>createFrom().failure(new IllegalArgumentException(
                            "Only a rejected receipt can be edited (current status: "
                                    + line.getStatus() + ")"));
                }
                // Convert the edited amount to base before the write transaction (mirrors create);
                // the locked claim date is the rate-date fallback.
                return detailService.convertForEdit(dto, line.getClaimDate()).flatMap(conv ->
                    companyPoolManager.poolFor(currentUserService.getCurrentCompanyId()).flatMap(pool ->
                        pool.withTransaction(tx ->
                            requireLine(headerId, lineId).flatMap(fresh -> {
                                detailService.applyEditedFields(fresh, dto, conv);
                                LocalDateTime now = DateUtil.nowSGT();
                                fresh.setStatus(LINE_PENDING);
                                fresh.setApprovedBy(null);
                                fresh.setApprovedDate(null);
                                fresh.setLastEditStaff(actor);
                                fresh.setLastEditDate(now);
                                return detailRepo.update(tx, fresh);
                            })
                        )
                    )
                );
            })
            .flatMap(saved -> applyPhotoChange(lineId, photoData, photoFileName, photoContentType)
                    .replaceWith(saved))
            .flatMap(v -> recalcAndRollup(headerId, actor))
            .flatMap(h -> getWithLines(headerId))
            .call(this::notifyClaimResubmitted)
        );
    }

    /**
     * Uploads a replacement receipt photo as a new version (the old one is kept). Runs
     * outside the row transaction; a failed upload never undoes the committed edit.
     */
    private Uni<Void> applyPhotoChange(Long lineId,
            byte[] photoData, String photoFileName, String photoContentType) {
        if (photoData != null && photoData.length > 0) {
            return attachmentService.uploadFile(
                    StaffClaimDetailService.MODULE_TYPE, String.valueOf(lineId),
                    photoFileName != null ? photoFileName : "receipt.jpg",
                    photoContentType != null ? photoContentType : "image/jpeg",
                    photoData)
                .replaceWithVoid()
                .onFailure().recoverWithItem((Void) null);
        }
        return Uni.createFrom().voidItem();
    }

    /** Loads a receipt and fails unless it exists and belongs to the given claim. */
    private Uni<StaffClaimDetail> requireLine(Long headerId, Long lineId) {
        return companyPoolManager.poolFor(currentUserService.getCurrentCompanyId()).flatMap(pool ->
            detailRepo.findById(pool, lineId).flatMap(line -> {
                if (line == null) {
                    return Uni.createFrom().failure(new NotFoundException("Receipt " + lineId + " not found"));
                }
                if (!headerId.equals(line.getClaimId())) {
                    return Uni.createFrom().failure(new IllegalArgumentException(
                            "Receipt " + lineId + " does not belong to claim " + headerId));
                }
                return Uni.createFrom().item(line);
            }));
    }

    /**
     * After a staff void/resubmit, recompute the header total (voided receipts excluded) and roll
     * the header status up from the surviving receipt decisions — mirrors the admin rollup in
     * hr-administration ClaimApprovalService, with voided receipts struck off entirely.
     */
    private Uni<StaffClaim> recalcAndRollup(Long headerId, String actor) {
        return companyPoolManager.poolFor(currentUserService.getCurrentCompanyId()).flatMap(pool ->
            detailRepo.findByHeaderId(pool, headerId).flatMap(lines -> {
                BigDecimal total = sumClaimAmount(lines); // voided receipts excluded
                int approved = 0, rejected = 0, pending = 0, voided = 0;
                BigDecimal approvedAmt = BigDecimal.ZERO;
                BigDecimal rejectedAmt = BigDecimal.ZERO;
                for (StaffClaimDetail l : lines) {
                    String st = l.getStatus();
                    BigDecimal amt = l.getClaimAmount() == null ? BigDecimal.ZERO : l.getClaimAmount();
                    if (LINE_VOID.equalsIgnoreCase(st)) {
                        // Accepted rejection: struck off entirely — excluded from the claim total and
                        // from every amount (approved/rejected), as if the receipt no longer exists.
                        voided++;
                        continue;
                    }
                    if (LINE_APPROVED.equalsIgnoreCase(st)) {
                        approved++;
                        approvedAmt = approvedAmt.add(amt);
                    } else if (LINE_REJECTED.equalsIgnoreCase(st)) {
                        rejected++;
                        rejectedAmt = rejectedAmt.add(amt);
                    } else {
                        pending++;
                    }
                }
                final String newStatus;
                if (pending > 0) {
                    newStatus = STATUS_SUBMITTED;             // back under review
                } else if (approved > 0 && rejected > 0) {
                    newStatus = STATUS_PARTIAL;
                } else if (approved > 0) {
                    newStatus = STATUS_APPROVED;
                } else if (rejected > 0) {
                    newStatus = STATUS_REJECTED;
                } else if (voided > 0) {
                    newStatus = STATUS_VOIDED;                // every receipt voided
                } else {
                    newStatus = STATUS_SUBMITTED;
                }
                final BigDecimal frozenApproved = (pending > 0) ? null : approvedAmt;
                final BigDecimal frozenRejected = rejectedAmt;
                // A void can turn a partially-approved claim (its last rejected receipt struck
                // off) into a wholly-approved one — notify the claimant, mirroring the admin
                // approve path. resubmit never reaches APPROVED (a pending line forces
                // SUBMITTED), so the new status alone is a safe transition signal here.
                final boolean becameApproved = STATUS_APPROVED.equals(newStatus);
                return pool.withTransaction(tx -> headerRepo.findById(tx, headerId).flatMap(h -> {
                    if (h == null) return Uni.createFrom().nullItem();
                    h.setClaimAmount(total);
                    h.setStatus(newStatus);
                    h.setApprovedAmount(frozenApproved);
                    h.setRejectedAmount(frozenRejected);
                    h.setLastEditStaff(actor);
                    h.setLastEditDate(DateUtil.nowSGT());
                    return headerRepo.update(tx, h);
                }))
                .call(saved -> (becameApproved && saved != null)
                        ? notifyClaimApproved(saved)
                        : Uni.createFrom().voidItem());
            }));
    }

    /** Next numbered period under a base month, e.g. "JULY-2026" → "JULY-2026-002" given an
     *  existing "JULY-2026-001"; zero-padded to three digits. */
    private String nextNumberedPeriod(String basePeriod, List<String> existing) {
        String prefix = basePeriod + "-";
        int max = 0;
        for (String p : existing) {
            if (p == null || !p.startsWith(prefix)) continue;
            try {
                int n = Integer.parseInt(p.substring(prefix.length()).trim());
                if (n > max) max = n;
            } catch (NumberFormatException ignored) { /* not a numeric suffix */ }
        }
        return String.format("%s-%03d", basePeriod, max + 1);
    }

    private BigDecimal sumClaimAmount(List<StaffClaimDetail> lines) {
        // A voided (accepted-rejection) receipt is struck off entirely — excluded from the claim
        // total and from every amount, as if it no longer exists.
        return lines.stream()
                .filter(l -> !LINE_VOID.equalsIgnoreCase(l.getStatus()))
                .map(l -> l.getClaimAmount() == null ? BigDecimal.ZERO : l.getClaimAmount())
                .reduce(BigDecimal.ZERO, (sum, value) -> sum.add(value));
    }

    // ─────────────────────────────────────────────────────────
    //  AUDIT LOG + NOTIFICATIONS (best-effort)
    // ─────────────────────────────────────────────────────────
    // These never fail the business action they trail: each swallows its own error.

    /** Writes one m07UserActionLog row (device info from the request); failures are swallowed. */
    private Uni<Void> logAction(String staffId, String referenceNo, String action,
                                String remarks, DeviceInfo deviceInfo) {
        return userActionLogService.logAction(
                currentUserService.getCurrentCompanyId(), staffId, UserActionLogService.Module.STAFF_CLAIM,
                truncate(referenceNo, LEN_LOG_REFERENCE), action, deviceInfo,
                truncate(remarks, LEN_LOG_REMARKS));
    }

    /**
     * On submit, notify the HR approver named by the HR-ADMIN-APPRV-IN-CHARGE system
     * parameter. A missing parameter or unknown staff name degrades to a skip / the raw
     * id rather than failing the submit.
     */
    private Uni<Void> notifyClaimSubmitted(StaffClaimDTO claim) {
        String submitter = claim.getEntryStaff() != null ? claim.getEntryStaff() : claim.getStaffId();
        return systemParameterService.loadParameter(PARAM_HR_APPROVER)
            .onFailure().recoverWithItem((String) null)
            .flatMap(recipient -> {
                if (recipient == null || recipient.isBlank()) {
                    System.err.println("[Notification] " + PARAM_HR_APPROVER
                            + " not configured — submit notification skipped for claim "
                            + claim.getUniqId());
                    return Uni.createFrom().voidItem();
                }
                // Sequential (never combined) — these reads share one reactive session.
                return companyPoolManager.poolFor(currentUserService.getCurrentCompanyId()).flatMap(pool ->
                    staffRepo.findNameByStaffId(pool, submitter)
                        .onFailure().recoverWithItem((String) null)
                        .flatMap(name -> loadBaseCurrencySafe().flatMap(baseCcy -> {
                            String who = (name != null && !name.isBlank()) ? name : submitter;
                            String subject = "You received staff claim submitted by " + who
                                    + " - " + nz(claim.getClaimPeriod());
                            String desc = "You have received a claims submitted by " + who
                                    + " of amount " + money(baseCcy, claim.getClaimAmount())
                                    + " for claim period " + nz(claim.getClaimPeriod())
                                    + " submitted on " + ts(claim.getSubmittedDate()) + ".";
                            return createNotification(NOTIF_TYPE_ADMIN, subject, desc, recipient,
                                    submitter, refOf(claim.getUniqId()));
                        })));
            })
            .onFailure().recoverWithItem((Void) null);
    }

    /**
     * On resubmit, notify the HR approver that a previously-rejected receipt is back for
     * review. Mirrors {@link #notifyClaimSubmitted}; a missing approver parameter is skipped.
     */
    private Uni<Void> notifyClaimResubmitted(StaffClaimDTO claim) {
        String submitter = claim.getEntryStaff() != null ? claim.getEntryStaff() : claim.getStaffId();
        return systemParameterService.loadParameter(PARAM_HR_APPROVER)
            .onFailure().recoverWithItem((String) null)
            .flatMap(recipient -> {
                if (recipient == null || recipient.isBlank()) {
                    return Uni.createFrom().voidItem();
                }
                return companyPoolManager.poolFor(currentUserService.getCurrentCompanyId()).flatMap(pool ->
                    staffRepo.findNameByStaffId(pool, submitter)
                        .onFailure().recoverWithItem((String) null)
                        .flatMap(name -> {
                            String who = (name != null && !name.isBlank()) ? name : submitter;
                            String subject = "Receipt resubmitted for review by " + who
                                    + " - " + nz(claim.getClaimPeriod());
                            String desc = who + " has resubmitted a receipt for claim period "
                                    + nz(claim.getClaimPeriod()) + " for your review.";
                            return createNotification(NOTIF_TYPE_ADMIN, subject, desc, recipient,
                                    submitter, refOf(claim.getUniqId()));
                        }));
            })
            .onFailure().recoverWithItem((Void) null);
    }

    /** Notifies the claimant that their claim is wholly approved. */
    private Uni<Void> notifyClaimApproved(StaffClaim claim) {
        String claimant = claim.getEntryStaff() != null ? claim.getEntryStaff() : claim.getStaffId();
        if (claimant == null || claimant.isBlank()) {
            return Uni.createFrom().voidItem();
        }
        return loadBaseCurrencySafe().flatMap(baseCcy -> {
            String subject = "Your " + nz(claim.getClaimPeriod()) + " claim is approved.";
            String desc = "Your claim for " + nz(claim.getClaimPeriod())
                    + " of amount " + money(baseCcy, claim.getClaimAmount())
                    + " submitted on " + ts(claim.getSubmittedDate()) + " is being approved.";
            return createNotification(NOTIF_TYPE, subject, desc, claimant, claimant,
                    refOf(claim.getUniqId()));
        })
        .onFailure().recoverWithItem((Void) null);
    }

    private Uni<Void> createNotification(String notificationType, String subject, String desc,
                                         String notifyStaff, String entryStaff, String referenceNo) {
        return companyPoolManager.poolFor(currentUserService.getCurrentCompanyId()).flatMap(pool ->
                pool.withTransaction(tx ->
                    notificationRepo.create(tx, MODULE_ID, notificationType,
                            truncate(subject, LEN_NOTIF_SUBJECT), truncate(desc, LEN_NOTIF_DESC),
                            notifyStaff, entryStaff, referenceNo)))
            .replaceWithVoid();
    }

    /** The claim's header id as text, for the notification reference (deep-link target). */
    private static String refOf(Long claimId) {
        return claimId == null ? null : String.valueOf(claimId);
    }

    /** Base currency, or null when it can't be read — the amount then prints without a code. */
    private Uni<String> loadBaseCurrencySafe() {
        return systemParameterService.loadBaseCurrency().onFailure().recoverWithItem((String) null);
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    /** Plain 2-dp amount, no currency (used in audit remarks). */
    private static String plain(BigDecimal amount) {
        return amount == null ? "0.00" : amount.setScale(2, RoundingMode.HALF_UP).toPlainString();
    }

    /** "{CCY} {amount}" for notification text; drops the code if base currency is unknown. */
    private static String money(String currency, BigDecimal amount) {
        String value = plain(amount);
        return (currency == null || currency.isBlank()) ? value : currency + " " + value;
    }

    private static String ts(LocalDateTime dt) {
        return dt == null ? "" : dt.format(TS_FORMAT);
    }

    private static String truncate(String s, int max) {
        if (s == null) return null;
        return s.length() > max ? s.substring(0, max) : s;
    }

    private StaffClaimDTO toHeaderDto(StaffClaim h, List<StaffClaimDetailDTO> lines) {
        StaffClaimDTO dto = new StaffClaimDTO();
        dto.setUniqId(h.getUniqId());
        dto.setStaffId(h.getStaffId());
        dto.setEntryStaff(h.getEntryStaff());
        dto.setEntryDate(h.getEntryDate());
        dto.setLastEditStaff(h.getLastEditStaff());
        dto.setLastEditDate(h.getLastEditDate());
        dto.setClaimPeriod(h.getClaimPeriod());
        dto.setClaimAmount(h.getClaimAmount());
        dto.setStatus(h.getStatus());
        dto.setSubmittedDate(h.getSubmittedDate());
        dto.setApprovedAmount(h.getApprovedAmount());
        dto.setRejectedAmount(h.getRejectedAmount());
        dto.setLines(lines);
        dto.setLineCount(lines != null ? lines.size() : null);
        return dto;
    }
}
