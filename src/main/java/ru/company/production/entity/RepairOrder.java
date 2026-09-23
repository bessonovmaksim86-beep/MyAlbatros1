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
 * Заказ на ремонт ячеек и подсборок из изолятора брака (ЗНП).
 *
 * Заказ заводит руководитель службы качества: он отбирает ячейки в
 * перечне изолятора и оформляет на них отдельный заказ — на ремонт
 * ячеек, на ремонт подсборок или на выдачу комплектации.
 *
 * Заказ не является {@link ProductionOrder} и не включается в его
 * дерево: ЗП/ЗНП диспетчера описывают выпуск продукции под заказ
 * покупателя, а этот заказ описывает работу с браком, у которого нет
 * заказчика, есть поставщик и есть перечень конкретных ячеек. Общей
 * у них только нумерация по тому же образцу.
 *
 * Поставщик записан строкой, а не ссылкой на
 * {@link WarehouseSupplier}: так же, как в партии прихода
 * ({@code cell_receiving_batches.supplier_name}), потому что заказ на
 * ремонт могут выставлять поставщику, которого в справочник склада ещё
 * не заводили. Ссылка {@code supplier} заполняется, когда написание в
 * справочнике есть, — она нужна сводке, а не отчёту.
 */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(
        name = "repair_orders",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_repair_order_number",
                columnNames = "number"
        )
)
public class RepairOrder {

    private static final DateTimeFormatter DATE_FORMATTER =
            DateTimeFormatter.ofPattern("dd.MM.yyyy");

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Номер заказа, например ЗНР-2026-001. */
    @Column(name = "number", nullable = false, length = 50)
    private String number;

    /** Назначение заказа: ремонт ячеек, подсборок или выдача комплектации. */
    @Enumerated(EnumType.STRING)
    @Column(name = "order_type", nullable = false, length = 30)
    private RepairOrderType orderType;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private RepairOrderStatus status = RepairOrderStatus.NEW;

    /**
     * Поставщик, к которому относится брак (выбор «типа поставщика»).
     *
     * Пишется написанием из справочника склада либо собственным
     * текстом, если поставщика в справочнике нет.
     */
    @Column(name = "supplier_name", nullable = false, length = 255)
    private String supplierName;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(
            name = "supplier_id",
            foreignKey = @ForeignKey(
                    name = "fk_repair_order_supplier"
            )
    )
    private WarehouseSupplier supplier;

    @Column(name = "order_date", nullable = false)
    private LocalDate orderDate;

    @Column(name = "note", length = 2000)
    private String note;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(
            name = "created_by",
            foreignKey = @ForeignKey(
                    name = "fk_repair_order_author"
            )
    )
    private AppUser createdBy;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    /**
     * Отмеченные ячейки заказа.
     *
     * Коллекция ленивая и из шаблона не читается ({@code open-in-view}
     * выключен): перечень отдаёт сервис отдельным запросом с fetch.
     */
    @OneToMany(
            mappedBy = "repairOrder",
            cascade = CascadeType.ALL,
            orphanRemoval = true
    )
    @OrderBy("position ASC")
    private List<RepairOrderItem> items = new ArrayList<>();

    @PreUpdate
    void touch() {
        updatedAt = LocalDateTime.now();
    }

    public void addItem(RepairOrderItem item) {
        item.setRepairOrder(this);
        items.add(item);
    }

    public void clearItems() {
        items.clear();
    }

    /** Заказ ещё не брали в работу. */
    @Transient
    public boolean isNew() {
        return status == RepairOrderStatus.NEW;
    }

    /** Заказ закрыт: ни ячеек добавить, ни статус менять нельзя. */
    @Transient
    public boolean isClosed() {
        return status == RepairOrderStatus.DONE
                || status == RepairOrderStatus.CANCELLED;
    }

    @Transient
    public String getOrderTypeLabel() {
        return orderType == null ? "—" : orderType.getDisplayName();
    }

    @Transient
    public String getStatusDisplayName() {
        return status == null ? "—" : status.getDisplayName();
    }

    /** Модификатор CSS-класса бейджа статуса. */
    @Transient
    public String getStatusBadgeClass() {
        return status == null ? "draft" : status.getBadgeClass();
    }

    @Transient
    public String getFormattedOrderDate() {
        return orderDate == null ? "—" : orderDate.format(DATE_FORMATTER);
    }

    @Transient
    public String getFormattedCreatedAt() {
        return createdAt == null ? "—" : createdAt.format(DATE_FORMATTER);
    }

    /** Автор заказа — выводится фамилия, а не логин. */
    @Transient
    public String getCreatedByName() {
        if (createdBy == null) {
            return "—";
        }

        String fullName = createdBy.getFullName();

        return fullName == null || fullName.isBlank()
                ? createdBy.getUsername()
                : fullName;
    }

    /**
     * Разновидность изделий заказа одним словом.
     *
     * Состав однороден по смыслу (ремонт ячеек — ячейки, ремонт
     * подсборок — подсборки), поэтому подпись считают по виду заказа,
     * а не перебирают строки.
     */
    @Transient
    public String getKindLabel() {
        return switch (orderType == null
                ? RepairOrderType.CELL_REPAIR : orderType) {
            case CELL_REPAIR -> QuarantineCellKind.CELL.getDisplayName();
            case SUBASSEMBLY_REPAIR ->
                    QuarantineCellKind.SUBASSEMBLY.getDisplayName();
            case KITS_ISSUE -> "Комплектация";
        };
    }
}
