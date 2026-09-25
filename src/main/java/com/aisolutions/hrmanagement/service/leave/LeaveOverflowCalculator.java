package com.aisolutions.hrmanagement.service.leave;

import java.math.BigDecimal;

/**
 * Splits an over-balance leave application into paid / advanced / unpaid days.
 * Pure and side-effect free so it can be unit-tested and reused by both the
 * apply-time preview and the authoritative submit path.
 *
 * <p>Model (design (a) — borrow against future entitlement): the paid balance may be
 * drawn down to {@code -advanceMaxDays} when advance leave is allowed; anything beyond
 * that becomes unpaid. So the days that draw the ledger (paid + advance) are capped at
 * {@code remaining + advanceMaxDays}, the paid portion is whatever positive balance
 * exists, and the advance portion is the borrowed remainder.
 *
 * <p>Invariant: {@code total = paid + advance + unpaid}, every part &ge; 0.
 */
public final class LeaveOverflowCalculator {

    private LeaveOverflowCalculator() {}

    /** The funding split of a leave application. {@code hasOverflow} is true when any day
     *  is not covered by the available paid balance (i.e. advance or unpaid is non-zero). */
    public record Split(BigDecimal paid, BigDecimal advance, BigDecimal unpaid, boolean hasOverflow) {}

    /**
     * @param total       total days requested (&ge; 0)
     * @param remaining   the current paid balance for the leave type (may be negative if already borrowed)
     * @param allowAdvance whether the company policy permits advanced leave
     * @param advanceMaxDays the furthest the paid balance may go negative (0 when advance is off)
     */
    public static Split compute(BigDecimal total, BigDecimal remaining,
                                boolean allowAdvance, BigDecimal advanceMaxDays) {
        BigDecimal t = nz(total);
        if (t.signum() <= 0) {
            return new Split(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, false);
        }
        BigDecimal bal = nz(remaining);
        BigDecimal cap = allowAdvance ? nz(advanceMaxDays).max(BigDecimal.ZERO) : BigDecimal.ZERO;

        // Days that may draw the ledger (paid + advance): balance plus the advance cap, clamped to [0, total].
        BigDecimal maxConsumable = bal.add(cap);
        BigDecimal consumable = clamp(maxConsumable, BigDecimal.ZERO, t);
        BigDecimal unpaid = t.subtract(consumable);

        // Paid is whatever positive balance covers; the rest of the consumable is borrowed (advance).
        BigDecimal positiveBalance = bal.max(BigDecimal.ZERO);
        BigDecimal paid = clamp(positiveBalance.min(consumable), BigDecimal.ZERO, consumable);
        BigDecimal advance = consumable.subtract(paid);

        boolean overflow = advance.signum() > 0 || unpaid.signum() > 0;
        return new Split(scale(paid), scale(advance), scale(unpaid), overflow);
    }

    private static BigDecimal clamp(BigDecimal v, BigDecimal lo, BigDecimal hi) {
        return v.max(lo).min(hi);
    }

    private static BigDecimal scale(BigDecimal v) {
        return v.setScale(1, java.math.RoundingMode.HALF_UP);
    }

    private static BigDecimal nz(BigDecimal v) {
        return v != null ? v : BigDecimal.ZERO;
    }
}
