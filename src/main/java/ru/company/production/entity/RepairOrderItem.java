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
import jakarta.persistence.Transient;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Строка заказа на ремонт: одна отмеченная ячейка.
 *
 * Ячейку пишут строкой, а не ссылкой на {@link ProductSerial}: в
 * изоляторе могут лежать и номера, зарезервированные партией и ещё не
 * внесённые в реестр (отметка «вся партия» в группе брака), поэтому
 * ссылка на реестр была бы необязательной и не покрывала половину
 * состава. Полный номер — единственный идентификатор, который есть у
 * ячейки на обеих стадиях.
 *
 * Группа брака-источник сохраняется: по ней заказ показывает причину
 * брака и партию, не пересобирая цепочку причин заново.
 */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(
        name = "repair_order_items",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_repair_order_item_number",
                columnNames = {"repair_order_id", "full_number"}
        )
)
public class RepairOrderItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(
            fetch = FetchType.LAZY,
            optional = false
    )
    @JoinColumn(
            name = "repair_order_id",
            nullable = false,
            foreignKey = @ForeignKey(
                    name = "fk_repair_order_item_order"
            )
    )
    private RepairOrder repairOrder;

    /** Группа брака, из которой ячейка попала в заказ. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(
            name = "defect_group_id",
            foreignKey = @ForeignKey(
                    name = "fk_repair_order_item_group"
            )
    )
    private InputControlDefectGroup defectGroup;

    /** Классификатор ячейки — даёт наименование и код для отчёта. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(
            name = "product_classifier_id",
            foreignKey = @ForeignKey(
                    name = "fk_repair_order_item_classifier"
            )
    )
    private ProductClassifier productClassifier;

    /** Полный номер ячейки: 4 цифры кода + 5 цифр серии. */
    @Column(name = "full_number", nullable = false, length = 20)
    private String fullNumber;

    /** Разновидность на момент формирования заказа. */
    @Enumerated(EnumType.STRING)
    @Column(name = "cell_kind", nullable = false, length = 20)
    private QuarantineCellKind cellKind;

    /** Порядок строк на экране: как отмечал руководитель. */
    @Column(name = "position", nullable = false)
    private int position = 1;

    @Column(name = "note", length = 500)
    private String note;

    /** Наименование изделия без обращения к ленивой связи. */
    @Transient
    public String getCellLabel() {
        if (productClassifier == null) {
            return fullNumber;
        }

        String name = productClassifier.getDisplayName();

        return name == null || name.isBlank()
                ? fullNumber
                : fullNumber + " — " + name;
    }

    @Transient
    public String getCellKindLabel() {
        return cellKind == null ? "—" : cellKind.getDisplayName();
    }
}
