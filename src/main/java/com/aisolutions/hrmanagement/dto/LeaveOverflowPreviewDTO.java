package com.aisolutions.hrmanagement.dto;

import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * Apply-form preview of how a leave application would be funded.
 * The wizard calls this when the requested days may exceed the paid balance, shows the
 * split, and asks the staff to confirm before submitting with {@code overflowConfirmed}.
 */
@Data
@NoArgsConstructor
public class LeaveOverflowPreviewDTO {

    private String leaveType;
    private BigDecimal totalDays;
    private BigDecimal remainingDays;   // paid balance before this application (may be negative)

    private BigDecimal paidDays;
    private BigDecimal advanceDays;
    private BigDecimal unpaidDays;

    private boolean allowAdvance;       // whether the company permits advanced leave
    private BigDecimal advanceMaxDays;  // the advance ceiling in effect

    /** True when any day is not covered by the paid balance (advance or unpaid > 0). */
    private boolean hasOverflow;
    /** True when the staff must confirm before the application can be submitted. */
    private boolean requiresConfirm;
    /** A human-readable explanation of the split for the confirm dialog. */
    private String message;
}
