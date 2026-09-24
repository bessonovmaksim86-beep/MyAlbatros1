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
 * Документ проходит через двух исполнителей, и порядок этот важнее
 * набора полей:
 *
 * - диспетчер заводит заявку — пишет номер, выбирает тип изделий и
 *   классификатор; состав в этот момент пуст, и заявка описывает намерение
 *   («ремонтировать ячейки вот этого изделия»), а не перечень;
 * - служба качества берёт заявку в работу и набирает состав из изолятора
 *   брака, не выходя за рамки указанного типа и классификатора;
 * - набранная заявка возвращается диспетчеру, который выбирает техпроцесс
 *   и ставит ЗНП в график.
 *
 * Поэтому {@link #cellKind} и {@link #productClassifier} держат именно
 * здесь, а не выводят из строк состава: их назначают до появления
 * состава, и без них заявка не говорила бы службе качества, что набирать.
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

    /**
     * Тип изделий, на которые заведена заявка.
     *
     * Выбирает диспетчер при оформлении, до того как в заявке появится
     * хоть одна ячейка. Служба качества набирает состав не выходя за
     * рамки этого типа: смешать ячейки и подсборки в одном ЗНП нельзя
     * уже потому, что в заявке им места не осталось.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "cell_kind", length = 20)
    private QuarantineCellKind cellKind;

    /**
     * Изделие (классификатор), к которому относится заявка.
     *
     * Как и тип, назначает диспетчер. Одно изделие на заявку — то же
     * правило, по которому ЗНП печатают с одной строкой изделия.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(
            name = "product_classifier_id",
            foreignKey = @ForeignKey(
                    name = "fk_repair_order_classifier"
            )
    )
    private ProductClassifier productClassifier;

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

    /**
     * Ремонт выдан с выдачей комплектации.
     *
     * Отметка, а не отдельный вид заказа: комплектацию выдают вместе с
     * ремонтом ячейки или подсборки, поэтому состав компонентов держат в
     * заголовке заказа ({@link #kitsList}).
     */
    @Column(name = "kits_issue", nullable = false)
    private boolean kitsIssue;

    /** Перечень компонентов для выдачи — свободный текст заказчика. */
    @Column(name = "kits_list", length = 2000)
    private String kitsList;

    /**
     * Ремонт выполняют собственными силами (СДП), а не поставщик.
     *
     * Такой заказ не ссылается на справочник поставщиков — вместо
     * ссылки у него маршрут, по которому ремонт делают
     * ({@link #techProcess}).
     */
    @Column(name = "by_sdp", nullable = false)
    private boolean bySdp;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(
            name = "tech_process_id",
            foreignKey = @ForeignKey(
                    name = "fk_repair_order_tech_process"
            )
    )
    private TechProcess techProcess;

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

    /** Заявка создана и ещё не взята службой качества в набор. */
    @Transient
    public boolean isNew() {
        return status == RepairOrderStatus.NEW;
    }

    /** Состав набран, заявка ждёт техпроцесса и графика у диспетчера. */
    @Transient
    public boolean isReturned() {
        return status == RepairOrderStatus.RETURNED;
    }

    /**
     * Набор состава открыт.
     *
     * Ячейки добавляют, пока заявка не вернулась диспетчеру: после
     * возврата состав становится частью переданного документа, и
     * добавленная «втихую» ячейка означала бы, что служба качества
     * отчиталась одним составом, а по факту ремонтируют другой.
     */
    @Transient
    public boolean isFillingOpen() {
        return status == RepairOrderStatus.NEW
                || status == RepairOrderStatus.IN_WORK;
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
     * Маршрут ремонта силами СДП: «ТП-14 — ЯПР56» для карточки.
     *
     * Связь ленивая, поэтому читают только из запроса с {@code fetch}
     * (см. RepairOrderRepository): при выключенном open-in-view
     * обращение к коду маршрута из шаблона прервало бы рендеринг.
     */
    @Transient
    public String getTechProcessLabel() {
        if (techProcess == null) {
            return "—";
        }

        String code = techProcess.getCode();

        if (code == null || code.isBlank()) {
            return "Маршрут без кода";
        }

        return techProcess.getProductClassifier() == null
                ? code
                : code + " — " + techProcess.getProductClassifier().getCode();
    }

    /** Исполнитель заказа одним взглядом: СДП или поставщик. */
    @Transient
    public String getExecutorLabel() {
        return bySdp ? "Силами СДП" : supplierName;
    }

    /**
     * Разновидность изделий заказа одним словом.
     *
     * Берут из поля заявки, а не из вида заказа: тип назначает
     * диспетчер, и он же ограничивает набор состава. Вид заказа о
     * разновидности только сообщает, но держать одно другим — значит
     * потерять различие, если однажды ремонт ячеек начнут оформлять
     * разными видами работ.
     *
     * Для заказов, заведённых до переноса типа в заголовок, остаточный
     * вывод по виду заказа сохраняют — иначе их строки вышли бы пустыми.
     */
    @Transient
    public String getKindLabel() {
        if (cellKind != null) {
            return cellKind.getDisplayName();
        }

        return switch (orderType == null
                ? RepairOrderType.CELL_REPAIR : orderType) {
            case CELL_REPAIR -> QuarantineCellKind.CELL.getDisplayName();
            case SUBASSEMBLY_REPAIR ->
                    QuarantineCellKind.SUBASSEMBLY.getDisplayName();
            case KITS_ISSUE -> "Комплектация";
        };
    }

    /**
     * Изделие заявки: «1212 — Подсборка ЯПР56».
     *
     * Связь ленивая — читают только из запроса с fetch
     * (см. RepairOrderRepository).
     */
    @Transient
    public String getClassifierLabel() {
        if (productClassifier == null) {
            return "—";
        }

        return productClassifier.getDisplayName();
    }

    /**
     * Заявка ждёт диспетчера: состав набран, техпроцесса ещё нет.
     *
     * Признак нужен карточке, чтобы показать, чей сейчас ход: у
     * вернувшейся заявки служба качества уже ничего не меняет.
     */
    @Transient
    public boolean isAwaitingDispatcher() {
        return isReturned() && techProcess == null;
    }
}
