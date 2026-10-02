package ru.company.production.entity;

import jakarta.persistence.*;
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
 * Заказ покупателя.
 *
 * Заголовок заказа. Изделия хранятся в {@link CustomerOrderItem} —
 * их количество в заказе не ограничено.
 *
 * На id этого заказа могут ссылаться другие таблицы. Например,
 * будущие заказы на производство связываются с заказом покупателя
 * колонкой customer_order_id.
 */
@Entity
@Table(
        name = "customer_orders",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_customer_order_number",
                columnNames = "number"
        )
)
@Getter
@Setter
@NoArgsConstructor
public class CustomerOrder {

    private static final DateTimeFormatter DATE_FORMATTER =
            DateTimeFormatter.ofPattern("dd.MM.yyyy");

    private static final DateTimeFormatter DATE_TIME_FORMATTER =
            DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm");

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * Номер заказа покупателя. Уникален, используется
     * пользователями и внешними ссылками.
     */
    @Column(nullable = false, length = 50)
    private String number;

    @ManyToOne(
            fetch = FetchType.LAZY,
            optional = false
    )
    @JoinColumn(
            name = "customer_id",
            nullable = false,
            foreignKey = @ForeignKey(
                    name = "fk_customer_order_customer"
            )
    )
    private Customer customer;

    @Column(
            name = "order_date",
            nullable = false
    )
    private LocalDate orderDate;

    /**
     * Срок исполнения заказа покупателем.
     */
    @Column(name = "due_date")
    private LocalDate dueDate;

    @Enumerated(EnumType.STRING)
    @Column(
            nullable = false,
            length = 20
    )
    private CustomerOrderStatus status =
            CustomerOrderStatus.NEW;

    /**
     * Статус заказа ДО приостановки (миграция V46).
     *
     * Только он определяет, куда заказ вернётся при возобновлении:
     * остановленный «в работе» обязан вернуться «в работу», а не
     * «новый». Пока заказ не приостановлен — null.
     */
    @Enumerated(EnumType.STRING)
    @Column(
            name = "suspended_from_status",
            length = 20
    )
    private CustomerOrderStatus suspendedFromStatus;

    /**
     * Причина приостановки: претензия заказчика, отсутствие
     * комплектации, стоп по договору и т. п. Видна в карточке, чтобы
     * через месяц понимать, почему заказ стоял (миграция V46).
     */
    @Column(
            name = "suspended_reason",
            length = 500
    )
    private String suspendedReason;

    /** Момент приостановки — для порядка и контроля простоя. */
    @Column(name = "suspended_at")
    private LocalDateTime suspendedAt;

    /**
     * Вид заказа: производство, ремонт (гарантийный/платный)
     * или внутренний заказ. По умолчанию — производство.
     */
    @Enumerated(EnumType.STRING)
    @Column(
            name = "order_type",
            nullable = false,
            length = 20
    )
    private CustomerOrderType orderType =
            CustomerOrderType.PRODUCTION;

    @Column(
            name = "contract_number",
            length = 100
    )
    private String contractNumber;

    @Column(length = 2000)
    private String note;

    /**
     * Гиперссылка на карточку заказа в Битрикс24.
     */
    @Column(name = "b24_order_url", length = 500)
    private String b24OrderUrl;

    /**
     * Гиперссылка на чек-лист заказа в Битрикс24.
     */
    @Column(name = "b24_checklist_url", length = 500)
    private String b24ChecklistUrl;

    /**
     * Приоритет планирования: очерёдность заказа и его календарные
     * правила (запуск, загруженность, переходы, отсечка изготовления).
     * Обязателен: заказ без приоритета нельзя поставить в очередь.
     */
    @ManyToOne(
            fetch = FetchType.LAZY,
            optional = false
    )
    @JoinColumn(
            name = "priority_id",
            nullable = false,
            foreignKey = @ForeignKey(
                    name = "fk_customer_order_priority"
            )
    )
    private OrderPriority priority;

    /**
     * Пользователь, создавший заказ.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(
            name = "created_by",
            foreignKey = @ForeignKey(
                    name = "fk_customer_order_author"
            )
    )
    private AppUser createdBy;

    @Column(nullable = false)
    private boolean active = true;

    @CreationTimestamp
    @Column(
            name = "created_at",
            nullable = false,
            updatable = false
    )
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    /*
     * Изделия заказа в порядке их добавления.
     */
    @OneToMany(
            mappedBy = "customerOrder",
            cascade = CascadeType.ALL,
            orphanRemoval = true
    )
    @OrderBy("position ASC")
    private List<CustomerOrderItem> items = new ArrayList<>();

    @Version
    private Long version;

    public void addItem(CustomerOrderItem item) {
        item.setCustomerOrder(this);
        item.setPosition(items.size() + 1);
        items.add(item);
    }

    public void removeItem(CustomerOrderItem item) {
        items.remove(item);

        /*
         * Порядок пересчитывается, чтобы не было пропусков
         * и нарушений уникальности (order_id, position).
         */
        for (int index = 0; index < items.size(); index++) {
            items.get(index).setPosition(index + 1);
        }
    }

    public void clearItems() {
        items.clear();
    }

    /*
     * Даты в формате «дд.мм.гггг» для шаблонов:
     * диалект Thymeleaf java8time в проект не подключён,
     * поэтому значение форматируется здесь.
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
     * Статус и приоритет отдаются вместе с именем CSS-модификатора,
     * чтобы шаблон не держал соответствие «значение → цвет».
     */
    @Transient
    public String getStatusBadgeClass() {
        return status == null ? "draft" : status.getBadgeClass();
    }

    /*
     * Признаки приостановки для шаблонов: карточка и список по ним
     * выбирают, какую кнопку показать — «Приостановить» или
     * «Возобновить». Держатся здесь, чтобы шаблон не сравнивал enum и
     * не повторял правило «что можно остановить».
     */
    @Transient
    public boolean isSuspended() {
        return status == CustomerOrderStatus.SUSPENDED;
    }

    /*
     * Можно ли приостановить: действующий заказ в незакрытом статусе.
     * Исполнен, отменён, удалён и уже приостановленный остановке не
     * подлежат — возобновление вернуло бы заказ в цикл, из которого он
     * вышел (или остановил бы стоящий на паузе повторно).
     */
    @Transient
    public boolean isCanSuspend() {
        if (!active || status == null) {
            return false;
        }

        return status != CustomerOrderStatus.SUSPENDED
                && status != CustomerOrderStatus.DONE
                && status != CustomerOrderStatus.CANCELLED
                && status != CustomerOrderStatus.DELETED;
    }

    /* Момент приостановки «дд.мм.гггг чч:мм» — для карточки заказа. */
    @Transient
    public String getFormattedSuspendedAt() {
        return suspendedAt == null
                ? "—"
                : suspendedAt.format(DATE_TIME_FORMATTER);
    }

    /*
     * Приоритет отдаётся вместе с именем CSS-модификатора, чтобы шаблон
     * не держал соответствие «приоритет → цвет». Значения читаются из
     * справочника, поэтому методы должны оставаться null-безопасными:
     * заказ мог быть создан до появления справочника.
     */
    @Transient
    public String getPriorityDisplayName() {
        return priority == null || priority.getName() == null
                ? "Обычный"
                : priority.getName();
    }

    @Transient
    public String getPriorityBadgeClass() {
        return priority == null || priority.getBadgeClass() == null
                ? "priority-ordinary"
                : priority.getBadgeClass();
    }

    /*
     * Вид заказа отдаётся вместе с именем CSS-модификатора,
     * чтобы шаблон не держал соответствие «значение → цвет».
     */
    @Transient
    public String getOrderTypeDisplayName() {
        return CustomerOrderType.orDefault(orderType).getDisplayName();
    }

    @Transient
    public String getOrderTypeBadgeClass() {
        return CustomerOrderType.orDefault(orderType).getBadgeClass();
    }

    private static String formatDate(LocalDate date) {
        return date == null
                ? "—"
                : date.format(DATE_FORMATTER);
    }

    @PreUpdate
    void touch() {
        updatedAt = LocalDateTime.now();
    }
}
