package ru.company.production.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * Возврат с контроля БТК — экземпляр отправлен на доработку.
 *
 * Контролёр БТК проводит операцию контроля и решает, что экземпляр
 * требует доработки: проведение со строки графика снимается, работа
 * возвращается цеху. Сроки (planned_date) не меняются — доработка
 * выполняется в те же даты, что и первоначальное выполнение.
 *
 * Одна строка на каждую возвращённую строку графика (не на экземпляр):
 * подсветка переделки должна появиться у каждого исполнителя по его
 * собственной работе, а закрытие переделки идёт через обычное
 * проведение строки плана.
 *
 * Соответствует таблице control_returns (миграция V82).
 */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "control_returns")
public class ControlReturn {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /*
     * Строка графика, проведение с которой снято.
     *
     * ON DELETE CASCADE: снять строку плана может только отмена
     * планирования, и висящий возврат по исчезнувшей работе цеху
     * не покажут.
     */
    @ManyToOne
    @JoinColumn(
            name = "schedule_row_id",
            nullable = false,
            foreignKey = @ForeignKey(
                    name = "fk_cr_schedule_row"
            )
    )
    private OrderScheduleRow scheduleRow;

    /*
     * Причина возврата, введённая контролёром БТК.
     *
     * Обязательна: экземпляр возвращается в цех уже проведённой
     * работой, и без причины исполнитель не понял бы, что именно
     * переделывать.
     */
    @Column(name = "reason", nullable = false, length = 500)
    private String reason;

    /* Этап/операция контроля, с которой экземпляр возвращён. */
    @Column(name = "control_operation", length = 255)
    private String controlOperation;

    /* Кто и когда вернул с контроля. */
    @ManyToOne
    @JoinColumn(
            name = "returned_by",
            nullable = false,
            foreignKey = @ForeignKey(
                    name = "fk_cr_returned_by"
            )
    )
    private AppUser returnedBy;

    @Column(name = "returned_at", nullable = false)
    private LocalDateTime returnedAt;

    /*
     * Переделка закрыта: строка проведена повторно.
     *
     * Пустое значение — открытая переделка, по ней цех видит
     * подсветку, а БТК — счётчик возвращённых.
     */
    @Column(name = "resolved_at")
    private LocalDateTime resolvedAt;

    @ManyToOne
    @JoinColumn(
            name = "resolved_by",
            foreignKey = @ForeignKey(
                    name = "fk_cr_resolved_by"
            )
    )
    private AppUser resolvedBy;

    /** Переделка ещё не закрыта — по этому признаку цех подсвечивает работу. */
    public boolean isOpen() {
        return resolvedAt == null;
    }
}
