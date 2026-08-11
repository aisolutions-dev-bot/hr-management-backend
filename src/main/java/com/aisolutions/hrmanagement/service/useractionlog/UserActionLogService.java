package com.aisolutions.hrmanagement.service.useractionlog;

import com.aisolutions.hrmanagement.repository.UserActionLogRepository;

import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

@ApplicationScoped
public class UserActionLogService {

  @Inject
  UserActionLogRepository auditLogRepository;

  /** m07UserActionLog.Module values for HR Management claim actions. */
  public static class Module {
    public static final String STAFF_CLAIM = "STAFF-CLAIM";
  }

  /** m07UserActionLog.Action values. */
  public static class Action {
    public static final String ADD    = "Add";
    public static final String EDIT   = "Edit";
    public static final String SUBMIT = "Submit Claim";
    public static final String VOID   = "Void";
  }

  /** Log an action with device info; never fails the caller's operation. */
  public Uni<Void> logAction(
      String currentUser,
      String module,
      String referenceNo,
      String action,
      DeviceInfo deviceInfo,
      String remarks) {

    return auditLogRepository.createLog(
        currentUser,
        module,
        referenceNo,
        action,
        deviceInfo != null ? deviceInfo.getDeviceName() : null,
        deviceInfo != null ? deviceInfo.getDeviceIPAddress() : null,
        deviceInfo != null ? deviceInfo.getDeviceSerialNo() : null,
        remarks)
        .replaceWithVoid()
        .onFailure().recoverWithNull();
  }

  /** Log an action without device info. */
  public Uni<Void> logAction(String currentUser, String module, String referenceNo, String action) {
    return logAction(currentUser, module, referenceNo, action, null, null);
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
