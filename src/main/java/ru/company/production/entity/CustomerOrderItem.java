package ru.company.production.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Изделие в заказе покупателя.
 *
 * Одна строка = одна позиция заказа. Количество позиций в заказе
 * не ограничено: при добавлении позиции порядок (position)
 * назначается автоматически.
 *
 * Изделие выбирается из классификатора продукции, поэтому в заказ
 * можно включить прибор, систему, ячейку или датчик.
 *
 * На id этой позиции также можно ссылаться из других таблиц —
 * например, из будущих заказов на производство, чтобы указать,
 * из какой позиции заказа покупателя вырос производственный заказ.
 */
@Entity
@Table(
        name = "customer_order_items",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_customer_order_item_position",
                columnNames = {
                        "order_id",
                        "position"
                }
        )
)
@Getter
@Setter
@NoArgsConstructor
public class CustomerOrderItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /*
     * Имя поля — customerOrder, а не order:
     * «order» зарезервировано в JPQL/HQL.
     * Колонка в БД остаётся order_id.
     */
    @ManyToOne(
            fetch = FetchType.LAZY,
            optional = false
    )
    @JoinColumn(
            name = "order_id",
            nullable = false,
            foreignKey = @ForeignKey(
                    name = "fk_customer_order_item_order"
            )
    )
    private CustomerOrder customerOrder;

    @ManyToOne(
            fetch = FetchType.LAZY,
            optional = false
    )
    @JoinColumn(
            name = "product_classifier_id",
            nullable = false,
            foreignKey = @ForeignKey(
                    name = "fk_customer_order_item_classifier"
            )
    )
    private ProductClassifier productClassifier;

    /**
     * Полное обозначение датчика «как в конструкторской
     * документации», введённое диспетчером вручную:
     * «Датчик уровня ультразвуковой ДУУ2М-12-1-15,00-0,15-ОМ1,5**-3
     * (поплавок титановый тип II УНКР.305446.080)».
     *
     * Заполняется для позиций типа «Датчик»: классификатор даёт
     * только код и род, а полное обозначение с параметрами нигде
     * больше не сохраняется. Для остальных типов — null.
     */
    @Column(name = "full_name", length = 500)
    private String fullName;

    @Column(
            nullable = false,
            precision = 15,
            scale = 3
    )
    private BigDecimal quantity;

    @Enumerated(EnumType.STRING)
    @Column(
            nullable = false,
            length = 20
    )
    private MeasurementUnit unit =
            MeasurementUnit.PCS;

    /**
     * Тип техпроцесса, по которому выпускается позиция.
     * По умолчанию — базовый техпроцесс изделия.
     */
    @Enumerated(EnumType.STRING)
    @Column(
            name = "tech_process_type",
            nullable = false,
            length = 20
    )
    private TechProcessType techProcessType =
            TechProcessType.BASIC;

    /**
     * Сколько изделий по этой позиции уже готово.
     */
    @Column(
            name = "done_quantity",
            nullable = false,
            precision = 15,
            scale = 3
    )
    private BigDecimal doneQuantity = BigDecimal.ZERO;

    /**
     * Срок по конкретной позиции.
     * Если null — используется срок всего заказа.
     */
    @Column(name = "due_date")
    private LocalDate dueDate;

    @Enumerated(EnumType.STRING)
    @Column(
            nullable = false,
            length = 20
    )
    private CustomerOrderItemStatus status =
            CustomerOrderItemStatus.NEW;

    @Column(nullable = false)
    private Integer position;

    @Column(length = 2000)
    private String note;

    /**
     * Осталось выпустить по позиции.
     */
    @Transient
    public BigDecimal getRemainingQuantity() {
        BigDecimal planned =
                quantity == null
                        ? BigDecimal.ZERO
                        : quantity;

        BigDecimal done =
                doneQuantity == null
                        ? BigDecimal.ZERO
                        : doneQuantity;

        return planned.subtract(done);
    }
}
