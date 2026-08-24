package com.aisolutions.hrmanagement.service.useractionlog;

import com.aisolutions.hrmanagement.repository.UserActionLogRepository;
import com.aisolutions.shared.tenancy.CompanyPoolManager;

import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

@ApplicationScoped
public class UserActionLogService {

  @Inject
  UserActionLogRepository auditLogRepository;

  @Inject
  CompanyPoolManager companyPoolManager;

  /** m07UserActionLog.Module values for HR Management actions. */
  public static class Module {
    public static final String STAFF_CLAIM = "STAFF-CLAIM";
    public static final String STAFF_LEAVE = "STAFF-LEAVE";
  }

  /** m07UserActionLog.Action values. */
  public static class Action {
    public static final String ADD    = "Add";
    public static final String EDIT   = "Edit";
    public static final String SUBMIT = "Submit Claim";
    public static final String VOID   = "Void";
    public static final String APPLY_LEAVE  = "Apply Leave";
    public static final String CANCEL_LEAVE = "Cancel Leave";
  }

  /**
   * Log an action with device info; never fails the caller's operation.
   * Routes to the correct company database via CompanyPoolManager.
   */
  public Uni<Void> logAction(
      String companyId,
      String currentUser,
      String module,
      String referenceNo,
      String action,
      DeviceInfo deviceInfo,
      String remarks) {

    return companyPoolManager.poolFor(companyId)
        .flatMap(pool -> auditLogRepository.createLog(
            pool,
            currentUser,
            module,
            referenceNo,
            action,
            deviceInfo != null ? deviceInfo.getDeviceName() : null,
            deviceInfo != null ? deviceInfo.getDeviceIPAddress() : null,
            deviceInfo != null ? deviceInfo.getDeviceSerialNo() : null,
            remarks))
        .replaceWithVoid()
        .onFailure().recoverWithNull();
  }

  /** Log an action without device info. */
  public Uni<Void> logAction(String companyId, String currentUser, String module, String referenceNo, String action) {
    return logAction(companyId, currentUser, module, referenceNo, action, null, null);
  }

  /** Device information holder. */
  public static class DeviceInfo {
    private final String deviceName;
    private final String deviceIPAddress;
    private final String deviceSerialNo;

    public DeviceInfo(String deviceName, String deviceIPAddress, String deviceSerialNo) {
      this.deviceName = deviceName;
      this.deviceIPAddress = deviceIPAddress;
      this.deviceSerialNo = deviceSerialNo;
    }

    public String getDeviceName() {
      return deviceName;
    }

    public String getDeviceIPAddress() {
      return deviceIPAddress;
    }

    public String getDeviceSerialNo() {
      return deviceSerialNo;
    }
  }
}
