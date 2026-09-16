package ru.company.production.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

@Entity
@Table(name = "operations")
@Getter
@Setter
public class Operation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(
            fetch = FetchType.LAZY,
            optional = false
    )
    @JoinColumn(
            name = "operation_name_id",
            nullable = false
    )
    private OperationName operationName;

    @ManyToOne(
            fetch = FetchType.LAZY,
            optional = false
    )
    @JoinColumn(
            name = "operation_type_id",
            nullable = false
    )
    private OperationType operationType;

    @ManyToOne(
            fetch = FetchType.LAZY,
            optional = false
    )
    @JoinColumn(
            name = "executor_role_id",
            nullable = false
    )
    private UserRole executorRole;

    @ManyToOne(
            fetch = FetchType.LAZY,
            optional = false
    )
    @JoinColumn(
            name = "service_id",
            nullable = false
    )
    private ProductionService service;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "organization_unit_id")
    private OrganizationUnit organizationUnit;

    /*
     * Произвольный комментарий к операции.
     */
    @Column(length = 2000)
    private String note;

    @CreationTimestamp
    @Column(
            name = "created_at",
            nullable = false,
            updatable = false
    )
    private LocalDateTime createdAt;
}