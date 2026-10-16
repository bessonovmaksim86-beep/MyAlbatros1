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
 *
 * Состояние ячейки ({@link #serialStatus}, {@link #cellKind},
 * {@link #productClassifier}, {@link #inputControlType}) копируют в
 * строку, а не читают по ссылке: {@code product_serials} перечёркивает
 * следующее движение, и паспорт, собранный по текущим колонкам, показал
 * бы нынешнюю стадию вместо той, на которой событие произошло.
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

    /* Статус реестра на момент события (V65). */
    @Enumerated(EnumType.STRING)
    @Column(name = "serial_status", length = 20)
    private ProductSerialStatus serialStatus;

    /* Ячейка либо подсборка на момент события (V65). */
    @Enumerated(EnumType.STRING)
    @Column(name = "cell_kind", length = 20)
    private QuarantineCellKind cellKind;

    /* Изделие на момент события (V65). */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(
            name = "product_classifier_id",
            foreignKey = @ForeignKey(name = "fk_sme_classifier")
    )
    private ProductClassifier productClassifier;

    /* Вид входного контроля, проставленный этим событием (V65). */
    @Enumerated(EnumType.STRING)
    @Column(name = "input_control_type", length = 20)
    private InputControlType inputControlType;

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

    /*
     * Группа брака — причина и исход события.
     *
     * Ссылку снимают при удалении группы (ON DELETE SET NULL, V65):
     * БВК перезаписывает группы при каждом сохранении карточки, а
     * строка журнала обязана пережить эту перезапись.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(
            name = "defect_group_id",
            foreignKey = @ForeignKey(name = "fk_sme_defect_group")
    )
    private InputControlDefectGroup defectGroup;

    /* Партия прихода — куда ячейка ушла или из которой пришла (V65). */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(
            name = "receiving_batch_id",
            foreignKey = @ForeignKey(name = "fk_sme_receiving_batch")
    )
    private CellReceivingBatch receivingBatch;

    /* Строка перемещения из изолятора, породившая событие (V65). */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(
            name = "quarantine_transfer_id",
            foreignKey = @ForeignKey(name = "fk_sme_quarantine_transfer")
    )
    private QuarantineTransfer quarantineTransfer;

    @Column(name = "note", length = 500)
    private String note;
}