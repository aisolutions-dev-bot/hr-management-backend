package com.aisolutions.hrmanagement.entity;

import java.time.LocalDateTime;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

@Entity
@Table(name = "m07UserActionLog")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class UserActionLog {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "UniqId")
    private Long uniqId;
    
    @Column(name = "StaffId", length = 25)
    private String staffId;
    
    @Column(name = "Module", length = 25)
    private String module;
    
    @Column(name = "ReferenceNo", length = 45)
    private String referenceNo;
    
    @Column(name = "Action", length = 25)
    private String action; 
    
    @Column(name = "LogDate")
    private LocalDateTime logDate;
    
    @Column(name = "DeviceName", length = 45)
    private String deviceName;
    
    @Column(name = "DeviceIPAddress", length = 25)
    private String deviceIPAddress;
    
    @Column(name = "DeviceSerialNo", length = 50)
    private String deviceSerialNo;
    
    @Column(name = "Remarks", length = 255)
    private String remarks;
}
