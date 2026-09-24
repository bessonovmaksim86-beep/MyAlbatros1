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

import java.math.BigDecimal;
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

    /*
     * Вид заказа-комплектующего (миграция V37): комплектование,
     * групповой на ячейки, групповой на приборы, ремонт ячеек или
     * ремонт подсборок.
     *
     * Колонка NOT NULL с значением по умолчанию, поэтому у заказа
     * верхнего уровня она тоже заполнена — комплектованием. Это
     * сознательно: отдельного «пустого» вида для ЗП не заводили, а
     * уровень и так читается из parent_production_order_id, так что
     * противоречия нет (см. isKindMeaningful()).
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "znp_kind", nullable = false, length = 30)
    private ZnpKind znpKind = ZnpKind.KITTING;

    /*
     * Изделие, заказанное группой или в ремонт (миграция V37).
     *
     * Заполняется только для видов, состав которых задаётся «изделием
     * и количеством»: у комплектования состава такого нет — там
     * перечень серийных номеров родительского выпуска.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(
            name = "product_classifier_id",
            foreignKey = @ForeignKey(
                    name = "fk_production_order_classifier"
            )
    )
    private ProductClassifier productClassifier;

    /*
     * Количество заказанных изделий — тот же DECIMAL(15,3), что у
     * позиции заказа покупателя (V12): количество в учёте дробное.
     */
    @Column(name = "quantity", precision = 15, scale = 3)
    private BigDecimal quantity;

    /* Конкретная комплектовочная операция из справочника. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(
            name = "production_operation_id",
            foreignKey = @ForeignKey(
                    name = "fk_production_order_operation"
            )
    )
    private Operation productionOperation;

    /*
     * Техпроцесс, по которому выполняют групповой заказ (миграция V40).
     *
     * Заполняется только видам, которому маршрут нужен (см.
     * ZnpKind#requiresTechProcess): у группового заказа работ по выпуску
     * нет, и ведут его по маршруту заказанного изделия. Заказу на
     * комплектование маршрут не назначается — его операции живут у
     * выпуска, внутрь которого заказ входит.
     *
     * Ссылка на справочник, а не копия маршрута: правки техпроцесса
     * диспетчер видит в заказе сразу, а история выполненных работ
     * держится отдельно (ProductSerialOperation).
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(
            name = "tech_process_id",
            foreignKey = @ForeignKey(
                    name = "fk_production_order_tech_process"
            )
    )
    private TechProcess techProcess;

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
     * Заказ верхнего уровня (ЗП) — выпуск.
     *
     * Признак «родителя нет» для этого больше не достаточен: с V37
     * групповые и ремонтные ЗНП существуют самостоятельно, без
     * родительского выпуска, и выпуск из них не получается. Выпуск —
     * заказ без родителя, состав которого задан позициями заказа
     * покупателя, то есть серийный вид (V38).
     *
     * Только у выпусков действует ограничение «один заказ покупателя
     * — один выпуск» и только они выдают серийные номера.
     */
    @Transient
    public boolean isRoot() {
        return parentProductionOrder == null
                && (znpKind == null || znpKind.isSerialBased());
    }

    /*
     * Заказ-комплектующий (ЗНП): либо вложен в выпуск и входит внутрь
     * конкретного изделия, либо заявлен самостоятельно — группой или
     * на ремонт (см. ZnpKind).
     */
    @Transient
    public boolean isComponent() {
        return !isRoot();
    }

    /*
     * Уровень дерева для колонок списка и карточки заказа: «ЗП» либо
     * «ЗНП <вид>». Вид показан именно здесь, а не отдельной колонкой:
     * он есть только у комплектующего, и отдельная колонка у половины
     * строк списка была бы пустой.
     */
    @Transient
    public String getLevelDisplayName() {
        if (isRoot()) {
            return "Заказ на производство";
        }

        /*
         * Префикс «ЗНП ·» к наименованию не добавляется: оно уже
         * содержит нужное слово («ЗНП на комплектование», «Групповой
         * ЗНП на приборы»), и приставка дала бы «ЗНП · ЗНП на …».
         */
        return znpKind == null
                ? "Заказ на производство (комплектующий)"
                : znpKind.getDisplayName();
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

    /*
     * Вид ЗНП нужен только заказу-комплектующему: заказ верхнего
     * уровня вида не выбирает, и показывать ему «комплектование» —
     * значит выдумывать смысл, которого в заказе нет.
     */
    @Transient
    public boolean isKindApplicable() {
        return isComponent() && znpKind != null;
    }

    @Transient
    public String getZnpKindDisplayName() {
        return znpKind == null ? "—" : znpKind.getDisplayName();
    }

    /*
     * Составлен ли заказ перечнем серийных номеров.
     *
     * Карточке это нужно, чтобы решить, показывать ли блок датчиков:
     * у группового и ремонтного заказа их нет, и пустая таблица «заказ
     * ещё не внесён ни в один датчик» выглядела бы как ошибка данных.
     */
    @Transient
    public boolean isSerialBasedKind() {
        return znpKind == null || znpKind.isSerialBased();
    }

    /*
     * Заказ относится к разделу «Групповые ЗНП».
     *
     * Нужен карточке и форме заказа, чтобы подсветить в меню тот раздел,
     * откуда заказ заведён, и вернуть в него после сохранения. Уровень
     * для этого не годится: групповой заказ самостоятелен, родителя нет,
     * и по «родителя нет» он оказался бы в списке выпусков.
     *
     * Признак берётся у вида (см. ZnpKind#isGroupKind), а не переписывается
     * перечнем: новый вид попадает в раздел сам.
     */
    @Transient
    public boolean isGroupZnp() {
        return znpKind != null && znpKind.isGroupKind();
    }

    /*
     * Страница, с которой заказ заведён и на которую надо возвращать:
     * комплектование и выпуски живут в «Заказах на производство»,
     * групповые виды — в одноимённом разделе меню (V37).
     *
     * Возврат отдаётся методом, а не тернарным выражением в шаблоне:
     * ссылку подставляют и карточка, и форма, и повторить признак
     * в двух разметках значило бы разъезд с разделами при первом же
     * новом виде.
     */
    @Transient
    public String getSectionListUrl() {
        return isGroupZnp()
                ? "/dispatcher/group-znp"
                : "/dispatcher/production-order";
    }

    /*
     * Изделие группового или ремонтного заказа: «код — наименование»,
     * как в прочих формах. Связь lazy, поэтому текст отдаётся этим
     * методом, а не обращением шаблона к productClassifier.code.
     */
    @Transient
    public String getClassifierLabel() {
        if (productClassifier == null) {
            return "—";
        }

        return productClassifier.getCode()
                + " — "
                + productClassifier.getDisplayName();
    }

    @Transient
    public Long getClassifierId() {
        return productClassifier == null
                ? null
                : productClassifier.getId();
    }

    /*
     * Количество без хвостовых нулей: «24», а не «24.000» — заказ
     * группой считают в штуках, и дробный хвост в карточке только
     * мешает.
     */
    @Transient
    public String getFormattedQuantity() {
        if (quantity == null) {
            return "—";
        }

        return quantity.stripTrailingZeros().toPlainString();
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

    /*
     * Код техпроцесса — для карточки и списка группового заказа.
     *
     * Пустая строка, а не «—»: поле показывают только групповым видам,
     * и пустое значение означает, что маршрут ещё не выбран (колонка
     * появилась в V40, старые заказы её не заполняли).
     */
    @Transient
    public String getTechProcessCode() {
        return techProcess == null
                ? ""
                : techProcess.getCode();
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