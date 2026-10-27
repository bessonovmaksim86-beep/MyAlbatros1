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
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Заявка на отбраковку ячейки/подсборки с согласованием БВК.
 *
 * Когда ячейку или подсборку, находящуюся в цеху/отделе, нужно
 * направить в изолятор брака, оператор цеха создаёт заявку.
 * Заявка попадает на согласование представителю БВК (BVK_MASTER),
 * который принимает решение:
 * - подтвердить перемещение в изолятор,
 * - отклонить брак и направить ячейку на склад полуфабрикатов.
 *
 * full_number — работает и для номеров из product_serials, и для
 * номеров прихода, ещё не внесённых в реестр (аналогично
 * QuarantineTransfer).
 *
 * current_location — «снимок» местоположения на момент создания,
 * вычисляется из последнего события SerialMovementEvent.
 *
 * znp_number — номер ЗНП, из которого забракована ячейка. Нужен
 * для доукомплектования заказа: при подтверждении брака строка
 * SerialProductionOrder снимается, а ячейка возвращается на склад
 * для повторного комплектования.
 *
 * controller_comment — комментарий контроллера при принятии решения.
 *
 * defect_confirmed — подтверждён ли дефект: CONFIRMED → изолятор,
 * DENIED → возврат на склад. NULL = решение ещё не принято.
 *
 * Соответствует таблице pending_defects (миграции V74, V80).
 */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "pending_defects")
public class PendingDefect {

    private static final DateTimeFormatter DATE_TIME_FORMATTER =
            DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm");

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /* Полный номер ячейки/подсборки: 4 цифры кода + 5 цифр серии. */
    @Column(name = "full_number", nullable = false, length = 20)
    private String fullNumber;

    /* CELL / SUBASSEMBLY — на момент создания заявки. */
    @Enumerated(EnumType.STRING)
    @Column(name = "cell_kind", nullable = false, length = 20)
    private QuarantineCellKind cellKind;

    /* Статус реестра на момент создания заявки. */
    @Enumerated(EnumType.STRING)
    @Column(name = "serial_status", length = 20)
    private ProductSerialStatus serialStatus;

    /* Изделие — для отображения и отбора. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(
            name = "product_classifier_id",
            foreignKey = @ForeignKey(
                    name = "fk_pending_defect_classifier"
            )
    )
    private ProductClassifier productClassifier;

    /* Партия прихода — для отображения. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(
            name = "receiving_batch_id",
            foreignKey = @ForeignKey(
                    name = "fk_pending_defect_batch"
            )
    )
    private CellReceivingBatch receivingBatch;

    /* Местоположение на момент создания: «Цех №3», «Склад СПФ» и т. п. */
    @Column(name = "current_location", nullable = false, length = 200)
    private String currentLocation;

    /* Причина брака (строка, 500 символов). */
    @Column(name = "defect_reason", nullable = false, length = 500)
    private String defectReason;

    /* Этап отбраковки (100 символов). */
    @Column(name = "defect_stage", nullable = false, length = 100)
    private String defectStage;

    /* Кто затребовал отбраковку. */
    @ManyToOne(
            fetch = FetchType.LAZY,
            optional = false
    )
    @JoinColumn(
            name = "requested_by_id",
            nullable = false,
            foreignKey = @ForeignKey(
                    name = "fk_pending_defect_requested_by"
            )
    )
    private AppUser requestedBy;

    /* Дата/время создания заявки. */
    @CreationTimestamp
    @Column(name = "requested_at", nullable = false)
    private LocalDateTime requestedAt;

    /* Решение: переместить в изолятор или отклонить брак. NULL = ожидает. */
    @Enumerated(EnumType.STRING)
    @Column(name = "decision", length = 30)
    private PendingDefectDecision decision;

    /* Кто принял решение. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(
            name = "approved_by_id",
            foreignKey = @ForeignKey(
                    name = "fk_pending_defect_approved_by"
            )
    )
    private AppUser approvedBy;

    /* Когда принято решение. */
    @Column(name = "approved_at")
    private LocalDateTime approvedAt;

    /*
     * Полный номер изделия, в которое была установлена ячейка.
     *
     * Вводится руководителем цеха вручную: по нему проверка убеждается,
     * что прибор/датчик вместе с ячейкой ещё числится в этом цеху.
     * Если изделие уже перемещено — заявка отклоняется.
     */
    @Column(name = "device_number", length = 20)
    private String deviceNumber;

    /*
     * Номер ЗНП, из которого забракована ячейка.
     *
     * Заполняется автоматически при создании заявки из цеха:
     * сервис ищет SerialProductionOrder по номеру ячейки и
     * возвращает номер ЗНП. Нужен для доукомплектования заказа:
     * при подтверждении брака строка снимается, ячейка возвращается
     * на склад для повторного комплектования.
     */
    @Column(name = "znp_number", length = 100)
    private String znpNumber;

    /*
     * Комментарий контроллера при принятии решения.
     *
     * Заполняется представителем БВК при подтверждении или отклонении
     * брака. Сохраняется в истории заявки и попадает в паспорт ячейки.
     */
    @Column(name = "controller_comment", length = 1000)
    private String controllerComment;

    /*
     * Подтверждён ли дефект: CONFIRMED → изолятор брака,
     * DENIED → возврат на склад полуфабрикатов.
     *
     * NULL — решение ещё не принято. Заменяет собой PendingDefectDecision
     * на уровне бизнес-логики отбраковки: это внутренний статус заявки,
     * а PendingDefectDecision — решение по согласованию.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "defect_confirmed", length = 20)
    private DefectConfirmed defectConfirmed;

    /*
     * Связанный серийный номер — для быстрых проверок.
     *
     * При создании заявки из цеха сервис резолвит full_number в
     * ProductSerial и сохраняет ссылку. У номеров прихода (ещё не
     * в реестре) остаётся null.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(
            name = "serial_id",
            foreignKey = @ForeignKey(
                    name = "fk_pending_defect_serial"
            )
    )
    private ProductSerial serial;

    /** Заявка ожидает решения. */
    @Transient
    public boolean isPending() {
        return decision == null;
    }

    /** Заявка решена. */
    @Transient
    public boolean isResolved() {
        return decision != null;
    }

    @Transient
    public String getFormattedRequestedAt() {
        return formatDateTime(requestedAt);
    }

    @Transient
    public String getFormattedApprovedAt() {
        return formatDateTime(approvedAt);
    }

    @Transient
    public String getRequestedByLabel() {
        return userNameOf(requestedBy);
    }

    @Transient
    public String getApprovedByLabel() {
        return approvedBy == null ? "—" : userNameOf(approvedBy);
    }

    @Transient
    public String getDecisionLabel() {
        return decision == null ? "Ожидает" : decision.getDisplayName();
    }

    @Transient
    public String getDecisionBadgeClass() {
        if (decision == null) {
            return "warning";
        }
        return decision.getBadgeClass();
    }

    /**
     * Вывод контроллера: подтверждён дефект или нет.
     *
     * Читается шаблоном вместо {@link #getDecisionLabel()}: направление
     * у обоих решений одно (изолятор брака), поэтому для перечня значим
     * именно вердикт, а не маршрут.
     */
    @Transient
    public String getDefectConfirmedLabel() {
        return defectConfirmed == null ? "Ожидает" : defectConfirmed.getDisplayName();
    }

    @Transient
    public String getDefectConfirmedBadgeClass() {
        return defectConfirmed == null ? "warning" : defectConfirmed.getBadgeClass();
    }

    /** Номер изделия, с которого сняли ячейку. */
    @Transient
    public String getDeviceNumberLabel() {
        return deviceNumber == null || deviceNumber.isBlank() ? "—" : deviceNumber;
    }

    /** Заказ, из которого забракована ячейка. */
    @Transient
    public String getZnpNumberLabel() {
        return znpNumber == null || znpNumber.isBlank() ? "—" : znpNumber;
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
    public String getCellKindLabel() {
        return cellKind == null ? "—" : cellKind.getDisplayName();
    }

    @Transient
    public String getSerialStatusLabel() {
        return serialStatus == null ? "—" : serialStatus.getDisplayName();
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
