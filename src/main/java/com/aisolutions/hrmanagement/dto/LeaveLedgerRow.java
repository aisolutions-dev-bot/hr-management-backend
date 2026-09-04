package com.aisolutions.hrmanagement.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * One raw m18StaffLeaveLedger row, as the balance calculator needs it.
 *
 * GRANT / CARRY_FORWARD rows are buckets ({@code days} positive, with a {@code txnDate}
 * effective date and an optional {@code expiryDate}); TAKEN / ADJUSTMENT rows are
 * consumption movements ({@code days} signed, {@code txnDate} = the leave's From date).
 */
public record LeaveLedgerRow(
        String leaveType,
        String txnType,
        int originYear,
        LocalDate txnDate,
        BigDecimal days,
        LocalDate expiryDate,
        Integer serviceYears,
        String sourceRefId) {
}
