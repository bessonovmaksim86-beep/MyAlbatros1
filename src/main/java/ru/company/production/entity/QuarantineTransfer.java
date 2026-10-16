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
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Ячейка, перемещённая из изолятора брака на приёмку складу
 * полуфабрикатов.
 *
 * Строка живёт от перемещения до приёмки: бюро входного контроля её
 * заводит, склад закрывает отметкой «принято» либо возвратом с
 * указанием, почему ячейка не принята. Держать два документа вместо
 * одного значило бы сверять их по номеру при каждом чтении перечня.
 *
 * Ячейка хранится полным номером, а не ссылкой на {@link ProductSerial}
 * — по тому же мотиву, что состав ремонтного ЗНП (V52) и строка заказа
 * на ремонт (V34): в изолятор попадают и номера партий прихода, ещё не
 * внесённые в реестр, поэтому ссылка на реестр не покрывала бы половину
 * состава изолятора.
 *
 * Разновидность ({@link #cellKind}) копируется на момент перемещения:
 * стадия движения со временем меняется (подсборка после приёмки складом
 * становится ячейкой, см. {@link QuarantineCellKind}), а перечень склада
 * обязан показывать то, что контролёр видел на экране изолятора.
 *
 * Соответствует таблице quarantine_transfers (миграция V63).
 */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "quarantine_transfers")
public class QuarantineTransfer {

    private static final DateTimeFormatter DATE_TIME_FORMATTER =
            DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm");

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /* Полный номер ячейки: 4 цифры кода + 5 цифр серии. */
    @Column(name = "full_number", nullable = false, length = 20)
    private String fullNumber;

    /* SUBASSEMBLY, CELL — разновидность на момент перемещения. */
    @Enumerated(EnumType.STRING)
    @Column(name = "cell_kind", nullable = false, length = 20)
    private QuarantineCellKind cellKind;

    /*
     * Классификатор ячейки — наименование для перечня склада.
     * NULL допустим: группу брака заводят и без привязки к
     * классификатору (номер вводят вручную).
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(
            name = "product_classifier_id",
            foreignKey = @ForeignKey(
                    name = "fk_quarantine_transfer_classifier"
            )
    )
    private ProductClassifier productClassifier;

    /* Партия прихода, из которой ячейка попала в изолятор. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(
            name = "receiving_batch_id",
            foreignKey = @ForeignKey(
                    name = "fk_quarantine_transfer_batch"
            )
    )
    private CellReceivingBatch receivingBatch;

    /*
     * Группа брака — по ней ячейку находят обратно в изоляторе, если
     * склад вернул её, не приняв на склад.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(
            name = "defect_group_id",
            foreignKey = @ForeignKey(
                    name = "fk_quarantine_transfer_group"
            )
    )
    private InputControlDefectGroup defectGroup;

    /* Причина перемещения, введённая контролёром бюро. */
    @Column(name = "reason", nullable = false, length = 500)
    private String reason;

    /* Кто и когда переместил ячейку из изолятора. */
    @ManyToOne(
            fetch = FetchType.LAZY,
            optional = false
    )
    @JoinColumn(
            name = "transferred_by",
            nullable = false,
            foreignKey = @ForeignKey(
                    name = "fk_quarantine_transfer_by"
            )
    )
    private AppUser transferredBy;

    @Column(name = "transferred_at", nullable = false)
    private LocalDateTime transferredAt;

    /*
     * Приёмка складом. Отметка стоит на той же строке: перемещение и
     * его приёмка — один документ в двух состояниях.
     */
    @Column(name = "accepted", nullable = false)
    private boolean accepted;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(
            name = "accepted_by",
            foreignKey = @ForeignKey(
                    name = "fk_quarantine_transfer_accepted_by"
            )
    )
    private AppUser acceptedBy;

    @Column(name = "accepted_at")
    private LocalDateTime acceptedAt;

    /*
     * Подписи готовятся здесь, а не в шаблоне: связи ленивые, а
     * open-in-view выключен — обращение страницы к transferredBy или
     * productClassifier упёрлось бы в ленивую загрузку вне сессии.
     */
    @Transient
    public boolean isPending() {
        return !accepted;
    }

    @Transient
    public String getCellKindLabel() {
        return cellKind == null ? "—" : cellKind.getDisplayName();
    }

    /** Наименование изделия без обращения к ленивой связи вне сессии. */
    @Transient
    public String getClassifierLabel() {
        if (productClassifier == null) {
            return "—";
        }

        String name = productClassifier.getDisplayName();

        return name == null || name.isBlank()
                ? String.valueOf(productClassifier.getCode())
                : productClassifier.getCode() + " — " + name;
    }

    /** Приходный ордер партии, по которому ячейку приняли на склад. */
    @Transient
    public String getIncomingOrderNumber() {
        return receivingBatch == null
                ? "—"
                : receivingBatch.getIncomingOrderNumber();
    }

    /** Поставщик партии — по нему ячейку ищут при приёмке. */
    @Transient
    public String getSupplierName() {
        return receivingBatch == null ? "—" : receivingBatch.getSupplierName();
    }

    @Transient
    public String getTransferredByLabel() {
        return userNameOf(transferredBy);
    }

    @Transient
    public String getTransferredAtLabel() {
        return formatDateTime(transferredAt);
    }

    @Transient
    public String getAcceptedByLabel() {
        return userNameOf(acceptedBy);
    }

    @Transient
    public String getAcceptedAtLabel() {
        return formatDateTime(acceptedAt);
    }

    private static String userNameOf(AppUser user) {
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
