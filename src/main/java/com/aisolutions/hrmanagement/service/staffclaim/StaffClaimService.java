package com.aisolutions.hrmanagement.service.staffclaim;

import com.aisolutions.hrmanagement.dto.AttachmentDTO;
import com.aisolutions.hrmanagement.dto.StaffClaimDTO;
import com.aisolutions.hrmanagement.dto.StaffClaimDetailDTO;
import com.aisolutions.hrmanagement.entity.StaffClaim;
import com.aisolutions.hrmanagement.entity.StaffClaimDetail;
import com.aisolutions.hrmanagement.repository.StaffClaimDetailRepository;
import com.aisolutions.hrmanagement.repository.StaffClaimRepository;
import com.aisolutions.hrmanagement.service.CurrentUserService;
import com.aisolutions.hrmanagement.service.attachment.AttachmentService;
import com.aisolutions.hrmanagement.util.StringNormalizer;

import io.quarkus.hibernate.reactive.panache.Panache;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.NotFoundException;

import java.math.BigDecimal;
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

    @Inject StaffClaimRepository headerRepo;
    @Inject StaffClaimDetailRepository detailRepo;
    @Inject StaffClaimDetailService detailService;
    @Inject CurrentUserService currentUserService;
    @Inject AttachmentService attachmentService;

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
            headerRepo.findByStaffPeriodStatus(staffId, period, STATUS_DRAFT)
                .flatMap(existing -> existing != null
                        ? Uni.createFrom().item(existing)
                        : createDraftFor(staffId, period))
                .flatMap(h -> getWithLines(h.getUniqId()))
        );
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
        return Panache.withTransaction(() -> headerRepo.save(h));
    }

    // ─────────────────────────────────────────────────────────
    //  READ
    // ─────────────────────────────────────────────────────────

    /** Header-level list for a staff member (no line items, but with line counts). */
    public Uni<List<StaffClaimDTO>> listByStaff(String staffId) {
        return headerRepo.findByStaff(staffId).flatMap(headers -> {
            if (headers.isEmpty()) {
                return Uni.createFrom().item(List.<StaffClaimDTO>of());
            }
            List<Long> ids = headers.stream().map(h -> h.getUniqId()).toList();
            return detailRepo.countByHeaderIds(ids).map(rows -> {
                java.util.Map<Long, Integer> counts = new java.util.HashMap<>();
                for (Object[] r : rows) {
                    counts.put(((Number) r[0]).longValue(), ((Number) r[1]).intValue());
                }
                return headers.stream().map(h -> {
                    StaffClaimDTO dto = toHeaderDto(h, null);
                    dto.setLineCount(counts.getOrDefault(h.getUniqId(), 0));
                    return dto;
                }).toList();
            });
        });
    }

    /** One claim with all its line items populated. */
    public Uni<StaffClaimDTO> getWithLines(Long headerId) {
        return headerRepo.findById(headerId).flatMap(h -> {
            if (h == null) return Uni.createFrom().nullItem();
            return detailRepo.findByHeaderId(headerId).map(lines -> {
                List<StaffClaimDetailDTO> lineDtos =
                        lines.stream().map(detailService::toDtoBasic).toList();
                return toHeaderDto(h, lineDtos);
            });
        });
    }

    // ─────────────────────────────────────────────────────────
    //  ADD / REMOVE LINES (draft only)
    // ─────────────────────────────────────────────────────────

    public Uni<StaffClaimDetailDTO> addLine(
            Long headerId, StaffClaimDetailDTO lineDto,
            byte[] photoData, String photoFileName, String photoContentType) {

        return requireDraft(headerId).flatMap(h -> {
            lineDto.setClaimId(headerId);
            return detailService.createClaim(lineDto, photoData, photoFileName, photoContentType)
                    .flatMap(saved -> recalcTotal(headerId).replaceWith(saved));
        });
    }

    public Uni<Void> removeLine(Long headerId, Long lineId) {
        return requireDraft(headerId).flatMap(h ->
            // Remove the line's receipt attachment(s) first (FTP file + m10Attachments row),
            // so deleting a draft receipt never leaves an orphaned file/record behind.
            deleteLineAttachments(lineId)
                .flatMap(ignored -> Panache.withTransaction(() -> detailRepo.deleteById(lineId)))
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

    public Uni<StaffClaimDTO> submit(Long headerId) {
        return detailRepo.findByHeaderId(headerId).flatMap(lines -> {
            if (lines.isEmpty()) {
                return Uni.createFrom().failure(
                        new IllegalArgumentException("Cannot submit a claim with no line items"));
            }
            BigDecimal total = sumClaimAmount(lines);
            LocalDateTime now = DateUtil.nowSGT();

            return Panache.withTransaction(() -> headerRepo.findById(headerId).flatMap(h -> {
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
                        : headerRepo.findPeriodsWithSuffix(h.getStaffId(), basePeriod)
                                .map(existing -> nextNumberedPeriod(basePeriod, existing));
                return numberedPeriod.flatMap(period -> {
                    h.setClaimPeriod(period);
                    h.setClaimAmount(total);
                    h.setStatus(STATUS_SUBMITTED);
                    h.setSubmittedDate(now);
                    h.setLastEditDate(now);
                    return headerRepo.update(h);
                });
            })).map(saved -> toHeaderDto(saved, null));
        });
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
    public Uni<List<StaffClaimDTO>> submitBatch(List<Long> headerIds) {
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
            chain = chain.flatMap(acc -> submit(id).map(dto -> {
                acc.add(dto);
                return acc;
            }));
        }
        return chain.map(list -> (List<StaffClaimDTO>) list);
    }

    /** Fails unless the claim exists, is still a DRAFT, and has at least one line. */
    private Uni<Void> requireSubmittable(Long headerId) {
        return requireDraft(headerId).flatMap(h ->
            detailRepo.findByHeaderId(headerId).flatMap(lines -> {
                if (lines.isEmpty()) {
                    return Uni.createFrom().failure(new IllegalArgumentException(
                            "Claim " + h.getClaimPeriod() + " has no receipts and cannot be submitted"));
                }
                return Uni.createFrom().voidItem();
            })
        );
    }

    // ─────────────────────────────────────────────────────────
    //  HELPERS
    // ─────────────────────────────────────────────────────────

    /** Loads the header and fails unless it exists and is still a DRAFT. */
    private Uni<StaffClaim> requireDraft(Long headerId) {
        return headerRepo.findById(headerId).flatMap(h -> {
            if (h == null) {
                return Uni.createFrom().failure(
                        new NotFoundException("Claim " + headerId + " not found"));
            }
            if (!STATUS_DRAFT.equals(h.getStatus())) {
                return Uni.createFrom().failure(new IllegalArgumentException(
                        "Claim is no longer a draft (status: " + h.getStatus() + ") and cannot be modified"));
            }
            return Uni.createFrom().item(h);
        });
    }

    /** Recomputes header ClaimAmount = sum of its line ClaimAmounts. */
    private Uni<StaffClaim> recalcTotal(Long headerId) {
        return detailRepo.findByHeaderId(headerId).flatMap(lines -> {
            BigDecimal total = sumClaimAmount(lines);
            return Panache.withTransaction(() -> headerRepo.findById(headerId).flatMap(h -> {
                if (h == null) return Uni.createFrom().nullItem();
                h.setClaimAmount(total);
                h.setLastEditDate(DateUtil.nowSGT());
                return headerRepo.update(h);
            }));
        });
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
        return lines.stream()
                .map(l -> l.getClaimAmount() == null ? BigDecimal.ZERO : l.getClaimAmount())
                .reduce(BigDecimal.ZERO, (sum, value) -> sum.add(value));
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
        dto.setLines(lines);
        dto.setLineCount(lines != null ? lines.size() : null);
        return dto;
    }
}
