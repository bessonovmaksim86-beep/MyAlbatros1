package ru.company.production.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Подтверждаемый переход позиции со склада в подразделение.
 *
 * Когда склад вносит ячейку (или подсборку) в заказ другого
 * подразделения, позиция переходит в статус «Передана, ждёт
 * подтверждения». Получатель открывает реестр «Приёмка от
 * склада», сверяет номера и подтверждает получение — после
 * этого позиция снимается со склада и появляется в распоряжении
 * цеха.
 *
 * Если получатель отклоняет позицию, она возвращается на склад
 * и остаётся в доступном остатке.
 *
 * Состав проводится в отдельной таблице, а не отметкой на ячейке:
 * так же, как quarantine_transfers (V63), — чтобы реестр склада
 * мог видеть незакрытые передачи и исключать их из доступного
 * остатка.
 *
 * Соответствует таблице unit_handovers (миграция V81).
 */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "unit_handovers")
public class UnitHandover {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /* Полный номер ячейки/подсборки: 4 цифры кода + 5 цифр серии. */
    @Column(name = "full_number", nullable = false, length = 20)
    private String fullNumber;

    /* Ссылка на серийный номер — для быстрых проверок и журналов. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "serial_id", foreignKey = @ForeignKey(
            name = "fk_unit_handover_serial"))
    private ProductSerial serial;

    /*
     * Классификатор — наименование для перечней.
     * NULL допустим: номера могут попадать сюда из партий прихода.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "product_classifier_id", foreignKey = @ForeignKey(
            name = "fk_unit_handover_classifier"))
    private ProductClassifier classifier;

    /* SUBASSEMBLY / CELL — разновидность на момент передачи. */
    @Enumerated(EnumType.STRING)
    @Column(name = "cell_kind", nullable = false, length = 20)
    private QuarantineCellKind cellKind;

    /* Подразделение-отправитель (склад полуфабрикатов). */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "from_unit_id", nullable = false, foreignKey = @ForeignKey(
            name = "fk_unit_handover_from_unit"))
    private OrganizationUnit fromUnit;

    /* Подразделение-получатель (цех сборки / настройки и т.п.). */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "to_unit_id", nullable = false, foreignKey = @ForeignKey(
            name = "fk_unit_handover_to_unit"))
    private OrganizationUnit toUnit;

    /* Заказ-комплектующий, по которому передана позиция. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "to_order_id", foreignKey = @ForeignKey(
            name = "fk_unit_handover_to_order"))
    private ProductionOrder toOrder;

    /* Номер заказа — чтобы не грузить связь при каждом чтении. */
    @Column(name = "to_order_number", length = 50)
    private String toOrderNumber;

    /* Операция, по которой передана позиция. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "to_operation_id", foreignKey = @ForeignKey(
            name = "fk_unit_handover_to_operation"))
    private Operation toOperation;

    /* Кто и когда передал позицию. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "transferred_by", nullable = false, foreignKey = @ForeignKey(
            name = "fk_unit_handover_transferred_by"))
    private AppUser transferredBy;

    @CreationTimestamp
    @Column(name = "transferred_at", nullable = false)
    private LocalDateTime transferredAt;

    /* Принято ли получателем. */
    @Column(nullable = false)
    private boolean accepted = false;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "accepted_by", foreignKey = @ForeignKey(
            name = "fk_unit_handover_accepted_by"))
    private AppUser acceptedBy;

    @Column(name = "accepted_at")
    private LocalDateTime acceptedAt;

    /* Отклонено ли получателем и причина. */
    @Column(nullable = false)
    private boolean rejected = false;

    @Column(name = "rejection_reason", length = 1000)
    private String rejectionReason;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "rejected_by", foreignKey = @ForeignKey(
            name = "fk_unit_handover_rejected_by"))
    private AppUser rejectedBy;

    @Column(name = "rejected_at")
    private LocalDateTime rejectedAt;

    /*
     * Подписи живут здесь, а не в шаблоне: связи ленивые, а
     * open-in-view выключен — обращение к transferredBy или
     * toUnit в шаблоне упёрлось бы в LazyInitializationException.
     */
    @Transient
    public boolean isPending() {
        return !accepted && !rejected;
    }

    @Transient
    public String getTransferredByLabel() {
        if (transferredBy == null) return "—";
        String fullName = transferredBy.getFullName();
        return fullName != null && !fullName.isBlank()
                ? fullName
                : transferredBy.getUsername();
    }

    @Transient
    public String getTransferredAtLabel() {
        return transferredAt == null ? "—"
                : transferredAt.format(DATE_TIME_FORMATTER);
    }

    @Transient
    public String getAcceptedByLabel() {
        if (acceptedBy == null) return "—";
        String fullName = acceptedBy.getFullName();
        return fullName != null && !fullName.isBlank()
                ? fullName
                : acceptedBy.getUsername();
    }

    @Transient
    public String getAcceptedAtLabel() {
        return acceptedAt == null ? "—"
                : acceptedAt.format(DATE_TIME_FORMATTER);
    }

    @Transient
    public String getToUnitName() {
        return toUnit != null
                ? toUnit.getName()
                : "—";
    }

    @Transient
    public String getFromUnitName() {
        return fromUnit != null && fromUnit.getName() != null
                ? fromUnit.getName()
                : "—";
    }

    /**
     * Изделие строкой: «5102 — ЯПР20М».
     *
     * Термин «Ячейка»/«Подсборка» сюда не подставляется намеренно: он описывает разновидность
     * позиции и печатается отдельной колонкой «Вид». Наименование изделия — то, что о нём сказал
     * классификатор, и переименовывать его по стадии маршрута нельзя.
     */
    @Transient
    public String getDeviceLabel() {
        String name = classifier == null ? null : classifier.getDisplayName();

        if (classifier == null || classifier.getCode() == null) {
            return name == null || name.isBlank() ? fullNumber : name.trim();
        }

        return name == null || name.isBlank()
                ? String.valueOf(classifier.getCode())
                : classifier.getCode() + " — " + name.trim();
    }

    /** Разновидность: «Ячейка» / «Подсборка» — единственное место термина. */
    @Transient
    public String getKindLabel() {
        return cellKind == null ? "—" : cellKind.getDisplayName();
    }

    /** Модификатор CSS-класса плашки вида. */
    @Transient
    public String getKindBadgeClass() {
        return cellKind == QuarantineCellKind.CELL ? "done" : "work";
    }

    /** Заказ, в который вложили позицию. */
    @Transient
    public String getOrderLabel() {
        return toOrderNumber == null || toOrderNumber.isBlank() ? "—" : toOrderNumber;
    }

    /** Операция, ради которой позицию увезли со склада. */
    @Transient
    public String getOperationLabel() {
        if (toOperation == null || toOperation.getOperationName() == null
                || toOperation.getOperationName().getName() == null) {
            return "—";
        }

        return toOperation.getOperationName().getName();
    }

    private static final DateTimeFormatter DATE_TIME_FORMATTER =
            DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm");
}
