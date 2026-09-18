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
import jakarta.persistence.Transient;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

/**
 * Изделие ⇄ заказ на производство: чем укомплектован экземпляр.
 *
 * Связь «многие ко многим» между серийным номером и заказом:
 *
 *   * в ОДИН датчик входит несколько заказов-комплектующих (ЗНП) —
 *     например, датчик укомплектован ячейкой, кабелем и блоком питания;
 *   * ОДИН ЗНП входит в несколько датчиков — комплектование
 *     выполняется на группу датчиков (заказ на ячейку 333 закрывает
 *     сразу несколько датчиков).
 *
 * ЭТО НЕ ДУБЛЬ ДРУГИХ СВЯЗЕЙ. В базе есть ещё две, и каждая отвечает
 * за свой факт:
 *
 *   ProductSerial.issuedProductionOrder — КЕМ ВЫПУЩЕН номер:
 *       однократный неизменный факт, один заказ;
 *   ProductionOrderItem.serial — какое изделие позиция выпуска
 *       ВЫПУСКАЕТ (появляется вместе с выпуском);
 *   эта таблица — чем датчик УКОМПЛЕКТОВАН: один ЗНП даёт строку
 *       на каждый датчик группы.
 *
 * Соответствует таблице serial_production_orders (миграция V20).
 */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(
        name = "serial_production_orders",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_spo_serial_order",
                columnNames = {"serial_id", "production_order_id"}
        )
)
public class SerialProductionOrder {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /* Экземпляр изделия: номер неизменный, всё остальное — строки. */
    @ManyToOne(
            fetch = FetchType.LAZY,
            optional = false
    )
    @JoinColumn(
            name = "serial_id",
            nullable = false,
            foreignKey = @ForeignKey(
                    name = "fk_spo_serial"
            )
    )
    private ProductSerial serial;

    /* Заказ-комплектующий (ЗНП), вошедший в изделие. */
    @ManyToOne(
            fetch = FetchType.LAZY,
            optional = false
    )
    @JoinColumn(
            name = "production_order_id",
            nullable = false,
            foreignKey = @ForeignKey(
                    name = "fk_spo_production_order"
            )
    )
    private ProductionOrder productionOrder;

    /*
     * Комплектовочная операция, в рамках которой заказ вошёл в изделие.
     * Как правило совпадает с операцией самого заказа, но хранится
     * здесь: один заказ может входить в разные изделия разными
     * операциями, когда группа комплектуются поэтапно.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(
            name = "operation_id",
            foreignKey = @ForeignKey(
                    name = "fk_spo_operation"
            )
    )
    private Operation operation;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    /* Кто внёс заказ в изделие. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(
            name = "created_by",
            foreignKey = @ForeignKey(
                    name = "fk_spo_author"
            )
    )
    private AppUser createdBy;

    @Column(name = "note", length = 2000)
    private String note;

    /*
     * Готовая строка списка «что входит в датчик»: номер заказа и его
     * операция. Связи lazy, поэтому значение формируется здесь —
     * шаблон не должен лезть в базу вне транзакции
     * (open-in-view: false).
     */
    public String getDisplayLabel() {
        String number = productionOrder == null
                ? "—"
                : productionOrder.getNumber();

        if (operation == null
                || operation.getOperationName() == null) {
            return number;
        }

        return number + " — "
                + operation.getOperationName().getName();
    }

    /*
     * Отдельные значения для карточки заказ-комплектующего: там номер
     * изделия и его наименование выводятся разными колонками, а
     * getDisplayLabel() склеивает их в одну строку.
     */
    @Transient
    public String getSerialNumber() {
        return serial == null ? "—" : serial.getFullNumber();
    }

    @Transient
    public String getSerialName() {
        return serial == null ? "—" : serial.getDisplayName();
    }

    @Transient
    public String getSerialTypeName() {
        return serial == null ? "—" : serial.getTypeName();
    }

    @Transient
    public String getOperationName() {
        if (operation == null
                || operation.getOperationName() == null) {
            return "—";
        }

        return operation.getOperationName().getName();
    }
}
