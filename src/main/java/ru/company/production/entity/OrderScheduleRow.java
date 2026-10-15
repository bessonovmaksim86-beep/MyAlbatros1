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
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Строка графика заказа покупателя.
 *
 * Одна строка = одна операция техпроцесса одного экземпляра одной
 * позиции заказа (миграция V43). Экземпляр нужен потому, что заказ
 * планируется до выпуска: серийного номера ещё нет, а планировать
 * нужно каждое изделие, иначе загрузка этапа оказалась бы заниженной
 * ровно в количество раз, сколько изделий в позиции.
 *
 * СТАТУС определяет силу строки.
 * DRAFT — предварительный расчёт, который диспетчер ещё не подтвердил:
 * такие строки в загрузке этапов не участвуют, иначе черновик, так и
 * не внесённый в план, заблокировал бы следующий заказ.
 * CONFIRMED — строка внесена в план, её человеко-часы считаются
 * занятым временем этапа при расчёте следующего заказа.
 *
 * ДАТЫ считает диспетчер, РАБОЧИЕ МЕСТА и исполнителей распределяет
 * руководитель подразделения (колонки work_place_id / executor_id):
 * права разведены именно поэтому, и планировщик их не заполняет.
 */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "order_schedule_rows")
public class OrderScheduleRow {

    private static final DateTimeFormatter DATE_FORMATTER =
            DateTimeFormatter.ofPattern("dd.MM.yyyy");

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /*
     * Основание строки — заказ покупателя (V43).
     *
     * nullable = false снят миграцией V56: строка графика теперь
     * планирует работу одного из двух оснований, и для группового
     * заказа-комплектующего этого заказа нет вовсе. Обязательность
     * «ровно одного из двух» держит chk_osr_base_exactly_one в базе,
     * поэтому в сущности связь объявлена просто lazy, без optional.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(
            name = "order_id",
            foreignKey = @ForeignKey(name = "fk_osr_customer_order")
    )
    private CustomerOrder customerOrder;

    /*
     * Основание строки — заказ на производство (V56).
     *
     * Нужен групповым заказам-комплектующим (V38): у них нет заказа
     * покупателя, но есть свой техпроцесс (V40) и своё количество, и
     * без строк плана операции их маршрута нигде не живут — цех не
     * видит «Монтаж подсборок» в распределении, и следующая после
     * комплектования операция не появляется.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(
            name = "production_order_id",
            foreignKey = @ForeignKey(name = "fk_osr_production_order")
    )
    private ProductionOrder productionOrder;

    /** Номер позиции заказа (customer_order_items.position). */
    @Column(
            name = "item_position",
            nullable = false
    )
    private Integer itemPosition;

    /** Номер экземпляра внутри позиции, 0-based. */
    @Column(
            name = "instance_index",
            nullable = false
    )
    private Integer instanceIndex;

    @ManyToOne(
            fetch = FetchType.LAZY,
            optional = false
    )
    @JoinColumn(
            name = "tech_process_id",
            nullable = false,
            foreignKey = @ForeignKey(name = "fk_osr_tech_process")
    )
    private TechProcess techProcess;

    @ManyToOne(
            fetch = FetchType.LAZY,
            optional = false
    )
    @JoinColumn(
            name = "operation_id",
            nullable = false,
            foreignKey = @ForeignKey(name = "fk_osr_operation")
    )
    private Operation operation;

    /** Подразделение — хозяин операции: по нему мастер видит работы. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(
            name = "organization_unit_id",
            foreignKey = @ForeignKey(name = "fk_osr_organization_unit")
    )
    private OrganizationUnit organizationUnit;

    /** Порядок операции в маршруте. */
    @Column(nullable = false)
    private Integer seq;

    @Column(
            name = "planned_date",
            nullable = false
    )
    private LocalDate plannedDate;

    /** Трудоёмкость на экземпляр, человеко-часы. */
    @Column(
            name = "labor_hours",
            nullable = false,
            precision = 10,
            scale = 3
    )
    private BigDecimal laborHours;

    /** Загрузка этапа в этот день по итогам расчёта, проценты. */
    @Column(name = "load_percent")
    private Integer loadPercent;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ScheduleRowStatus status = ScheduleRowStatus.DRAFT;

    /*
     * Распределение по рабочим местам и исполнителям — зона
     * руководителя подразделения, планировщик оставляет их пустыми.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(
            name = "work_place_id",
            foreignKey = @ForeignKey(name = "fk_osr_work_place")
    )
    private WorkPlace workPlace;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(
            name = "executor_id",
            foreignKey = @ForeignKey(name = "fk_osr_executor")
    )
    private AppUser executor;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(
            name = "assigned_by",
            foreignKey = @ForeignKey(name = "fk_osr_assigned_by")
    )
    private AppUser assignedBy;

    @Column(name = "assigned_at")
    private LocalDateTime assignedAt;

    /*
     * Проведение — факт выполнения этого экземпляра операции.
     * Отметку ставит исполнитель, на которого строка закреплена
     * (V44): completed_at — когда, completed_by — кто. Пока дата
     * пустая, работа считается незакрытой и висит в «Мои операции».
     */
    @Column(name = "completed_at")
    private LocalDateTime completedAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(
            name = "completed_by",
            foreignKey = @ForeignKey(name = "fk_osr_completed_by")
    )
    private AppUser completedBy;

    @Column(length = 2000)
    private String note;

    @Column(
            name = "created_at",
            nullable = false,
            updatable = false
    )
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    /** Дата плана в привычном формате — для таблиц и текстовых сообщений. */
    @Transient
    public String getFormattedPlannedDate() {
        return plannedDate == null
                ? "—"
                : plannedDate.format(DATE_FORMATTER);
    }

    /**
     * Строка планирует работу заказа на производство, а не позиции
     * заказа покупателя (V56).
     *
     * Признак читается из связи, а не из пустоты instanceIndex: у
     * строк ЗНП позиция остаётся нулевой, и проверка позиции
     * перепутала бы одну ячейку группового заказа с первым экземпляром
     * позиции плана.
     */
    @Transient
    public boolean isProductionOrderBased() {
        return productionOrder != null;
    }

    /*
     * Номер основания строки — то, по которому работу ищут в реестре.
     *
     * Для плана заказа покупателя это его номер, для строки ЗНП —
     * номер заказа на производство: у группового ЗНП своего заказа
     * покупателя нет, и прочерк в этой колонке оставил бы кладовщика и
     * мастера без ответа «ради чего эта работа».
     */
    @Transient
    public String getBaseNumber() {
        if (productionOrder != null) {
            return productionOrder.getNumber();
        }

        return customerOrder == null
                ? "—"
                : customerOrder.getNumber();
    }

    @PrePersist
    void onCreate() {
        createdAt = LocalDateTime.now();
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}