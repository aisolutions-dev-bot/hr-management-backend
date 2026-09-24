package com.aisolutions.hrmanagement.service.leave;

import com.aisolutions.hrmanagement.entity.LeaveTypeEntitlement;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * Country-agnostic leave-entitlement engine. Given a leave type's configured pro-ration
 * method, its entitlement rows, and the company's leave policy, it derives the suggested
 * entitled days.
 *
 * <p>It produces the pre-assignment estimate a staff member sees for a leave type that has no
 * HR-assigned ledger grant yet, computed from the same policy + entitlement data HR uses to
 * assign the grant, so the estimate matches what is later granted. A ledger grant, once
 * present, is authoritative and overrides this estimate.
 *
 * <p>No country's rules are hard-coded here — only the building blocks the configuration
 * selects: a linear pro-ration of a service-year band, a service-month step table, or a
 * plain service-year band. A country (Singapore, Malaysia, …) is expressed entirely as
 * data: {@code m01LeaveType.ProRateMethod}, the {@code m01LeaveTypeEntitlement} rows, and
 * the {@code m01LeavePolicy} row.
 */
public final class LeaveEntitlementCalculator {

    private LeaveEntitlementCalculator() {}

    public static final String METHOD_NONE   = "NONE";
    public static final String METHOD_LINEAR = "LINEAR";
    public static final String METHOD_STEP   = "STEP";

    public static final String UNIT_YEAR  = "YEAR";
    public static final String UNIT_MONTH = "MONTH";

    // Leave-year reset basis. CALENDAR is assumed by the maths today; ANNIVERSARY is a reserved
    // value on the policy column (per-staff reset not yet wired) kept for future flexibility.
    public static final String BASIS_CALENDAR    = "CALENDAR";
    public static final String BASIS_ANNIVERSARY = "ANNIVERSARY";

    // How a pro-rated year is allocated (the active toggle).
    public static final String ALLOC_FORWARD_CREDIT = "FORWARD_CREDIT"; // whole year credited up front (count to 31 Dec)
    public static final String ALLOC_ACCRUED        = "ACCRUED";        // earned so far this year (count to today)

    public static final String ROUND_NEAREST = "NEAREST";
    public static final String ROUND_UP      = "UP";
    public static final String ROUND_DOWN    = "DOWN";

    /** Company-level leave policy (the {@code m01LeavePolicy} row). */
    public record Policy(String leaveYearBasis, String allocationType, int eligibilityMonths, String roundingMode) {
        public static Policy defaults() {
            return new Policy(BASIS_CALENDAR, ALLOC_ACCRUED, 3, ROUND_NEAREST);
        }
    }

    /** The computed suggestion: the days (null when service is unknown), plus a human note. */
    public record Suggestion(BigDecimal days, Integer serviceYears, boolean entitlementKnown, String note) {}

    /**
     * Suggested entitled days for a staff / leave type / leave year.
     *
     * @param method   the type's ProRateMethod (NONE | LINEAR | STEP)
     * @param rows     the type's entitlement rows (YEAR ladder and/or MONTH steps), any order
     * @param joinDate the staff's join date, or null when unknown
     * @param leaveYear the leave year being granted
     * @param today    the current date (used by ACCRUED and by a plain NONE band lookup)
     * @param policy   the company leave policy
     */
    public static Suggestion compute(String method, List<LeaveTypeEntitlement> rows,
                                     LocalDate joinDate, int leaveYear, LocalDate today, Policy policy) {
        Policy p = policy != null ? policy : Policy.defaults();
        String m = method != null ? method : METHOD_NONE;
        if (METHOD_LINEAR.equals(m)) {
            return linear(rows, joinDate, leaveYear, today, p);
        }
        if (METHOD_STEP.equals(m)) {
            return step(rows, joinDate, leaveYear, today, p);
        }
        return none(rows, joinDate, today);
    }

    /**
     * LINEAR pro-ration on a CALENDAR leave year (Jan–Dec): the band for the staff's completed
     * years of service, pro-rated by the completed months of service that fall inside the leave
     * year. How far the count runs is the policy's allocation type:
     * <ul>
     *   <li>{@code FORWARD_CREDIT} — count to 31 Dec: the whole year is credited up front
     *       (a 1 Apr joiner on a 7-day band ≈ 5 days).</li>
     *   <li>{@code ACCRUED} — count only up to today: earned so far this year
     *       (the same joiner in ~September ≈ 3 days).</li>
     * </ul>
     */
    private static Suggestion linear(List<LeaveTypeEntitlement> rows, LocalDate join, int leaveYear,
                                     LocalDate today, Policy p) {
        if (join == null) {
            return new Suggestion(null, null, false, null);
        }
        List<LeaveTypeEntitlement> yearRows = unit(rows, UNIT_YEAR);
        LocalDate yearStart = LocalDate.of(leaveYear, 1, 1);
        LocalDate yearEndExclusive = LocalDate.of(leaveYear + 1, 1, 1);

        // ACCRUED stops the count at today (never past the year end); FORWARD_CREDIT runs to 31 Dec.
        LocalDate countTo = ALLOC_ACCRUED.equals(p.allocationType()) && today != null && today.isBefore(yearEndExclusive)
                ? today
                : yearEndExclusive;

        int totalServiceMonths = completedMonths(join, countTo);
        int years = totalServiceMonths / 12;

        if (totalServiceMonths < p.eligibilityMonths()) {
            return new Suggestion(zero(), years, true,
                    "Under " + p.eligibilityMonths() + " months' service — not yet eligible.");
        }

        int band = bandFor(yearRows, years);
        if (band == 0) {
            band = firstBandDays(yearRows); // first partial year (below the first band) pro-rates the first band
        }
        LocalDate monthsFrom = join.isAfter(yearStart) ? join : yearStart;
        int monthsInYear = Math.max(0, Math.min(12, completedMonths(monthsFrom, countTo)));
        BigDecimal raw = BigDecimal.valueOf((long) band * monthsInYear)
                .divide(BigDecimal.valueOf(12), 4, RoundingMode.HALF_UP);
        BigDecimal days = roundDays(raw, p.roundingMode());
        String note = monthsInYear >= 12
                ? "Full year: " + band + " days."
                : "Pro-rated: " + monthsInYear + "/12 × " + band + " = " + days.toPlainString() + " days.";
        return new Suggestion(days, years, true, note);
    }

    /**
     * Days from the highest month-of-service step reached (no further pro-ration). Like LINEAR,
     * how far service is counted follows the allocation type: ACCRUED counts the months completed
     * as of today (the step earned so far), FORWARD_CREDIT the months completed by 31 Dec.
     */
    private static Suggestion step(List<LeaveTypeEntitlement> rows, LocalDate join, int leaveYear,
                                   LocalDate today, Policy p) {
        if (join == null) {
            return new Suggestion(null, null, false, null);
        }
        List<LeaveTypeEntitlement> monthRows = unit(rows, UNIT_MONTH);
        LocalDate yearEndExclusive = LocalDate.of(leaveYear + 1, 1, 1);
        LocalDate countTo = ALLOC_ACCRUED.equals(p.allocationType()) && today != null && today.isBefore(yearEndExclusive)
                ? today
                : yearEndExclusive;
        int totalServiceMonths = completedMonths(join, countTo);
        int years = totalServiceMonths / 12;
        int days = bandFor(monthRows, totalServiceMonths);
        String note = days == 0
                ? "Under the first service step (" + totalServiceMonths + " months) — no entitlement yet."
                : "At " + totalServiceMonths + " months of service: " + days + " days.";
        return new Suggestion(BigDecimal.valueOf(days).setScale(1), years, true, note);
    }

    /** Plain service-year band as of today — the pre-pro-ration behaviour, unchanged. */
    private static Suggestion none(List<LeaveTypeEntitlement> rows, LocalDate join, LocalDate today) {
        if (join == null) {
            return new Suggestion(null, null, false, null);
        }
        int years = join.isAfter(today) ? 0 : (int) ChronoUnit.YEARS.between(join, today);
        int band = bandFor(unit(rows, UNIT_YEAR), years);
        return new Suggestion(BigDecimal.valueOf(band).setScale(1), years, true, null);
    }

    // ─────────────────────────────────────────────────────────
    //  MATH HELPERS
    // ─────────────────────────────────────────────────────────

    /** Completed whole months of service from the join day to an exclusive reference date. */
    public static int completedMonths(LocalDate join, LocalDate refExclusive) {
        if (join == null || !join.isBefore(refExclusive)) return 0;
        return (int) ChronoUnit.MONTHS.between(join, refExclusive);
    }

    /** Days of the highest row whose threshold <= value; 0 when below the first row or none exist. */
    private static int bandFor(List<LeaveTypeEntitlement> rows, int value) {
        int days = 0;
        int best = Integer.MIN_VALUE;
        for (LeaveTypeEntitlement r : rows) {
            Integer threshold = r.getYearOfService();
            if (threshold != null && threshold <= value && threshold > best) {
                best = threshold;
                days = r.getDaysOfLeave() != null ? r.getDaysOfLeave() : days;
            }
        }
        return days;
    }

    /** Days of the lowest-threshold row (the first band), or 0 when there are none. */
    private static int firstBandDays(List<LeaveTypeEntitlement> rows) {
        int days = 0;
        int best = Integer.MAX_VALUE;
        for (LeaveTypeEntitlement r : rows) {
            Integer threshold = r.getYearOfService();
            if (threshold != null && threshold < best) {
                best = threshold;
                days = r.getDaysOfLeave() != null ? r.getDaysOfLeave() : 0;
            }
        }
        return days;
    }

    /** Rows matching a threshold unit; a null unit is read as YEAR (the column default). */
    private static List<LeaveTypeEntitlement> unit(List<LeaveTypeEntitlement> rows, String u) {
        if (rows == null) return List.of();
        return rows.stream()
                .filter(r -> u.equalsIgnoreCase(r.getUnitType() == null ? UNIT_YEAR : r.getUnitType()))
                .toList();
    }

    /** Round to whole days per the policy: NEAREST (0.5 up), UP, or DOWN. Scale 1 for display. */
    private static BigDecimal roundDays(BigDecimal raw, String mode) {
        RoundingMode rm = switch (mode == null ? ROUND_NEAREST : mode) {
            case ROUND_UP -> RoundingMode.CEILING;
            case ROUND_DOWN -> RoundingMode.FLOOR;
            default -> RoundingMode.HALF_UP;
        };
        return raw.setScale(0, rm).setScale(1);
    }

    private static BigDecimal zero() {
        return BigDecimal.ZERO.setScale(1);
    }
}
