package com.aisolutions.hrmanagement.service.staffclaim;

import com.aisolutions.hrmanagement.dto.StaffClaimDetailDTO;
import com.aisolutions.hrmanagement.repository.ProjectCodeRepository;
import io.smallrye.mutiny.Uni;
import io.vertx.mutiny.sqlclient.SqlClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Unit coverage of the Activity Code and Process Code rule for company-project receipts. */
@ExtendWith(MockitoExtension.class)
class ClaimProjectCodeGuardTest {

    private static final String PROJECT_CODE = "PRJ-TEST-005";

    @Mock
    ProjectCodeRepository projectCodeRepository;

    /** A company-project receipt without its codes is refused with the project named. */
    @Test
    void refusesCompanyProjectReceiptWithoutCodes() {
        stubProjectExists(true);

        IllegalArgumentException refusal = assertThrows(
                IllegalArgumentException.class,
                () -> guard().requireCodes(tx(), receipt(null, "PRC-1")).await().indefinitely());

        assertEquals("Activity Code and Process Code are required for project " + PROJECT_CODE, refusal.getMessage());
    }

    /** A blank code counts as missing, same as null. */
    @Test
    void refusesCompanyProjectReceiptWithBlankProcessCode() {
        stubProjectExists(true);

        assertThrows(
                IllegalArgumentException.class,
                () -> guard().requireCodes(tx(), receipt("ACT-1", "  ")).await().indefinitely());
    }

    /** A company-project receipt with both codes passes without a project lookup. */
    @Test
    void acceptsCompanyProjectReceiptWithBothCodes() {
        guard().requireCodes(tx(), receipt("ACT-1", "PRC-1")).await().indefinitely();

        verify(projectCodeRepository, never()).existsByCode(any(SqlClient.class), anyString());
    }

    /** A free-typed claim title that is not a project code needs no codes. */
    @Test
    void acceptsFreeTypedTitleWithoutCodes() {
        stubProjectExists(false);

        guard().requireCodes(tx(), receipt(null, null)).await().indefinitely();
    }

    private ClaimProjectCodeGuard guard() {
        ClaimProjectCodeGuard guard = new ClaimProjectCodeGuard();
        guard.projectCodeRepository = projectCodeRepository;
        return guard;
    }

    private void stubProjectExists(boolean exists) {
        when(projectCodeRepository.existsByCode(tx(), PROJECT_CODE))
                .thenReturn(Uni.createFrom().item(exists));
    }

    private static StaffClaimDetailDTO receipt(String activityCode, String processCode) {
        StaffClaimDetailDTO dto = new StaffClaimDetailDTO();
        dto.setProjectId(PROJECT_CODE);
        dto.setActivityCode(activityCode);
        dto.setProcessCode(processCode);
        return dto;
    }

    private static final SqlClient TX = mock(SqlClient.class);

    private static SqlClient tx() {
        return TX;
    }
}
