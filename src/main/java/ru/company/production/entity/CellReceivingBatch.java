package ru.company.production.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * Партия ячеек, оприходованная складом полуфабрикатов.
 *
 * Строка описывает приход целиком: поставщик, приходный ордер,
 * классификатор ячейки, количество, номер изменения и примечание.
 * Из этих данных складывается серия номеров — непрерывный диапазон
 * first_serial..last_serial, зарезервированный в момент формирования
 * партии.
 *
 * Резерв нужен потому, что в реестр (product_serials) номера
 * попадают только после сканирования. Без резерва промежуток между
 * генерацией QR-кодов и подтверждением мог бы быть занят заказом на
 * производство, и партия «пришла» бы на уже существующие номера.
 *
 * Партия без confirmed_at — черновик: она видна оператору, но изделия
 * на складе ещё не числятся.
 */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "cell_receiving_batches")
public class CellReceivingBatch {

    /*
     * Диалект Thymeleaf java8time в проект не подключён, поэтому даты
     * для шаблонов форматируются здесь — как в CustomerOrder.
     */
    private static final DateTimeFormatter DATE_TIME_FORMATTER =
            DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm");

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "supplier_name", nullable = false, length = 255)
    private String supplierName;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(
            name = "supplier_id",
            foreignKey = @ForeignKey(
                    name = "fk_cell_receiving_supplier"
            )
    )
    private WarehouseSupplier supplier;

    @Column(name = "incoming_order_number", nullable = false, length = 100)
    private String incomingOrderNumber;

    @Column(name = "change_number", nullable = false, length = 50)
    private String changeNumber;

    @Column(name = "quantity", nullable = false)
    private int quantity;

    @ManyToOne(
            fetch = FetchType.LAZY,
            optional = false
    )
    @JoinColumn(
            name = "cell_classifier_id",
            nullable = false,
            foreignKey = @ForeignKey(
                    name = "fk_cell_receiving_classifier"
            )
    )
    private ProductClassifier cellClassifier;

    @Column(name = "first_serial", nullable = false)
    private int firstSerial;

    @Column(name = "last_serial", nullable = false)
    private int lastSerial;

    @Column(name = "confirmed_at")
    private LocalDateTime confirmedAt;

    /*
     * Приёмка партии после БВК: дата, кладовщик и число принятых
     * ячеек.
     *
     * Отдельные колонки, а не производственный статус ячейки:
     * product_serials.status принадлежит выпуску и закреплён CHECK,
     * и «принята на склад» — событие склада, а не сборки. Принятых
     * ячеек может быть меньше quantity: часть отсеяна на брак в БВК.
     */
    @Column(name = "accepted_at")
    private LocalDateTime acceptedAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(
            name = "accepted_by",
            foreignKey = @ForeignKey(
                    name = "fk_cell_receiving_accepted_by"
            )
    )
    private AppUser acceptedBy;

    @Column(name = "accepted_quantity")
    private Integer acceptedQuantity;

    /*
     * Отметка «партия принята как готовые ячейки» (V75).
     *
     * Приёмка после БВК кладёт партию на склад, но позицию она даёт
     * подсборкой: ячейкой она становится по галочке «отметить как
     * готовые ячейки». Отметка держится и на партии, и на каждой
     * ячейке: перечни изолятора и брака фильтруют партии, а не строки
     * реестра (см. InputControlService.DefectRecordView#getKind).
     */
    @Column(name = "cell_ready_at")
    private LocalDateTime cellReadyAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(
            name = "cell_ready_by",
            foreignKey = @ForeignKey(
                    name = "fk_cell_receiving_cell_ready_by"
            )
    )
    private AppUser cellReadyBy;

    @Column(name = "qr_printed_at")
    private LocalDateTime qrPrintedAt;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(
            name = "created_by",
            foreignKey = @ForeignKey(
                    name = "fk_cell_receiving_author"
            )
    )
    private AppUser createdBy;

    @Column(name = "note", length = 2000)
    private String note;

    /** Номера ещё не подтверждены сканированием. */
    @Transient
    public boolean isPending() {
        return confirmedAt == null;
    }

    /** Партию принял склад после проверки БВК. */
    @Transient
    public boolean isAccepted() {
        return acceptedAt != null;
    }

    /** Партию приняли как готовые ячейки (V75). */
    @Transient
    public boolean isCellReady() {
        return cellReadyAt != null;
    }

    /** Номера переданы в программу печати. */
    @Transient
    public boolean isQrPrinted() {
        return qrPrintedAt != null;
    }

    /**
     * Полный номер ячейки по её порядковому номеру в партии:
     * код классификатора + пятизначная серия.
     */
    public String fullNumber(int serial) {
        Integer code = cellClassifier == null
                ? null
                : cellClassifier.getCode();

        return (code == null ? "" : code) + String.format("%05d", serial);
    }

    /**
     * Номера партии от первого к последнему.
     *
     * Список строится по диапазону, а не читается из реестра: до
     * подтверждения ячеек в реестре нет, а перечень нужен и для
     * печати, и для проверки сканирования.
     */
    @Transient
    public List<String> fullNumbers() {
        List<String> numbers = new ArrayList<>(quantity);

        for (int serial = firstSerial; serial <= lastSerial; serial++) {
            numbers.add(fullNumber(serial));
        }

        return numbers;
    }

    /**
     * Классификатор ячейки: «1212 — Подсборка ЯПР56» для таблиц и сводок.
     *
     * До оприходования изделием склада является подсборка: статус
     * ячейки она получает только после передачи на склад
     * полуфабрикатов. Поэтому в перечнях прихода позиция подписывается
     * как подсборка, хотя номера резервируются по её классификатору
     * так же, как у целой ячейки.
     */
    @Transient
    public String getCellLabel() {
        if (cellClassifier == null) {
            return "Классификатор не выбран";
        }

        String name = cellClassifier.getDisplayName();

        if (name == null || name.isBlank()) {
            return String.valueOf(cellClassifier.getCode());
        }

        return cellClassifier.getCode()
                + " — "
                + WarehouseNaming.subassemblyLabel(name);
    }

    /** Диапазон в полном виде: «121200022 … 121200025». */
    @Transient
    public String getNumberRangeLabel() {
        return fullNumber(firstSerial) + " … " + fullNumber(lastSerial);
    }

    /**
     * Шаг процесса, на котором находится партия.
     *
     * Статус выводится вместе с CSS-модификатором, чтобы шаблон не
     * держал соответствие «этап → цвет»: «Черновик» — номера только
     * что выпущены, «Ждёт сканирования» — этикетки напечатаны, но
     * приход не закрыт, «Оприходовано» — ячейки в реестре,
     * «Подсборка принята на склад» — партию вернулся с БВК и склад
     * принял её целиком или за вычетом брака.
     */
    @Transient
    public String getStatusLabel() {
        if (isAccepted()) {
            return "Подсборка принята на склад";
        }

        if (!isPending()) {
            return "Оприходовано";
        }

        return isQrPrinted() ? "Ждёт сканирования" : "Черновик";
    }

    @Transient
    public String getStatusBadgeClass() {
        if (isAccepted()) {
            return "accepted";
        }

        if (!isPending()) {
            return "done";
        }

        return isQrPrinted() ? "work" : "draft";
    }

    @Transient
    public String getFormattedCreatedAt() {
        return formatDateTime(createdAt);
    }

    @Transient
    public String getFormattedQrPrintedAt() {
        return formatDateTime(qrPrintedAt);
    }

    @Transient
    public String getFormattedConfirmedAt() {
        return formatDateTime(confirmedAt);
    }

    @Transient
    public String getFormattedAcceptedAt() {
        return formatDateTime(acceptedAt);
    }

    /** Момент, когда партию приняли как готовые ячейки (V75). */
    @Transient
    public String getFormattedCellReadyAt() {
        return formatDateTime(cellReadyAt);
    }

    /** Автор прихода — в журнал выводится фамилия, а не логин. */
    @Transient
    public String getAuthorName() {
        return authorNameOf(createdBy);
    }

    /** Кладовщик, принявший партию с БВК. */
    @Transient
    public String getAcceptedByName() {
        return authorNameOf(acceptedBy);
    }

    private static String authorNameOf(AppUser user) {
        if (user == null) {
            return "—";
        }

        String fullName = user.getFullName();

        return fullName == null || fullName.isBlank()
                ? user.getUsername()
                : fullName;
    }

    private static String formatDateTime(LocalDateTime value) {
        return value == null ? "—" : value.format(DATE_TIME_FORMATTER);
    }
}