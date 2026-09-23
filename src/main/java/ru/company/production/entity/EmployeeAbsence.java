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
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * Отсутствие сотрудника в производственном календаре.
 *
 * Строка описывает ровно один день: отметка на несколько чисел
 * превращается сервисом в несколько записей. Такой формат делает
 * пересечение двух видов отсутствия в один день невозможным на
 * уровне схемы (миграция V23) и упрощает выборку за месяц.
 */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(
        name = "employee_absences",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_employee_absence_date",
                columnNames = {"employee_id", "absence_date"}
        )
)
public class EmployeeAbsence {

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
                    name = "fk_employee_absence_employee"
            )
    )
    private AppUser employee;

    @Column(
            name = "absence_date",
            nullable = false
    )
    private LocalDate absenceDate;

    @Enumerated(EnumType.STRING)
    @Column(
            name = "absence_type",
            nullable = false,
            length = 20
    )
    private AbsenceType absenceType;

    @Column(name = "note", length = 1000)
    private String note;

    @CreationTimestamp
    @Column(
            name = "created_at",
            nullable = false,
            updatable = false
    )
    private LocalDateTime createdAt;
}
