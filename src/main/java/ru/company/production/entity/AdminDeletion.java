package ru.company.production.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Одна строка журнала административных удалений (миграция V73).
 *
 * Раздел «Очистка данных» удаляет записи физически, и без этой строки
 * нельзя было бы ответить на единственный вопрос, который остаётся
 * после исчезновения заказа или партии: кто это сделал и что именно
 * ушло вместе с ним.
 *
 * Ссылки на удалённую запись нет — есть идентификатор и номер текстом.
 * Внешний ключ на удалённую строку либо блокировал бы удаление
 * (RESTRICT по колонке журнала), либо стирал бы саму строку журнала
 * каскадом, и журнала как средства разбора претензий не оставалось бы.
 *
 * ФИО администратора копируют в строку, а не читают по ссылке:
 * учётную запись переименовывают или блокируют, а журнал обязан
 * оставаться читаемым за весь срок хранения. Тот же приём, что у
 * {@link SerialMovementEvent}, который копирует состояние ячейки на
 * момент события.
 */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "admin_deletions")
public class AdminDeletion {

    private static final DateTimeFormatter DATE_TIME_FORMATTER =
            DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm");

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(name = "target_kind", nullable = false, length = 30)
    private DeletionTargetKind targetKind;

    /** Идентификатор удалённой записи; внешнего ключа нет намеренно (см. V73). */
    @Column(name = "target_id")
    private Long targetId;

    /** Номер записи текстом: «ЗП-2026-014» либо реквизиты приходного ордера. */
    @Column(name = "target_label", nullable = false, length = 200)
    private String targetLabel;

    @Enumerated(EnumType.STRING)
    @Column(name = "action", nullable = false, length = 30)
    private DeletionAction action;

    /**
     * Развёртка удалённого состава: «Плановые операции цеха: 14;
     * Строки графика: 6».
     *
     * Считается ДО удаления — после него считать не по чему. Строку
     * пишут и при отказе частично удалённого состава не бывает:
     * удаление идёт в одной транзакции.
     */
    @Column(name = "affected_counts", length = 2000)
    private String affectedCounts;

    @Column(name = "note", length = 2000)
    private String note;

    /**
     * Удаление выполнено из интерфейса другой роли: здесь — логин
     * администратора, под который выполнялся вход.
     *
     * Нужен именно он, а не тот, под кем вошли: ответственность за
     * удаление несёт администратор, давший себе такие права, а
     * мастер цеха в этот момент только наблюдал экран.
     */
    @Column(name = "impersonated_by", length = 255)
    private String impersonatedBy;

    @Column(name = "actor_id")
    private Long actorId;

    @Column(name = "actor_name", nullable = false, length = 255)
    private String actorName;

    @Column(name = "performed_at", nullable = false)
    private LocalDateTime performedAt;

    /** Тип и номер удалённой записи одной строкой для ленты журнала. */
    public String getTargetLabelView() {
        return targetKind == null
                ? targetLabel
                : targetKind.getDisplayName() + " · " + targetLabel;
    }

    @Transient
    public String getFormattedPerformedAt() {
        return performedAt == null
                ? "—"
                : performedAt.format(DATE_TIME_FORMATTER);
    }
}
