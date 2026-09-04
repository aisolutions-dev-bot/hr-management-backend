package com.aisolutions.hrmanagement.service.leave;

import com.aisolutions.hrmanagement.dto.LeaveLedgerRow;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Turns a staff member's ledger rows into a leave balance for a target year, honouring
 * carry-forward, date-based expiry and an optional per-company carry cap.
 *
 * Model: GRANT/CARRY_FORWARD rows are buckets (days available from {@code txnDate} until
 * {@code expiryDate}); TAKEN/ADJUSTMENT rows are consumption, netted per source leave (a
 * cancelled leave's TAKEN and its reversal cancel out). Consumption is drawn FIFO —
 * soonest-to-expire bucket first — respecting each bucket's validity window. The simulation
 * runs year by year so the cap can clamp what carries into each year (the excess lapses),
 * and a bucket's leftover lapses once its expiry has passed.
 */
public final class LeaveBalanceCalculator {

    private LeaveBalanceCalculator() {}

    /** The computed position for one leave type in the target year. {@code hasGrant} is false
     *  when the staff has no ledger buckets — the caller then falls back to the live ladder. */
    public record Result(
            boolean hasGrant,
            BigDecimal entitled,        // this year's grant (annual entitlement)
            BigDecimal broughtForward,  // still-available days carried in from prior years
            BigDecimal approved,        // days consumed during the target year
            BigDecimal available,       // total available now (before pending): broughtForward + this year − used
            Integer serviceYears,       // snapshot from the year's grant, may be null
            BigDecimal expiring,        // available days that expire by the end of the target year
            LocalDate nextExpiry,       // soonest upcoming expiry among still-available buckets
            BigDecimal lapsed) {}       // days already lost to expiry/cap

    private static final class Bucket {
        final int originYear;
        final LocalDate start;
        final LocalDate expiry;   // nullable = never expires
        BigDecimal remaining;
        Bucket(int originYear, LocalDate start, LocalDate expiry, BigDecimal days) {
            this.originYear = originYear; this.start = start; this.expiry = expiry; this.remaining = days;
        }
    }

    private static final class Consumption {
        LocalDate date;
        BigDecimal net = BigDecimal.ZERO;   // TAKEN negative, reversal positive
    }

    private record ConsEvent(LocalDate date, BigDecimal amount) {}   // amount > 0

    public static Result compute(List<LeaveLedgerRow> rows, Integer cap, int year, LocalDate today) {
        List<Bucket> buckets = new ArrayList<>();
        Map<String, Consumption> byRef = new LinkedHashMap<>();
        BigDecimal entitledThisYear = BigDecimal.ZERO;
        Integer serviceYears = null;

        for (LeaveLedgerRow r : rows) {
            String t = r.txnType();
            if ("GRANT".equals(t) || "CARRY_FORWARD".equals(t)) {
                LocalDate start = r.txnDate() != null ? r.txnDate() : LocalDate.of(r.originYear(), 1, 1);
                buckets.add(new Bucket(r.originYear(), start, r.expiryDate(), nz(r.days())));
                if (r.originYear() == year) {
                    entitledThisYear = entitledThisYear.add(nz(r.days()));
                    if (r.serviceYears() != null) serviceYears = r.serviceYears();
                }
            } else { // TAKEN / ADJUSTMENT / LAPSE / ENCASH — consumption, netted by source leave
                String ref = r.sourceRefId() != null ? r.sourceRefId() : ("@" + System.identityHashCode(r));
                Consumption c = byRef.computeIfAbsent(ref, k -> new Consumption());
                c.net = c.net.add(nz(r.days()));
                LocalDate d = r.txnDate() != null ? r.txnDate() : LocalDate.of(r.originYear(), 1, 1);
                if (c.date == null || d.isBefore(c.date)) c.date = d;
            }
        }

        List<ConsEvent> cons = new ArrayList<>();
        for (Consumption c : byRef.values()) {
            if (c.net.signum() < 0 && c.date != null) cons.add(new ConsEvent(c.date, c.net.negate()));
        }
        cons.sort(Comparator.comparing(ConsEvent::date));

        // Days consumed during the target year — reported even with no buckets (the ladder fallback).
        BigDecimal approvedThisYear = BigDecimal.ZERO;
        for (ConsEvent ce : cons) {
            if (ce.date.getYear() == year) approvedThisYear = approvedThisYear.add(ce.amount);
        }

        if (buckets.isEmpty()) {
            return new Result(false, null, null, approvedThisYear, null, serviceYears, null, null, null);
        }

        BigDecimal lapsed = BigDecimal.ZERO;
        BigDecimal overdraw = BigDecimal.ZERO;

        int minYear = year;
        for (Bucket b : buckets) minYear = Math.min(minYear, b.originYear);
        for (ConsEvent ce : cons) minYear = Math.min(minYear, ce.date.getYear());

        for (int y = minYear; y <= year; y++) {
            LocalDate startOfY = LocalDate.of(y, 1, 1);
            LocalDate endOfY = LocalDate.of(y, 12, 31);

            // (a) cap the carry-in from prior-year buckets at the start of the year
            if (cap != null && cap >= 0) {
                BigDecimal capBd = BigDecimal.valueOf(cap);
                BigDecimal carryIn = BigDecimal.ZERO;
                for (Bucket b : buckets) {
                    if (b.originYear < y && validDuring(b, startOfY, endOfY) && b.remaining.signum() > 0) {
                        carryIn = carryIn.add(b.remaining);
                    }
                }
                if (carryIn.compareTo(capBd) > 0) {
                    BigDecimal excess = carryIn.subtract(capBd);
                    List<Bucket> prior = new ArrayList<>();
                    for (Bucket b : buckets) {
                        if (b.originYear < y && validDuring(b, startOfY, endOfY) && b.remaining.signum() > 0) prior.add(b);
                    }
                    prior.sort(Comparator.comparingInt((Bucket b) -> b.originYear).thenComparing(b -> b.start));
                    for (Bucket b : prior) {
                        if (excess.signum() <= 0) break;
                        BigDecimal cut = b.remaining.min(excess);
                        b.remaining = b.remaining.subtract(cut);
                        excess = excess.subtract(cut);
                        lapsed = lapsed.add(cut);
                    }
                }
            }

            // (b) draw the year's consumption FIFO across valid buckets
            for (ConsEvent ce : cons) {
                if (ce.date.getYear() != y) continue;
                BigDecimal need = ce.amount;
                List<Bucket> valid = new ArrayList<>();
                for (Bucket b : buckets) {
                    if (!b.start.isAfter(ce.date) && (b.expiry == null || ce.date.isBefore(b.expiry))
                            && b.remaining.signum() > 0) {
                        valid.add(b);
                    }
                }
                valid.sort(FIFO);
                for (Bucket b : valid) {
                    if (need.signum() <= 0) break;
                    BigDecimal draw = b.remaining.min(need);
                    b.remaining = b.remaining.subtract(draw);
                    need = need.subtract(draw);
                }
                if (need.signum() > 0) overdraw = overdraw.add(need);
            }

            // (c) lapse buckets whose expiry has passed (by end of year y, and not in the future)
            for (Bucket b : buckets) {
                if (b.expiry != null && b.remaining.signum() > 0
                        && !b.expiry.isAfter(endOfY) && !b.expiry.isAfter(today)) {
                    lapsed = lapsed.add(b.remaining);
                    b.remaining = BigDecimal.ZERO;
                }
            }
        }

        // Position for the target year
        LocalDate startOfYear = LocalDate.of(year, 1, 1);
        LocalDate endOfYear = LocalDate.of(year, 12, 31);
        BigDecimal broughtForward = BigDecimal.ZERO;
        BigDecimal thisYearRemaining = BigDecimal.ZERO;
        BigDecimal expiring = BigDecimal.ZERO;
        LocalDate nextExpiry = null;
        List<Bucket> active = new ArrayList<>();
        for (Bucket b : buckets) {
            if (b.remaining.signum() <= 0 || b.originYear > year || !validDuring(b, startOfYear, endOfYear)) continue;
            active.add(b);
            if (b.originYear < year) broughtForward = broughtForward.add(b.remaining);
            else thisYearRemaining = thisYearRemaining.add(b.remaining);
            if (b.expiry != null && (nextExpiry == null || b.expiry.isBefore(nextExpiry))) nextExpiry = b.expiry;
        }
        if (nextExpiry != null) {
            for (Bucket b : active) {
                if (nextExpiry.equals(b.expiry)) expiring = expiring.add(b.remaining);
            }
        }
        BigDecimal available = broughtForward.add(thisYearRemaining).subtract(overdraw);
        return new Result(true, entitledThisYear, broughtForward, approvedThisYear, available,
                serviceYears, expiring, nextExpiry, lapsed);
    }

    /** Soonest-to-expire first (never-expiring last), then oldest, so carried days are used first. */
    private static final Comparator<Bucket> FIFO =
            Comparator.comparing((Bucket b) -> b.expiry, Comparator.nullsLast(Comparator.naturalOrder()))
                    .thenComparingInt(b -> b.originYear)
                    .thenComparing(b -> b.start);

    /** A bucket is usable during a year if it had started by year end and not fully expired before year start. */
    private static boolean validDuring(Bucket b, LocalDate startOfY, LocalDate endOfY) {
        return !b.start.isAfter(endOfY) && (b.expiry == null || b.expiry.isAfter(startOfY));
    }

    private static BigDecimal nz(BigDecimal v) {
        return v != null ? v : BigDecimal.ZERO;
    }
}
