package ru.company.production.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

/**
 * Привязка сотрудника цеха к операции или к рабочему месту.
 *
 * Заполнен ровно один из двух объектов {@code operation} /
 * {@code workPlace} — какой именно, говорит {@code bindingType}.
 * Ограничение на «один из двух» висит и в схеме (миграция V23),
 * и проверяется сервисом перед сохранением: сервис даёт человеку
 * понятный текст, а схема страхует от обхода через прямую правку БД.
 */
@Getter
@Setter
@NoArgsConstructor
/*
 * Уникальность привязки в схеме посчитана по сгенерированным ключам
 * operation_key / work_place_key (миграция V23), поэтому уникальное
 * ограничение в маппинге не объявляется — так же, как в Operation,
 * где аналогично устроен uk_operation_assignment.
 */
@Entity
@Table(name = "workshop_staff_bindings")
public class WorkshopStaffBinding {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(
            fetch = FetchType.LAZY,
            optional = false
    )
    @JoinColumn(
            name = "employee_id",
            nullable = false,
            foreignKey = @ForeignKey(
                    name = "fk_workshop_staff_binding_employee"
            )
    )
    private AppUser employee;

    @Enumerated(EnumType.STRING)
    @Column(
            name = "binding_type",
            nullable = false,
            length = 20
    )
    private StaffBindingType bindingType;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(
            name = "operation_id",
            foreignKey = @ForeignKey(
                    name = "fk_workshop_staff_binding_operation"
            )
    )
    private Operation operation;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(
            name = "work_place_id",
            foreignKey = @ForeignKey(
                    name = "fk_workshop_staff_binding_work_place"
            )
    )
    private WorkPlace workPlace;

    @CreationTimestamp
    @Column(
            name = "created_at",
            nullable = false,
            updatable = false
    )
    private LocalDateTime createdAt;
}
