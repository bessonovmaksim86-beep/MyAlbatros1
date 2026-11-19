package ru.company.production.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

/**
 * Привязка ячейки к прибору при комплектовании группового ЗНП.
 *
 * При комплектовании группового ЗНП на приборы технолог задаёт
 * в техпроцессе виды ячеек (контролируемые параметры «Ячейка №»).
 * Склад вносит номера ячеек, принятых на склад, — они формируют
 * состав ЗНП, но не привязаны к конкретным приборам.
 *
 * После перехода ЗНП к следующей операции по техпроцессу (сдвиг)
 * исполнитель привязывает каждую ячейку к конкретному прибору.
 * После привязки путь ячейки в производстве считается законченным
 * — она «вошла в изделие».
 *
 * Соответствует таблице cell_instrument_bindings (V91).
 */
@Entity
@Table(
        name = "cell_instrument_bindings",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_cib_instrument_cell",
                columnNames = {"instrument_serial_id", "cell_serial_id"}
        )
)
@Getter
@Setter
@NoArgsConstructor
public class CellInstrumentBinding {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /* Серийник прибора (из production_order_items). */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(
            name = "instrument_serial_id",
            nullable = false,
            foreignKey = @ForeignKey(
                    name = "fk_cib_instrument"
            )
    )
    private ProductSerial instrumentSerial;

    /* Серийник ячейки, вошедшей в этот прибор. */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(
            name = "cell_serial_id",
            nullable = false,
            foreignKey = @ForeignKey(
                    name = "fk_cib_cell"
            )
    )
    private ProductSerial cellSerial;

    /* Классификатор вида ячейки — из контролируемого параметра операции ТП. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(
            name = "cell_classifier_id",
            foreignKey = @ForeignKey(
                    name = "fk_cib_classifier"
            )
    )
    private ProductClassifier cellClassifier;

    /* Заказ-комплектующий, в рамках которого выполнена привязка. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(
            name = "production_order_id",
            foreignKey = @ForeignKey(
                    name = "fk_cib_production_order"
            )
    )
    private ProductionOrder productionOrder;

    /* Операция, на которой выполнена привязка. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(
            name = "operation_id",
            foreignKey = @ForeignKey(
                    name = "fk_cib_operation"
            )
    )
    private Operation operation;

    /* Кто и когда привязал. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(
            name = "created_by",
            foreignKey = @ForeignKey(
                    name = "fk_cib_created_by"
            )
    )
    private AppUser createdBy;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    /**
     * Полный номер прибора: 4 цифры кода + 5 цифр серии.
     */
    public String getInstrumentFullNumber() {
        return instrumentSerial == null ? "—" : instrumentSerial.getFullNumber();
    }

    /**
     * Полный номер ячейки.
     */
    public String getCellFullNumber() {
        return cellSerial == null ? "—" : cellSerial.getFullNumber();
    }

    /**
     * Наименование прибора.
     */
    public String getInstrumentName() {
        return cellSerial == null ? "—" : cellSerial.getDisplayName();
    }
}
