package com.aisolutions.hrmanagement.dto;

import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * A staff member's leave balance for one leave type in the current calendar year.
 *
 *   remainingDays = entitledDays − takenDays
 *
 * {@code entitlementKnown} is false when the balance could not be computed because the
 * staff member has no join date on record (so years of service — and thus entitlement —
 * is unknown). The UI warns rather than blocks in that case.
 */
@Data
@NoArgsConstructor
public class LeaveBalanceDTO {

    private String leaveType;
    private String leaveTypeDescription;
    private int year;                 // calendar year the balance applies to

    private Integer serviceYears;     // completed years of service, null when no join date
    private boolean entitlementKnown; // false = no join date → balance not verified

    private BigDecimal entitledDays;  // per-year entitlement from the matched band
    private BigDecimal takenDays;     // approved + pending days already booked this year
    private BigDecimal approvedDays;  // the approved slice of takenDays (used)
    private BigDecimal pendingDays;   // the still-pending slice of takenDays
    private BigDecimal remainingDays; // entitled − taken (may be negative)

    /** Human-readable note for the UI, e.g. "No join date on record — balance not verified". */
    private String message;
}
