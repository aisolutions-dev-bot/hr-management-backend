package com.aisolutions.hrmanagement.service.staffclaim;

import com.aisolutions.hrmanagement.dto.StaffClaimDetailDTO;
import com.aisolutions.hrmanagement.entity.StaffClaimDetail;
import com.aisolutions.hrmanagement.repository.StaffClaimDetailRepository;
import com.aisolutions.hrmanagement.service.CurrencyService;
import com.aisolutions.hrmanagement.service.CurrentUserService;
import com.aisolutions.hrmanagement.service.attachment.AttachmentService;
import com.aisolutions.hrmanagement.util.StringNormalizer;
import com.aisolutions.shared.tenancy.CompanyPoolManager;
import com.aisolutions.shared.util.DateUtil;

import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.List;

/**
 * Handles staff claim detail creation and retrieval.
 *
 * Saving flow:
 *   1. Validate + truncate fields to fit m18StaffClaimsDet VARCHAR(25) constraints
 *   2. Persist claim record via CompanyPoolManager-pooled transaction
 *   3. If a photo was provided, upload it via AttachmentService with
 *      moduleType="CLAIM" and referenceCode={claim.UniqId}.
 *      AttachmentService internally uses FTPStorageService for the actual
 *      FTP transfer and records metadata in m10Attachments.
 *
 * Retrieval flow:
 *   Photos are retrieved via GET /api/v1/attachments?moduleType=CLAIM
 *   &referenceCode={claimId}, or via the convenience /staff-claims-det/{id}/photo.
 */
@ApplicationScoped
public class StaffClaimDetailService {

    public static final String MODULE_TYPE = "CLAIM";

    // m18StaffClaimsDet column lengths — truncate inputs to match DDL
    private static final int LEN_STAFF_ID = 25;
    private static final int LEN_PROJECT_ID = 25;
    private static final int LEN_CLAIM_TYPE = 25;
    private static final int LEN_DESCRIPTION = 100;
    private static final int LEN_MERCHANT_NAME = 25;
    private static final int LEN_RECEIPT_NUMBER = 25;
    private static final int LEN_CURRENCY = 10;

    @Inject StaffClaimDetailRepository claimRepo;
    @Inject AttachmentService attachmentService;
    @Inject CurrentUserService currentUserService;
    @Inject CurrencyService currencyService;
    @Inject CompanyPoolManager companyPoolManager;

    // ─────────────────────────────────────────────────────────
    //  CREATE
    // ─────────────────────────────────────────────────────────

    public Uni<StaffClaimDetailDTO> createClaim(
            StaffClaimDetailDTO dto,
            byte[] photoData,
            String photoFileName,
            String photoContentType) {

        validate(dto);

        // The user enters the Original Amount in the receipt's currency; it maps to
        // ReceiptAmount. (An older client that sends only claimAmount is treated as
        // entering the amount already in base — currency falls through to base below.)
        BigDecimal originalAmount = dto.getReceiptAmount() != null
                ? dto.getReceiptAmount() : dto.getClaimAmount();

        return currentUserService.getCurrentUser()
            .onItem().transformToUni(user -> {
                String staffId = (user != null && user.getStaffId() != null)
                        ? user.getStaffId()
                        : dto.getStaffId();

                // 0. Convert to base server-side from m01Currency/m01CurrencyDet —
                //    never trusting a client-sent rate.
                return currencyService.toBase(originalAmount, dto.getCurrency(), resolveRateDate(dto))
                    .flatMap(conv ->
                    // 1. Persist the claim (in transaction)
                    companyPoolManager.poolFor(currentUserService.getCurrentCompanyId())
                        .flatMap(pool -> pool.withTransaction(tx ->
                            claimRepo.save(tx, buildEntity(dto, staffId, originalAmount, conv))
                        ))
                    // 2. Upload photo to FTP (separate call — AttachmentService runs its own transaction)
                    .flatMap(saved -> {
                        if (photoData == null || photoData.length == 0) {
                            return Uni.createFrom().item(toDto(saved, null, null));
                        }
                        return attachmentService.uploadFile(
                                MODULE_TYPE,
                                String.valueOf(saved.getUniqId()),
                                photoFileName != null ? photoFileName : "receipt.jpg",
                                photoContentType != null ? photoContentType : "image/jpeg",
                                photoData
                            )
                            .map(att -> toDto(saved, att.getUniqId(), att.getFilePath()))
                            .onFailure().recoverWithItem(err -> {
                                System.err.println("[StaffClaimDetail] FTP upload failed for claim "
                                        + saved.getUniqId() + ": " + err.getMessage());
                                return toDto(saved, null, null);
                            });
                    })
                );
            });
    }

    // ─────────────────────────────────────────────────────────
    //  EDIT (rejected-receipt fix)
    // ─────────────────────────────────────────────────────────

    /**
     * Converts a rejected-receipt edit's (editable) amount to base. Rate date = the new
     * receipt date, else the locked claim date, else today.
     */
    public Uni<CurrencyService.Converted> convertForEdit(StaffClaimDetailDTO dto,
                                                         LocalDateTime lockedClaimDate) {
        if (dto == null) throw new IllegalArgumentException("Claim data is required");
        BigDecimal originalAmount = dto.getReceiptAmount() != null
                ? dto.getReceiptAmount() : dto.getClaimAmount();
        if (originalAmount == null || originalAmount.signum() <= 0)
            throw new IllegalArgumentException("Amount must be greater than zero");
        LocalDate rateDate = dto.getReceiptDate() != null ? dto.getReceiptDate().toLocalDate()
                : (lockedClaimDate != null ? lockedClaimDate.toLocalDate()
                                           : DateUtil.nowSGT().toLocalDate());
        return currencyService.toBase(originalAmount, dto.getCurrency(), rateDate);
    }

    /**
     * Overwrites the editable fields of a rejected-receipt fix and the re-converted amount.
     * Locked (kept from the original): Project, Claim Type, Description, Claim Date.
     */
    public void applyEditedFields(StaffClaimDetail e, StaffClaimDetailDTO dto,
                                  CurrencyService.Converted conv) {
        BigDecimal originalAmount = dto.getReceiptAmount() != null
                ? dto.getReceiptAmount() : dto.getClaimAmount();
        e.setMerchantName(StringNormalizer.truncate(dto.getMerchantName(), LEN_MERCHANT_NAME));
        e.setReceiptNumber(StringNormalizer.truncate(dto.getReceiptNumber(), LEN_RECEIPT_NUMBER));
        e.setReceiptDate(dto.getReceiptDate());
        e.setReceiptAmount(originalAmount);
        e.setClaimAmount(conv.baseAmount());
        e.setCurrency(StringNormalizer.truncate(conv.currencyCode(), LEN_CURRENCY));
        e.setDetectedCurrency(StringNormalizer.truncate(dto.getDetectedCurrency(), LEN_CURRENCY));
        e.setExchangeRate(conv.rateUsed());
    }

    // ─────────────────────────────────────────────────────────
    //  READ
    // ─────────────────────────────────────────────────────────

    public Uni<StaffClaimDetailDTO> getById(Long id) {
        return companyPoolManager.poolFor(currentUserService.getCurrentCompanyId()).flatMap(pool ->
            claimRepo.findById(pool, id)
                .map(e -> e == null ? null : toDto(e, null, null)));
    }

    public Uni<List<StaffClaimDetailDTO>> listByStaff(String staffId) {
        return companyPoolManager.poolFor(currentUserService.getCurrentCompanyId()).flatMap(pool ->
            claimRepo.findByStaff(pool, staffId)
                .map(list -> list.stream().map(e -> toDto(e, null, null)).toList()));
    }

    public Uni<List<StaffClaimDetailDTO>> listCurrentMonth(String staffId) {
        // Current month in SGT, not the server zone.
        YearMonth ym = YearMonth.from(DateUtil.nowSGT());
        LocalDateTime from = ym.atDay(1).atStartOfDay();
        LocalDateTime to = ym.plusMonths(1).atDay(1).atStartOfDay();
        return companyPoolManager.poolFor(currentUserService.getCurrentCompanyId()).flatMap(pool ->
            claimRepo.findByStaffAndDateRange(pool, staffId, from, to)
                .map(list -> list.stream().map(e -> toDto(e, null, null)).toList()));
    }

    // ─────────────────────────────────────────────────────────
    //  HELPERS
    // ─────────────────────────────────────────────────────────

    private void validate(StaffClaimDetailDTO dto) {
        if (dto == null) throw new IllegalArgumentException("Claim data is required");
        if (StringNormalizer.isBlank(dto.getStaffId()))
            throw new IllegalArgumentException("Staff ID is required");
        if (StringNormalizer.isBlank(dto.getProjectId()))
            throw new IllegalArgumentException("Project ID is required");
        if (StringNormalizer.isBlank(dto.getClaimType()))
            throw new IllegalArgumentException("Claim Type is required");
        if (dto.getClaimDate() == null)
            throw new IllegalArgumentException("Claim Date is required");
        BigDecimal original = dto.getReceiptAmount() != null
                ? dto.getReceiptAmount() : dto.getClaimAmount();
        if (original == null || original.signum() <= 0)
            throw new IllegalArgumentException("Amount must be greater than zero");
    }

    /**
     * The date whose month picks the exchange rate — the receipt date (when the money
     * was spent), else the claim date, else today (SGT).
     */
    private LocalDate resolveRateDate(StaffClaimDetailDTO dto) {
        if (dto.getReceiptDate() != null) return dto.getReceiptDate().toLocalDate();
        if (dto.getClaimDate() != null)   return dto.getClaimDate().toLocalDate();
        return DateUtil.nowSGT().toLocalDate();
    }

    private StaffClaimDetail buildEntity(StaffClaimDetailDTO dto, String staffIdOverride,
                                         BigDecimal originalAmount, CurrencyService.Converted conv) {
        StaffClaimDetail e = new StaffClaimDetail();
        LocalDateTime now = DateUtil.nowSGT();

        e.setStaffId(StringNormalizer.truncate(
                staffIdOverride != null ? staffIdOverride : dto.getStaffId(), LEN_STAFF_ID));
        e.setProjectId(StringNormalizer.truncate(dto.getProjectId(), LEN_PROJECT_ID));
        e.setClaimType(StringNormalizer.truncate(dto.getClaimType(), LEN_CLAIM_TYPE));
        e.setClaimDate(dto.getClaimDate());
        e.setClaimDescription(StringNormalizer.truncate(dto.getClaimDescription(), LEN_DESCRIPTION));

        e.setMerchantName(StringNormalizer.truncate(dto.getMerchantName(), LEN_MERCHANT_NAME));
        e.setReceiptNumber(StringNormalizer.truncate(dto.getReceiptNumber(), LEN_RECEIPT_NUMBER));
        e.setReceiptDate(dto.getReceiptDate());

        // ReceiptAmount = the "Original Amount" the user entered, in the receipt currency.
        // ClaimAmount   = that amount converted to base, which is what the header total and
        //                 the approver see. Currency + ExchangeRate record how it was
        //                 converted, so the value is reproducible and frozen against later
        //                 rate changes — all four computed server-side by CurrencyService.
        e.setReceiptAmount(originalAmount);
        e.setClaimAmount(conv.baseAmount());
        e.setCurrency(StringNormalizer.truncate(conv.currencyCode(), LEN_CURRENCY));
        e.setDetectedCurrency(StringNormalizer.truncate(dto.getDetectedCurrency(), LEN_CURRENCY));
        e.setExchangeRate(conv.rateUsed());

        // Link to the claim header + initial itemised-approval status
        e.setClaimId(dto.getClaimId());
        e.setStatus("PENDING");

        e.setEntryStaff(e.getStaffId());
        e.setEntryDate(now);
        e.setLastEditStaff(e.getStaffId());
        e.setLastEditDate(now);
        return e;
    }

    /** Public mapper used by {@link StaffClaimService} to render a claim's line items (no attachment lookup). */
    public StaffClaimDetailDTO toDtoBasic(StaffClaimDetail e) {
        return toDto(e, null, null);
    }

    private StaffClaimDetailDTO toDto(StaffClaimDetail e, Long attachmentId, String attachmentPath) {
        StaffClaimDetailDTO dto = new StaffClaimDetailDTO();
        dto.setUniqId(e.getUniqId());
        dto.setClaimId(e.getClaimId());
        dto.setStaffId(e.getStaffId());
        dto.setProjectId(e.getProjectId());
        dto.setClaimType(e.getClaimType());
        dto.setClaimDate(e.getClaimDate());
        dto.setClaimDescription(e.getClaimDescription());
        dto.setMerchantName(e.getMerchantName());
        dto.setReceiptNumber(e.getReceiptNumber());
        dto.setReceiptDate(e.getReceiptDate());
        dto.setReceiptAmount(e.getReceiptAmount());
        dto.setClaimAmount(e.getClaimAmount());
        dto.setCurrency(e.getCurrency());
        dto.setDetectedCurrency(e.getDetectedCurrency());
        dto.setExchangeRate(e.getExchangeRate());
        dto.setStatus(e.getStatus());
        dto.setApprovedBy(e.getApprovedBy());
        dto.setApprovedDate(e.getApprovedDate());
        dto.setRejectReason(e.getRejectReason());
        dto.setEntryStaff(e.getEntryStaff());
        dto.setEntryDate(e.getEntryDate());
        dto.setLastEditStaff(e.getLastEditStaff());
        dto.setLastEditDate(e.getLastEditDate());
        dto.setAttachmentId(attachmentId);
        dto.setAttachmentPath(attachmentPath);
        return dto;
    }
}
