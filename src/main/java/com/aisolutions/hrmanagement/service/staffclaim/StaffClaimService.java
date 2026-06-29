package com.aisolutions.hrmanagement.service.staffclaim;

import com.aisolutions.hrmanagement.dto.StaffClaimDTO;
import com.aisolutions.hrmanagement.dto.StaffClaimDetailDTO;
import com.aisolutions.hrmanagement.entity.StaffClaim;
import com.aisolutions.hrmanagement.entity.StaffClaimDetail;
import com.aisolutions.hrmanagement.repository.StaffClaimDetailRepository;
import com.aisolutions.hrmanagement.repository.StaffClaimRepository;
import com.aisolutions.hrmanagement.service.CurrentUserService;
import com.aisolutions.hrmanagement.util.StringNormalizer;

import io.quarkus.hibernate.reactive.panache.Panache;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.NotFoundException;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

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

    @Inject StaffClaimRepository headerRepo;
    @Inject StaffClaimDetailRepository detailRepo;
    @Inject StaffClaimDetailService detailService;
    @Inject CurrentUserService currentUserService;

    // ─────────────────────────────────────────────────────────
    //  CREATE DRAFT
    // ─────────────────────────────────────────────────────────

    public Uni<StaffClaimDTO> createDraft(StaffClaimDTO dto) {
        return currentUserService.getCurrentUser().flatMap(user -> {
            String staffId = (user != null && user.getStaffId() != null)
                    ? user.getStaffId()
                    : dto.getStaffId();
            if (StringNormalizer.isBlank(staffId)) {
                return Uni.createFrom().failure(
                        new IllegalArgumentException("Cannot resolve the claimant (staffId)"));
            }

            LocalDateTime now = LocalDateTime.now();
            StaffClaim h = new StaffClaim();
            h.setStaffId(StringNormalizer.truncate(staffId, LEN_STAFF_ID));
            h.setClaimPeriod(StringNormalizer.truncate(dto.getClaimPeriod(), LEN_PERIOD));
            h.setClaimAmount(BigDecimal.ZERO);
            h.setStatus(STATUS_DRAFT);
            h.setEntryStaff(h.getStaffId());
            h.setEntryDate(now);
            h.setLastEditStaff(h.getStaffId());
            h.setLastEditDate(now);

            return Panache.withTransaction(() -> headerRepo.save(h))
                    .map(saved -> toHeaderDto(saved, null));
        });
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
            List<Long> ids = headers.stream().map(StaffClaim::getUniqId).toList();
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
            Panache.withTransaction(() -> detailRepo.deleteById(lineId))
                .flatMap(deleted -> {
                    if (Boolean.FALSE.equals(deleted)) {
                        return Uni.createFrom().failure(
                                new NotFoundException("Line " + lineId + " not found"));
                    }
                    return recalcTotal(headerId).replaceWithVoid();
                })
        );
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
            LocalDateTime now = LocalDateTime.now();

            return Panache.withTransaction(() -> headerRepo.findById(headerId).flatMap(h -> {
                if (h == null) {
                    return Uni.createFrom().failure(
                            new NotFoundException("Claim " + headerId + " not found"));
                }
                if (!STATUS_DRAFT.equals(h.getStatus())) {
                    return Uni.createFrom().failure(new IllegalArgumentException(
                            "Only a DRAFT claim can be submitted (current status: " + h.getStatus() + ")"));
                }
                h.setClaimAmount(total);
                h.setStatus(STATUS_SUBMITTED);
                h.setSubmittedDate(now);
                h.setLastEditDate(now);
                return headerRepo.update(h);
            })).map(saved -> toHeaderDto(saved, null));
        });
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
                h.setLastEditDate(LocalDateTime.now());
                return headerRepo.update(h);
            }));
        });
    }

    private BigDecimal sumClaimAmount(List<StaffClaimDetail> lines) {
        return lines.stream()
                .map(l -> l.getClaimAmount() == null ? BigDecimal.ZERO : l.getClaimAmount())
                .reduce(BigDecimal.ZERO, BigDecimal::add);
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
