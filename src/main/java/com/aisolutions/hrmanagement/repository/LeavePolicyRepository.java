package com.aisolutions.hrmanagement.repository;

import com.aisolutions.hrmanagement.service.leave.LeaveEntitlementCalculator.Policy;

import io.smallrye.mutiny.Uni;
import io.vertx.mutiny.sqlclient.Row;
import io.vertx.mutiny.sqlclient.SqlClient;
import jakarta.enterprise.context.ApplicationScoped;
import lombok.extern.slf4j.Slf4j;

/**
 * Raw SqlClient repository for m01LeavePolicy — the per-company leave policy
 * (leave-year basis, pro-ration allocation type, eligibility gate, rounding).
 * Read-only here; one row per tenant.
 *
 * Fail-safe: any error or a missing row yields the defaults, so a company that has
 * not been seeded still resolves a sensible policy rather than failing.
 */
@ApplicationScoped
@Slf4j
public class LeavePolicyRepository {

    public Uni<Policy> findPolicy(SqlClient client) {
        return client.preparedQuery(
                "SELECT LeaveYearBasis, AllocationType, EligibilityMonths, RoundingMode "
                        + "FROM m01LeavePolicy ORDER BY UniqId ASC LIMIT 1")
            .execute()
            .map(rows -> {
                if (!rows.iterator().hasNext()) return Policy.defaults();
                Row r = rows.iterator().next();
                Policy d = Policy.defaults();
                String basis = r.getString("LeaveYearBasis");
                String allocation = r.getString("AllocationType");
                Integer months = r.getInteger("EligibilityMonths");
                String rounding = r.getString("RoundingMode");
                return new Policy(
                        basis == null || basis.isBlank() ? d.leaveYearBasis() : basis,
                        allocation == null || allocation.isBlank() ? d.allocationType() : allocation,
                        months == null ? d.eligibilityMonths() : months,
                        rounding == null || rounding.isBlank() ? d.roundingMode() : rounding);
            })
            .onFailure().recoverWithItem(() -> {
                log.warn("Falling back to default leave policy — m01LeavePolicy read failed");
                return Policy.defaults();
            });
    }
}
