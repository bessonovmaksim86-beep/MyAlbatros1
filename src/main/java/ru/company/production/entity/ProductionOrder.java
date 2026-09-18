package ru.company.production.entity;

import jakarta.persistence.CascadeType;
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
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * Заказ на производство.
 *
 * Диспетчер создаёт заказ так:
 *   1) номер формируется автоматически (ПЗ-ГГГГ-NNN);
 *   2) выбирается конкретная операция из справочника — только
 *      комплектовочного типа (KITTING);
 *   3) привязывается ОДИН заказ покупателя: его позиции и определяют
 *      список выпускаемых изделий.
 *
 * Состав заказа хранится в {@link ProductionOrderItem}: одна строка —
 * один выпускаемый экземпляр с собственным серийным номером.
 *
 * Имена колонок заданы явно: схема проверяется Hibernate
 * (ddl-auto: validate), поэтому соответствие миграции V16 нужно
 * поддерживать вручную.
 */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "production_orders")
public class ProductionOrder {

    private static final DateTimeFormatter DATE_FORMATTER =
            DateTimeFormatter.ofPattern("dd.MM.yyyy");

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "number", nullable = false, length = 50)
    private String number;

    /*
     * Заказ покупателя, ради которого выполняется выпуск.
     *
     * Уникальность держится НЕ по самой колонке, а по служебной
     * root_customer_order_id (миграция V21): она заполнена только у
     * заказов верхнего уровня, поэтому ограничение «один заказ
     * покупателя — один выпуск» действует для ЗП и не мешает
     * создавать несколько заказов-комплектующих (ЗНП) того же
     * покупателя.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(
            name = "customer_order_id",
            foreignKey = @ForeignKey(
                    name = "fk_production_order_customer_order"
            )
    )
    private CustomerOrder customerOrder;

    /*
     * Родительский заказ — уровень дерева (миграция V21):
     *
     *   родитель не задан → это ЗАКАЗ НА ПРОИЗВОДСТВО (ЗП): в него
     *       входят серийники выпускаемых изделий;
     *   родитель задан   → это ЗАКАЗ НА ПРОИЗВОДСТВО-КОМПЛЕКТУЮЩИЙ
     *       (ЗНП): он входит внутрь конкретного датчика (например,
     *       комплектование ячейкой 333).
     *
     * Оба уровня — одна сущность: номер, операция и статус у них
     * общие, поэтому отдельной таблицы для ЗНП не заводили.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(
            name = "parent_production_order_id",
            foreignKey = @ForeignKey(
                    name = "fk_production_order_parent"
            )
    )
    private ProductionOrder parentProductionOrder;

    /* Конкретная комплектовочная операция из справочника. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(
            name = "production_operation_id",
            foreignKey = @ForeignKey(
                    name = "fk_production_order_operation"
            )
    )
    private Operation productionOperation;

    @Column(name = "order_date", nullable = false)
    private LocalDate orderDate;

    @Column(name = "due_date")
    private LocalDate dueDate;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private ProductionOrderStatus status = ProductionOrderStatus.NEW;

    @Column(name = "note", length = 2000)
    private String note;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(
            name = "created_by",
            foreignKey = @ForeignKey(
                    name = "fk_production_order_author"
            )
    )
    private AppUser createdBy;

    @Column(name = "active", nullable = false)
    private boolean active = true;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    @OneToMany(
            mappedBy = "productionOrder",
            cascade = CascadeType.ALL,
            orphanRemoval = true
    )
    @OrderBy("position ASC")
    private List<ProductionOrderItem> items = new ArrayList<>();

    @PreUpdate
    void touch() {
        updatedAt = LocalDateTime.now();
    }

    public void addItem(ProductionOrderItem item) {
        item.setProductionOrder(this);
        items.add(item);
    }

    public void clearItems() {
        items.clear();
    }

    /*
     * Даты в формате «дд.мм.гггг» для шаблонов: диалект Thymeleaf
     * java8time в проект не подключён, поэтому значение форматируется
     * здесь — так же, как в CustomerOrder.
     */
    @Transient
    public String getFormattedOrderDate() {
        return formatDate(orderDate);
    }

    @Transient
    public String getFormattedDueDate() {
        return formatDate(dueDate);
    }

    /*
     * Статус отдаётся вместе с именем CSS-модификатора, чтобы шаблон
     * не держал соответствие «значение → цвет».
     */
    @Transient
    public String getStatusDisplayName() {
        return status == null ? "—" : status.getDisplayName();
    }

    @Transient
    public String getStatusBadgeClass() {
        return status == null ? "draft" : status.getBadgeClass();
    }

    /*
     * Номер связанного заказа покупателя: в списке и просмотре он
     * нужен как текст, а связь lazy — без этого метода шаблон лез бы
     * в базу за пределами транзакции (open-in-view: false).
     */
    @Transient
    public String getCustomerOrderNumber() {
        return customerOrder == null
                ? "—"
                : customerOrder.getNumber();
    }

    /*
     * Заказ верхнего уровня (ЗП): родителя нет.
     * Только у таких заказов действует ограничение «один заказ
     * покупателя — один выпуск».
     */
    @Transient
    public boolean isRoot() {
        return parentProductionOrder == null;
    }

    /*
     * Заказ-комплектующий (ЗНП): вложен в заказ на производство и
     * входит внутрь конкретного изделия.
     */
    @Transient
    public boolean isComponent() {
        return parentProductionOrder != null;
    }

    /* Уровень дерева для колонок списка и карточки заказа. */
    @Transient
    public String getLevelDisplayName() {
        return isRoot()
                ? "Заказ на производство"
                : "Заказ на производство (комплектующий)";
    }

    /* Номер родительского заказа — текст, связь lazy (см. getCustomerOrderNumber). */
    @Transient
    public String getParentOrderNumber() {
        return parentProductionOrder == null
                ? "—"
                : parentProductionOrder.getNumber();
    }

    /*
     * Идентификатор родителя для ссылок в шаблонах: связь lazy, и
     * обращаться к parentProductionOrder.id напрямую из Thymeleaf
     * нельзя — вне сессии это LazyInitializationException.
     */
    @Transient
    public Long getParentId() {
        return parentProductionOrder == null
                ? null
                : parentProductionOrder.getId();
    }

    /* Наименование комплектовочной операции — то же соображение. */
    @Transient
    public String getOperationName() {
        if (productionOperation == null
                || productionOperation.getOperationName() == null) {
            return "—";
        }

        return productionOperation.getOperationName().getName();
    }

    /* Фамилия и инициалы автора выпуска для страницы просмотра. */
    @Transient
    public String getCreatedByName() {
        return createdBy == null
                ? "—"
                : createdBy.getFullName();
    }

    private static String formatDate(LocalDate date) {
        return date == null
                ? "—"
                : date.format(DATE_FORMATTER);
    }
}