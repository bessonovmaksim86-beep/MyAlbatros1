package ru.company.production.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

/**
 * Приоритет планирования заказа покупателя.
 *
 * Задаёт правила очерёдности и календарный каркас исполнения:
 *
 *   startDays             — «запуск»: через сколько рабочих дней после
 *                           планирования оформлять первые операции;
 *   maxLoadPercent        — допустимая загруженность участка в процентах,
 *                           до которой участку ещё можно назначать работу;
 *   transitionDays        — межоперационные переходы: рабочие дни паузы
 *                           между окончанием одной операции и началом
 *                           следующей;
 *   maxManufacturingDays  — отсечка изготовления: столько рабочих дней
 *                           перед сроком отгрузки остаётся на изготовление.
 *                           Если «срок − отсечка» уже прошла, заказ
 *                           не может быть исполнен в срок и планирование
 *                           по такому приоритету отклоняется.
 */
@Entity
@Table(
        name = "order_priorities",
        uniqueConstraints = {
                @UniqueConstraint(
                        name = "uk_order_priority_code",
                        columnNames = "code"
                ),
                @UniqueConstraint(
                        name = "uk_order_priority_name",
                        columnNames = "name"
                )
        }
)
@Getter
@Setter
@NoArgsConstructor
public class OrderPriority {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 20)
    private String code;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(name = "start_days", nullable = false)
    private Integer startDays;

    @Column(name = "max_load_percent", nullable = false)
    private Integer maxLoadPercent;

    @Column(name = "transition_days", nullable = false)
    private Integer transitionDays;

    @Column(name = "max_manufacturing_days", nullable = false)
    private Integer maxManufacturingDays;

    /** Модификатор CSS-класса бейджа в списках и карточках. */
    @Column(name = "badge_class", length = 40)
    private String badgeClass;

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
