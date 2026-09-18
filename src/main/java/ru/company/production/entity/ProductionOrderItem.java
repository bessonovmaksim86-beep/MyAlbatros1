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

/**
 * Позиция заказа на производство — один выпускаемый экземпляр изделия.
 *
 * Сколько экземпляров заявлено в позиции заказа покупателя, столько
 * строк выпуска появляется в заказе на производство. Каждая строка
 * получает собственный серийный номер из реестра {@link ProductSerial}.
 *
 * Соответствует таблице production_order_items (V16, колонка serial_id
 * добавлена в V17).
 */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(
        name = "production_order_items",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_production_order_item_position",
                columnNames = {"production_order_id", "position"}
        )
)
public class ProductionOrderItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(
            fetch = FetchType.LAZY,
            optional = false
    )
    @JoinColumn(
            name = "production_order_id",
            nullable = false,
            foreignKey = @ForeignKey(
                    name = "fk_production_order_item_order"
            )
    )
    private ProductionOrder productionOrder;

    /* Изделие классификатора: даёт 4-значный префикс номера изделия. */
    @ManyToOne(
            fetch = FetchType.LAZY,
            optional = false
    )
    @JoinColumn(
            name = "product_classifier_id",
            nullable = false,
            foreignKey = @ForeignKey(
                    name = "fk_production_order_item_classifier"
            )
    )
    private ProductClassifier productClassifier;

    /* Позиция заказа покупателя — источник этой строки выпуска. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(
            name = "customer_order_item_id",
            foreignKey = @ForeignKey(
                    name = "fk_production_order_item_customer_item"
            )
    )
    private CustomerOrderItem customerOrderItem;

    /* Выданный серийный номер. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(
            name = "serial_id",
            foreignKey = @ForeignKey(
                    name = "fk_production_order_item_serial"
            )
    )
    private ProductSerial serial;

    @Column(name = "position", nullable = false)
    private int position;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private ProductionItemStatus status = ProductionItemStatus.NEW;

    @Column(name = "note", length = 2000)
    private String note;

    /**
     * Полный номер изделия: 4 цифры кода классификатора + 5 цифр серии.
     * Например 1212 + 22 → «121200022».
     *
     * Если серийный номер ещё не выдан, возвращается только код
     * классификатора — так строка остаётся читаемой.
     */
    public String getFullNumber() {
        if (serial == null) {
            return productClassifier == null
                    ? "—"
                    : String.valueOf(productClassifier.getCode());
        }

        return serial.getFullNumber();
    }
}
