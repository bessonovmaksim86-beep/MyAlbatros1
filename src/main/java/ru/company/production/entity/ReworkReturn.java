package ru.company.production.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
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
 * Возврат на доработку — изделие возвращено цеху без снятия проведения.
 *
 * В отличие от {@link ControlReturn}, здесь проведение со строк графика
 * не снимается: изделие просто помечается как «на доработке», а цех
 * повторяет только выбранные операции. Факт возврата фиксируется для
 * истории, аналитики брака и анализа возвратов.
 *
 * Типы возврата:
 * <ul>
 *   <li>INTERNAL — возврат в текущий цех (начальник цеха выбирает операции)</li>
 *   <li>PREDECESSOR — отправка на предшествующую операцию/цех</li>
 * </ul>
 *
 * Соответствует таблице rework_returns (миграция V84).
 */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "rework_returns")
public class ReworkReturn {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /*
     * Строка графика, возвращаемая на доработку.
     *
     * ON DELETE CASCADE: строка плана исчезает — возврат теряет смысл.
     */
    @ManyToOne
    @JoinColumn(
            name = "schedule_row_id",
            nullable = false,
            foreignKey = @ForeignKey(
                    name = "fk_rr_schedule_row"
            )
    )
    private OrderScheduleRow scheduleRow;

    /*
     * Причина возврата, введённая начальником цеха или контролёром.
     */
    @Column(name = "reason", nullable = false, length = 500)
    private String reason;

    /*
     * Тип возврата: INTERNAL — в текущий цех, PREDECESSOR — на пред. цех.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "return_type", nullable = false, length = 30)
    private ReturnType returnType;

    /*
     * Статус возврата: OPEN — ожидает действий, IN_REWORK — на переделке,
     * COMPLETED — операции проведены повторно.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private ReworkStatus status = ReworkStatus.OPEN;

    /*
     * Распространить на следующие операции маршрута:
     * если true, все проведённые после текущей операции в том же цехе
     * тоже помечаются для повторного проведения.
     */
    @Column(name = "cascade_to_following", nullable = false)
    private boolean cascadeToFollowing = false;

    /*
     * Кто и когда вернул на доработку.
     */
    @ManyToOne
    @JoinColumn(
            name = "returned_by",
            nullable = false,
            foreignKey = @ForeignKey(
                    name = "fk_rr_returned_by"
            )
    )
    private AppUser returnedBy;

    @Column(name = "returned_at", nullable = false)
    private LocalDateTime returnedAt;

    /*
     * Когда доработка завершена (операции проведены повторно).
     */
    @Column(name = "resolved_at")
    private LocalDateTime resolvedAt;

    @ManyToOne
    @JoinColumn(
            name = "resolved_by",
            foreignKey = @ForeignKey(
                    name = "fk_rr_resolved_by"
            )
    )
    private AppUser resolvedBy;

    /** Возврат ещё не закрыт — по этому признаку цех видит работу в списке. */
    public boolean isOpen() {
        return status == ReworkStatus.OPEN;
    }

    public enum ReturnType {
        INTERNAL("В текущий цех"),
        PREDECESSOR("В предшествующий цех");

        private final String displayName;

        ReturnType(String displayName) {
            this.displayName = displayName;
        }

        public String getDisplayName() {
            return displayName;
        }
    }

    public enum ReworkStatus {
        OPEN("Ожидает"),
        IN_REWORK("На доработке"),
        COMPLETED("Завершён");

        private final String displayName;

        ReworkStatus(String displayName) {
            this.displayName = displayName;
        }

        public String getDisplayName() {
            return displayName;
        }
    }
}
