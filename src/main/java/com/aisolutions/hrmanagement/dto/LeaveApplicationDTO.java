package com.aisolutions.hrmanagement.dto;

import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * Transport for a leave application across all four wizard steps and the list/detail views.
 * The approver name is resolved on read (for display); it is not stored.
 */
@Data
@NoArgsConstructor
public class LeaveApplicationDTO {

    private Long uniqId;

    // Step 1
    private String staffId;
    private String staffName;
    private String department;
    private LocalDateTime applicationDate;

    // Step 2
    private String leaveAction;   // APPLY | CANCEL
    private String leaveType;     // m01LeaveType code
    private String leaveTypeDescription; // resolved on read for display
    private String remarks;

    // Step 3 (APPLY)
    private LocalDate fromDate;
    private LocalDate toDate;
    private String halfDayPeriod; // AM | PM | null
    private BigDecimal totalDays;

    // Step 3 (CANCEL)
    private Long cancelRefId;

    // Step 4
    private String approverStaffId;
    private String approverName;  // resolved on read for display

    // Lifecycle
    private String status;
    private String approvedBy;
    private LocalDateTime approvedDate;
    private String rejectReason;

    private LocalDateTime entryDate;
    private LocalDateTime lastEditDate;
}
