package ru.company.production.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(
        name = "operations",
        uniqueConstraints = {
                @UniqueConstraint(
                        name = "uk_operation_code",
                        columnNames = "code"
                )
        },
        indexes = {
                @Index(
                        name = "idx_operation_type",
                        columnList = "operation_type"
                ),
                @Index(
                        name = "idx_operation_workshop",
                        columnList = "workshop_id"
                ),
                @Index(
                        name = "idx_operation_department",
                        columnList = "department_id"
                )
        }
)
public class Operation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 50)
    private String code;

    @Column(nullable = false, length = 255)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(
            name = "operation_type",
            nullable = false,
            length = 40
    )
    private OperationType type;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(
            name = "workshop_id",
            foreignKey = @ForeignKey(
                    name = "fk_operation_workshop"
            )
    )
    private Workshop workshop;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(
            name = "department_id",
            foreignKey = @ForeignKey(
                    name = "fk_operation_department"
            )
    )
    private Department department;

    @Column(nullable = false)
    private boolean active = true;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
    }
}