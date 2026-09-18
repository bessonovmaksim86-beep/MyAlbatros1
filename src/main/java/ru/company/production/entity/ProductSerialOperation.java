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
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Выполнение цеховой операции конкретным изделием (экземпляром).
 *
 * Вокруг неизменного серийного номера накапливается план и факт по
 * каждой операции, ответственный исполнитель и порядок операций. Это
 * замена плоским таблицам вида «одна колонка = одна операция», где
 * число операций ограничивалось числом колонок и добавление операции
 * требовало перестройки таблицы.
 *
 * ПЕРЕДЕЛКИ. Операция у одного изделия может выполняться несколько раз
 * (возврат на доработку), поэтому уникальный ключ держит номер попытки:
 * attempt = 1 — первое выполнение, 2 и далее — переделки. Прежняя
 * попытка НЕ перезаписывается: она остаётся строкой со статусом
 * REWORKED, и история переделок живёт в этой же таблице.
 *
 * ЗАКАЗ ОПЕРАЦИИ. Колонка production_order_id (миграция V20) отвечает на
 * вопрос «в рамках какого заказа выполнена операция». При связи
 * «датчик ⇄ заказ» «многие ко многим» без неё смысл терялся бы: если
 * датчик укомплектован по двум заказам, невозможно понять, к какому из
 * них относится запись о выполнении.
 *
 * NULL — операция общая для изделия и ни к одному заказу не привязана
 * (например, входной контроль выпущенного узла).
 *
 * Соответствует таблице product_serial_operations (миграции V19, V20).
 */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(
        name = "product_serial_operations",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_pso_serial_operation_attempt",
                columnNames = {"serial_id", "operation_id", "attempt"}
        )
)
public class ProductSerialOperation {

    private static final DateTimeFormatter DATE_FORMATTER =
            DateTimeFormatter.ofPattern("dd.MM.yyyy");

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /* Экземпляр изделия: 121205333 и т. п. Номер неизменный. */
    @ManyToOne(
            fetch = FetchType.LAZY,
            optional = false
    )
    @JoinColumn(
            name = "serial_id",
            nullable = false,
            foreignKey = @ForeignKey(
                    name = "fk_pso_serial"
            )
    )
    private ProductSerial serial;

    /*
     * Заказ, в рамках которого выполнена операция.
     * Как правило это заказ-комплектующий (ЗНП) из
     * SerialProductionOrder; null — общая операция изделия.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(
            name = "production_order_id",
            foreignKey = @ForeignKey(
                    name = "fk_pso_production_order"
            )
    )
    private ProductionOrder productionOrder;

    /* Цеховая операция из справочника. */
    @ManyToOne(
            fetch = FetchType.LAZY,
            optional = false
    )
    @JoinColumn(
            name = "operation_id",
            nullable = false,
            foreignKey = @ForeignKey(
                    name = "fk_pso_operation"
            )
    )
    private Operation operation;

    /*
     * Номер попытки: 1 — первое выполнение, 2+ — переделка.
     * Актуальной считается строка с наибольшим номером.
     */
    @Column(name = "attempt", nullable = false)
    private int attempt = 1;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private ProductSerialOperationStatus status =
            ProductSerialOperationStatus.PLANNED;

    /* План и факт одной строкой — не разъезжаются по разным таблицам. */
    @Column(name = "planned_date")
    private LocalDate plannedDate;

    @Column(name = "actual_date")
    private LocalDate actualDate;

    /* Ответственный за операцию. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(
            name = "executor_id",
            foreignKey = @ForeignKey(
                    name = "fk_pso_executor"
            )
    )
    private AppUser executor;

    /* Кто отметил фактическое выполнение. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(
            name = "performed_by",
            foreignKey = @ForeignKey(
                    name = "fk_pso_performed_by"
            )
    )
    private AppUser performedBy;

    /* Порядок операции в матрице выпуска. */
    @Column(name = "seq", nullable = false)
    private int seq;

    @Column(name = "note", length = 2000)
    private String note;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @PreUpdate
    void touch() {
        updatedAt = LocalDateTime.now();
    }

    /* Переделанная попытка: её считают закрытой, актуальна следующая. */
    @Transient
    public boolean isReworked() {
        return status == ProductSerialOperationStatus.REWORKED;
    }

    /* Операция закрыта фактом. */
    @Transient
    public boolean isDone() {
        return status == ProductSerialOperationStatus.DONE;
    }

    /*
     * Просрочка: план есть, факта нет, срок вышел.
     * Сегодняшняя дата берётся здесь, чтобы шаблон не вычислял её сам.
     */
    @Transient
    public boolean isOverdue() {
        return plannedDate != null
                && actualDate == null
                && plannedDate.isBefore(LocalDate.now());
    }

    /*
     * Даты в формате «дд.мм.гггг»: диалект Thymeleaf java8time
     * не подключён, поэтому значение форматируется здесь — так же,
     * как в ProductionOrder.
     */
    @Transient
    public String getFormattedPlannedDate() {
        return formatDate(plannedDate);
    }

    @Transient
    public String getFormattedActualDate() {
        return formatDate(actualDate);
    }

    @Transient
    public String getStatusDisplayName() {
        return status == null ? "—" : status.getDisplayName();
    }

    @Transient
    public String getStatusBadgeClass() {
        return status == null ? "draft" : status.getBadgeClass();
    }

    /*
     * Наименование операции и ФИО исполнителя — текст вместо lazy-связей:
     * матрица строится списком, а open-in-view выключен.
     */
    @Transient
    public String getOperationName() {
        if (operation == null
                || operation.getOperationName() == null) {
            return "—";
        }

        return operation.getOperationName().getName();
    }

    @Transient
    public String getExecutorName() {
        return executor == null ? "—" : executor.getFullName();
    }

    @Transient
    public String getProductionOrderNumber() {
        return productionOrder == null
                ? "—"
                : productionOrder.getNumber();
    }

    /* Признак переделки для колонки матрицы: «2», «3» … */
    @Transient
    public String getAttemptLabel() {
        return attempt <= 1 ? null : String.valueOf(attempt);
    }

    private static String formatDate(LocalDate date) {
        return date == null
                ? "—"
                : date.format(DATE_FORMATTER);
    }
}
