package ru.company.production.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * Работа, выполненная вне производственного плана.
 *
 * Заводится руководителем цеха вручную через раздел «Работа без плана».
 * Не участвует в распределении, не попадает в «Мои операции» и не
 * закрывает плановые строки — используется только в статистике
 * производительности.
 */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "work_without_plan")
public class WorkWithoutPlan {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(optional = false)
    @JoinColumn(name = "employee_id", nullable = false,
            foreignKey = @ForeignKey(name = "fk_wwp_employee"))
    private AppUser employee;

    @Column(name = "operation_name", nullable = false, length = 255)
    private String operationName;

    /** Норма, человеко-часы на одно изделие. */
    @Column(name = "norm_hours", nullable = false, precision = 10, scale = 3)
    private BigDecimal normHours;

    /** Количество изделий. */
    @Column(nullable = false)
    private Integer quantity;

    /** Дата выполнения. */
    @Column(name = "work_date", nullable = false)
    private LocalDate workDate;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @ManyToOne
    @JoinColumn(name = "created_by", foreignKey = @ForeignKey(name = "fk_wwp_created_by"))
    private AppUser createdBy;

    /** Общие часы: норма × количество. */
    public BigDecimal getTotalHours() {
        if (normHours == null || quantity == null) {
            return BigDecimal.ZERO;
        }
        return normHours.multiply(BigDecimal.valueOf(quantity));
    }
}
