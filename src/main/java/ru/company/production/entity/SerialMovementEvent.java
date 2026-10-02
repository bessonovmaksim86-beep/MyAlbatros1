package ru.company.production.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * Одна отметка журнала перемещений: номер, событие, дата, исполнитель,
 * заказ-источник.
 *
 * Строка неизменяемая (нет @Version и audit-колонок — их нет и в V42,
 * и {@code ddl-auto=validate} сверяет колонки один в один). Номер
 * держат строкой {@code fullNumber}, а ссылку на реестр —
 * необязательной: в изолятор попадают номера «всей партией», ещё не
 * внесённые в {@code product_serials} (CHECK chk_sme_has_identifier).
 */
@Entity
@Table(name = "serial_movement_events")
@Getter
@Setter
public class SerialMovementEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "serial_id")
    private Long serialId;

    @Column(name = "full_number", length = 20)
    private String fullNumber;

    @Enumerated(EnumType.STRING)
    @Column(name = "event_type", nullable = false, length = 30)
    private SerialMovementType eventType;

    @Column(name = "occurred_at", nullable = false)
    private LocalDateTime occurredAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "actor_id")
    private AppUser actor;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "repair_order_id")
    private RepairOrder repairOrder;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "production_order_id")
    private ProductionOrder productionOrder;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "defect_group_id")
    private InputControlDefectGroup defectGroup;

    @Column(name = "note", length = 500)
    private String note;
}