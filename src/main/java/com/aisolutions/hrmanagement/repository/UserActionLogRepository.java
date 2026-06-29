package com.aisolutions.hrmanagement.repository;

import io.quarkus.hibernate.reactive.panache.PanacheRepositoryBase;
import io.quarkus.hibernate.reactive.panache.common.WithSession;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;

import com.aisolutions.hrmanagement.entity.UserActionLog;

import java.time.LocalDateTime;

@ApplicationScoped
@WithSession
public class UserActionLogRepository implements PanacheRepositoryBase<UserActionLog, Long> {

  /**
   * Create audit log entry
   */
  public Uni<UserActionLog> createLog(
      String staffId,
      String module,
      String referenceNo,
      String action,
      String deviceName,
      String deviceIPAddress,
      String deviceSerialNo,
      String remarks) {

    return getSession().flatMap(session -> {
      UserActionLog log = new UserActionLog();
      log.setStaffId(staffId);
      log.setModule(module);
      log.setReferenceNo(referenceNo);
      log.setAction(action);
      log.setLogDate(LocalDateTime.now());
      log.setDeviceName(deviceName);
      log.setDeviceIPAddress(deviceIPAddress);
      log.setDeviceSerialNo(deviceSerialNo);
      log.setRemarks(remarks);

      return session.persist(log).replaceWith(log);
    })
    .onFailure().invoke(e -> {
      System.err.println("Error creating audit log: " + e.getMessage());
      e.printStackTrace();
    });
  }
}
