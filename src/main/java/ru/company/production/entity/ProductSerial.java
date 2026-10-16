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
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Серийный номер изделия в реестре.
 *
 * Реестр один на все типы изделий. Порядок нумерации задан не типом,
 * а областью (numbering_key):
 *
 *     888800122 → 888800123   прибор, ячейка, система — ряд своего
 *                             классификатора
 *
 *     119200111 → 119400112   датчик — ряд рода (sensor_catalog),
 *                             общий для всех классификаторов рода
 *
 * Полный номер изделия = 4 цифры кода классификатора + 5 цифр серии:
 *
 *     1212 (код) + 00022 (серия) → 121200022
 *
 * В базе серия хранится числом (22), пять знаков добавляются при выводе.
 */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(
        name = "product_serials",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_product_serial_numbering",
                columnNames = {"numbering_key", "serial"}
        )
)
public class ProductSerial {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /* Тип изделия: определяет, что считается областью нумерации. */
    @Enumerated(EnumType.STRING)
    @Column(name = "product_type", nullable = false, length = 20)
    private ProductType productType;

    /* Порядковый номер в пределах области нумерации: 1..99999. */
    @Column(name = "serial", nullable = false)
    private int serial;

    /* Изделие, для которого выдан номер — даёт 4-значный префикс. */
    @ManyToOne(
            fetch = FetchType.LAZY,
            optional = false
    )
    @JoinColumn(
            name = "product_classifier_id",
            nullable = false,
            foreignKey = @ForeignKey(
                    name = "fk_product_serial_classifier"
            )
    )
    private ProductClassifier productClassifier;

    /*
     * Род датчика — область нумерации для типа SENSOR. У классификаторов
     * остальных типов null, и тогда областью служит сам классификатор.
     *
     * Колонка повторяет род классификатора сознательно: сгенерированный
     * столбец numbering_key не может обратиться к product_classifier, а
     * уникальный ключ обязан опираться на физическое значение.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(
            name = "sensor_catalog_id",
            foreignKey = @ForeignKey(
                    name = "fk_product_serial_sensor_catalog"
            )
    )
    private SensorCatalog sensorCatalog;

    /*
     * Область нумерации: «C<классификатор>» либо «S<род датчика>».
     * Значение считает MySQL, приложение только читает его.
     */
    @Column(
            name = "numbering_key",
            insertable = false,
            updatable = false
    )
    private String numberingKey;

    /* Заказ на производство, в рамках которого выдан номер. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(
            name = "issued_production_order_id",
            foreignKey = @ForeignKey(
                    name = "fk_product_serial_production_order"
            )
    )
    private ProductionOrder issuedProductionOrder;

    /*
     * Позиция заказа покупателя, для которой выдан номер.
     *
     * Номер можно завести заранее — на этапе заказа покупателя, не
     * дожидаясь выпуска. Тогда выпуск переиспользует уже выданный
     * номер вместо нового: ряд датчика остаётся сквозным, а диспетчер
     * видит серийники сразу в заказе. У номеров, рождённых выпуском
     * или приходом, колонка null.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(
            name = "customer_order_item_id",
            foreignKey = @ForeignKey(
                    name = "fk_product_serial_customer_order_item"
            )
    )
    private CustomerOrderItem customerOrderItem;

    /*
     * Партия оприходования склада, если номер выпущен приходом, а не
     * заказом на производство. У номеров выпуска она null.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(
            name = "receiving_batch_id",
            foreignKey = @ForeignKey(
                    name = "fk_product_serial_receiving_batch"
            )
    )
    private CellReceivingBatch receivingBatch;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private ProductSerialStatus status = ProductSerialStatus.NEW;

    /*
     * Отметка входного контроля: какой вид контроля проведён, когда и
     * кем.
     *
     * Отметка стоит ОТДЕЛЬНО от status, а не внутри него: status
     * принадлежит производству и закреплён CHECK, и у одной колонки не
     * может быть двух хозяев — иначе производство затирало бы отметку
     * приёмки, а приёмка сбрасывала бы статус сборки.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "input_control_type", length = 20)
    private InputControlType inputControlType;

    @Column(name = "input_control_at")
    private LocalDateTime inputControlAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(
            name = "input_control_by",
            foreignKey = @ForeignKey(
                    name = "fk_product_serial_input_control_by"
            )
    )
    private AppUser inputControlBy;

    /*
     * Отметка приёмки на склад полуфабрикатов: ячейку принял кладовщик
     * после того, как партию проверило БВК.
     *
     * Стоит ОТДЕЛЬНО от input_control_* и от status: контроль отмечает
     * БВК, приёмку — склад, а status остаётся производством. Три
     * разных хозяина в одной колонке затёрли бы друг друга.
     */
    @Column(name = "accepted_at")
    private LocalDateTime acceptedAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(
            name = "accepted_by",
            foreignKey = @ForeignKey(
                    name = "fk_product_serial_accepted_by"
            )
    )
    private AppUser acceptedBy;

    @CreationTimestamp
    @Column(name = "issued_at", nullable = false, updatable = false)
    private LocalDateTime issuedAt;

    @Column(name = "note", length = 2000)
    private String note;

    /*
     * Номер ЗНП, привязанный к серийному номеру изделия.
     *
     * Заполняется оператором при создании выпуска (ЗП) или
     * комплектующего (ЗНП): напротив каждого серийного номера
     * появляется поле ввода, куда вносится номер ЗНП, под
     * которым данное изделие будет укомплектовано.
     *
     * Номер может быть один на несколько изделий — диспетчер
     * вписывает одно и то же значение в несколько строк.
     */
    @Column(name = "znp_number", length = 100)
    private String znpNumber;

    /** Серия в пять знаков с дополнением нулями: 22 → «00022». */
    public String getFormattedSerial() {
        return String.format("%05d", serial);
    }

    /**
     * Полный номер изделия: код классификатора + пятизначная серия.
     * Например 1212 и 22 → «121200022».
     */
    public String getFullNumber() {
        Integer code = productClassifier == null
                ? null
                : productClassifier.getCode();

        return (code == null ? "" : code) + getFormattedSerial();
    }

    /**
     * Готовая строка списка изделий: «121200022 - Название изделия».
     * Наименование берётся из классификатора, для датчика — из
     * справочника датчиков (getDisplayName()).
     */
    public String getDisplayLabel() {
        String name = productClassifier == null
                ? null
                : productClassifier.getDisplayName();

        return name == null || name.isBlank()
                ? getFullNumber()
                : getFullNumber() + " - " + name;
    }

    /**
     * Наименование изделия без номера.
     *
     * Нужно там, где номер уже выводится отдельной колонкой (выбор
     * датчиков в заказе-комплектующем), — дублировать его в той же
     * строке незачем. Связь классификатора ленивая, поэтому значение
     * отдаётся здесь, а список для таких мест читается детальным
     * запросом с fetch join.
     */
    @Transient
    public String getDisplayName() {
        String name = productClassifier == null
                ? null
                : productClassifier.getDisplayName();

        return name == null || name.isBlank()
                ? "Изделие"
                : name;
    }

    /** Тип изделия для подписи в списках. */
    @Transient
    public String getTypeName() {
        return productType == null ? "—" : productType.getDisplayName();
    }

    /*
     * Реквизиты прослеживаемости для сводки поиска.
     *
     * Связи ленивые, а open-in-view выключен: значения читаются внутри
     * readOnly-транзакции SearchSummaryService детальным запросом, и
     * только тогда обращение к номеру заказа безопасно.
     */

    /** Заказ на производство, выпустивший номер. */
    @Transient
    public String getProductionOrderNumber() {
        return issuedProductionOrder == null
                ? null
                : issuedProductionOrder.getNumber();
    }

    /** Позиция заказа покупателя, для которой выдан номер. */
    @Transient
    public String getCustomerOrderNumber() {
        if (customerOrderItem == null
                || customerOrderItem.getCustomerOrder() == null) {
            return null;
        }

        return customerOrderItem.getCustomerOrder().getNumber();
    }

    /** Приходный ордер партии, если номер выпущен приходом. */
    @Transient
    public String getIncomingOrderNumber() {
        return receivingBatch == null
                ? null
                : receivingBatch.getIncomingOrderNumber();
    }

    /** Ячейку отмечали входным контролем. */
    @Transient
    public boolean isInputControlPassed() {
        return inputControlType != null;
    }

    /** Ячейку принял склад полуфабрикатов после БВК. */
    @Transient
    public boolean isAccepted() {
        return acceptedAt != null;
    }

    /*
     * Диалект Thymeleaf java8time в проект не подключён, поэтому даты
     * для шаблонов форматируют сами сущности — как CellReceivingBatch.
     */
    private static final DateTimeFormatter DATE_TIME_FORMATTER =
            DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm");

    /** Вид контроля для строки реестра: «Визуальный и технический». */
    @Transient
    public String getInputControlLabel() {
        return inputControlType == null
                ? "контроль не проведён"
                : inputControlType.getDisplayName();
    }

    @Transient
    public String getFormattedInputControlAt() {
        return inputControlAt == null
                ? "—"
                : inputControlAt.format(DATE_TIME_FORMATTER);
    }

    /** Контролёра выводят фамилией, а не логином учётной записи. */
    @Transient
    public String getInputControlByName() {
        if (inputControlBy == null) {
            return "—";
        }

        String fullName = inputControlBy.getFullName();

        return fullName == null || fullName.isBlank()
                ? inputControlBy.getUsername()
                : fullName;
    }

    /**
     * Готовая подпись прохода ячейки для карточки контроля:
     * «Визуальный · 22.09.2026 14:10 · Иванов И. И.».
     *
     * Строка собирает все три реквизита сразу: контролёр обязан
     * видеть не только факт отметки, но и кто и когда её поставил.
     */
    @Transient
    public String getInputControlMarkLabel() {
        return inputControlType == null
                ? "контроль не проведён"
                : inputControlType.getDisplayName()
                  + " · " + getFormattedInputControlAt()
                  + " · " + getInputControlByName();
    }

    @Transient
    public String getFormattedAcceptedAt() {
        return acceptedAt == null
                ? "—"
                : acceptedAt.format(DATE_TIME_FORMATTER);
    }

    /** Кладовщика выводят фамилией, а не логином учётной записи. */
    @Transient
    public String getAcceptedByName() {
        if (acceptedBy == null) {
            return "—";
        }

        String fullName = acceptedBy.getFullName();

        return fullName == null || fullName.isBlank()
                ? acceptedBy.getUsername()
                : fullName;
    }

    /**
     * Готовая подпись приёмки на склад для строки сводки поиска:
     * «15.10.2026 10:30 · Иванов И. И.» либо «приёмка не проведена».
     */
    @Transient
    public String getAcceptedMarkLabel() {
        if (acceptedAt == null) {
            return "приёмка не проведена";
        }

        return getFormattedAcceptedAt() + " · " + getAcceptedByName();
    }
}