package com.aisolutions.hrmanagement.repository;

import io.smallrye.mutiny.Uni;
import io.vertx.mutiny.mysqlclient.MySQLClient;
import io.vertx.mutiny.sqlclient.Row;
import io.vertx.mutiny.sqlclient.RowSet;
import io.vertx.mutiny.sqlclient.SqlClient;
import io.vertx.mutiny.sqlclient.Tuple;
import jakarta.enterprise.context.ApplicationScoped;
import lombok.extern.slf4j.Slf4j;

import com.aisolutions.hrmanagement.entity.UserActionLog;
import com.aisolutions.shared.util.DateUtil;

@ApplicationScoped
@Slf4j
public class UserActionLogRepository {

    /**
     * Create audit log entry
     */
    public Uni<UserActionLog> createLog(
        SqlClient client,
        String staffId,
        String module,
        String referenceNo,
        String action,
        String deviceName,
        String deviceIPAddress,
        String deviceSerialNo,
        String remarks) {

        return client.preparedQuery(
                "INSERT INTO m07UserActionLog (StaffId, Module, ReferenceNo, Action, LogDate, " +
                "DeviceName, DeviceIPAddress, DeviceSerialNo, Remarks) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)")
            .execute(Tuple.tuple()
                .addValue(staffId)
                .addValue(module)
                .addValue(referenceNo)
                .addValue(action)
                .addValue(DateUtil.nowSGT())
                .addValue(deviceName)
                .addValue(deviceIPAddress)
                .addValue(deviceSerialNo)
                .addValue(remarks))
            .flatMap(result -> {
                Long id = result.property(MySQLClient.LAST_INSERTED_ID);
                UserActionLog log = new UserActionLog();
                log.setUniqId(id);
                log.setStaffId(staffId);
                log.setModule(module);
                log.setReferenceNo(referenceNo);
                log.setAction(action);
                log.setLogDate(DateUtil.nowSGT());
                log.setDeviceName(deviceName);
                log.setDeviceIPAddress(deviceIPAddress);
                log.setDeviceSerialNo(deviceSerialNo);
                log.setRemarks(remarks);
                return Uni.createFrom().item(log);
            })
            .onFailure().invoke(e -> {
                System.err.println("Error creating audit log: " + e.getMessage());
                e.printStackTrace();
            });
    }
}
