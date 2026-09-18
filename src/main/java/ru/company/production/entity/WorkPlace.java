package ru.company.production.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

/**
 * Рабочее место.
 *
 * Состав параметров повторяет справочник операций
 * (наименование, тип, исполнитель, служба, подразделение),
 * но код формируется автоматически в виде «РМ 00.01».
 */
@Entity
@Table(
        name = "work_places",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_work_place_code",
                columnNames = "code"
        )
)
@Getter
@Setter
@NoArgsConstructor
public class WorkPlace {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 50)
    private String code;

    @Column(nullable = false, length = 255)
    private String name;

    @ManyToOne(
            fetch = FetchType.LAZY,
            optional = false
    )
    @JoinColumn(
            name = "work_place_type_id",
            nullable = false,
            foreignKey = @ForeignKey(
                    name = "fk_work_place_type"
            )
    )
    private WorkPlaceType workPlaceType;

    @ManyToOne(
            fetch = FetchType.LAZY,
            optional = false
    )
    @JoinColumn(
            name = "executor_role_id",
            nullable = false,
            foreignKey = @ForeignKey(
                    name = "fk_work_place_executor_role"
            )
    )
    private UserRole executorRole;

    @ManyToOne(
            fetch = FetchType.LAZY,
            optional = false
    )
    @JoinColumn(
            name = "service_id",
            nullable = false,
            foreignKey = @ForeignKey(
                    name = "fk_work_place_service"
            )
    )
    private ProductionService service;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(
            name = "organization_unit_id",
            foreignKey = @ForeignKey(
                    name = "fk_work_place_organization_unit"
            )
    )
    private OrganizationUnit organizationUnit;

    @Column(length = 2000)
    private String note;

    @Column(nullable = false)
    private boolean active = true;

    @CreationTimestamp
    @Column(
            name = "created_at",
            nullable = false,
            updatable = false
    )
    private LocalDateTime createdAt;
}
