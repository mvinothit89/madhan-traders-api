package com.mt.salesapp.model;

import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Entity
@Table(name = "mt_audit_logs")
public class AuditLog {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    
    private LocalDateTime logTimestamp = LocalDateTime.now();
    
    private String actionType; // INSERT, UPDATE, DELETE
    
    private String tableName;
    
    private String recordReference;
    
    private String performedBy = "System-Admin";
}