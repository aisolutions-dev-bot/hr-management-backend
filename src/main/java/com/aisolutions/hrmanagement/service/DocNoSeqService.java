package com.aisolutions.hrmanagement.service;

import java.time.YearMonth;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import com.aisolutions.hrmanagement.repository.DocNoSeqRepository;
import com.aisolutions.hrmanagement.repository.DocNoSeqRepository.Bucket;
import com.aisolutions.hrmanagement.repository.DocNoSeqRepository.SeqDef;
import io.smallrye.mutiny.Uni;
import io.vertx.mutiny.sqlclient.SqlClient;

/**
 * Generates document numbers from {@code m01DocNoSeq} / {@code m01DocNoSeqDet}, e.g. the claim
 * seeded as mod18 / 'Claim Application' / CLMD / Monthly gives {@code CLMD-YYYYMM-000001}.
 * The definition row is locked until the caller's transaction ends, so concurrent submits
 * cannot receive the same number, and a rolled-back document does not burn a value.
 */
@ApplicationScoped
public class DocNoSeqService {

    private static final DocType CLAIM = new DocType("mod18", "Claim Application");

    /** Which sequence definition to use: the module and screen it is seeded under. */
    private record DocType(String moduleId, String screenType) {}

    @Inject
    DocNoSeqRepository docNoSeqRepo;

    /** Reserves and formats the next claim number for the given month. */
    public Uni<String> nextClaimNo(SqlClient tx, YearMonth period, String actor) {
        return next(tx, CLAIM, period, actor);
    }

    private Uni<String> next(SqlClient tx, DocType type, YearMonth period, String actor) {
        return docNoSeqRepo
                .findDefinitionForUpdate(tx, type.moduleId(), type.screenType())
                .flatMap(def -> {
                    if (def == null) {
                        return Uni.createFrom()
                                .failure(new IllegalStateException("Document number sequence is not configured for "
                                        + type.screenType() + " (" + type.moduleId() + ")."));
                    }
                    String yearKey = isContinuous(def) ? "" : String.valueOf(period.getYear());
                    String monthKey = isMonthly(def) ? String.format("%02d", period.getMonthValue()) : "";
                    Bucket bucket = new Bucket(def.uniqId(), yearKey, monthKey);
                    return docNoSeqRepo.findLastSeq(tx, bucket).flatMap(last -> {
                        long next = last + 1;
                        return docNoSeqRepo.saveSeq(tx, bucket, next, actor).replaceWith(format(def, period, next));
                    });
                });
    }

    private String format(SeqDef def, YearMonth period, long seq) {
        String seqStr = String.format("%0" + def.digit() + "d", seq);
        String numberPart;
        if (isMonthly(def)) {
            numberPart = String.format("%d%02d", period.getYear(), period.getMonthValue());
        } else if (isContinuous(def)) {
            numberPart = "";
        } else {
            numberPart = String.valueOf(period.getYear());
        }
        return numberPart.isEmpty() ? def.prefix() + "-" + seqStr : def.prefix() + "-" + numberPart + "-" + seqStr;
    }

    private boolean isMonthly(SeqDef def) {
        return "Monthly".equalsIgnoreCase(def.format());
    }

    private boolean isContinuous(SeqDef def) {
        return def.format() == null
                || "Continuous".equalsIgnoreCase(def.format())
                || "Continuously".equalsIgnoreCase(def.format());
    }
}
