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
import jakarta.persistence.OneToMany;
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
 * Карточка входного контроля оприходованной партии.
 *
 * Основанием служит партия прихода склада
 * ({@link CellReceivingBatch}): её реквизиты и есть карточка
 * оприходования, которую контролёр видит в начале проверки.
 *
 * Проверка выборочная: в список выборочного контроля попадают только
 * осмотренные ячейки, и их заведомо меньше, чем номеров в партии.
 * Брак описывается отдельно и только вместе с причиной — партию без
 * причины брака не бракуют.
 *
 * Открытая карточка на партию одна: «взять партию в работу» дважды
 * нельзя, иначе два контролёра заполняли бы два разных результата
 * одной проверки.
 */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "input_control_cards")
public class InputControlCard {

    private static final DateTimeFormatter DATE_TIME_FORMATTER =
            DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm");

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(
            fetch = FetchType.LAZY,
            optional = false
    )
    @JoinColumn(
            name = "batch_id",
            nullable = false,
            foreignKey = @ForeignKey(
                    name = "fk_input_control_card_batch"
            )
    )
    private CellReceivingBatch batch;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private InputControlStatus status = InputControlStatus.IN_WORK;

    /** Выборочный контроль проставлен по всей партии. */
    @Column(name = "sample_all", nullable = false)
    private boolean sampleAll;

    /**
     * Вид контроля, которым закрыли проверку.
     *
     * Заполняется вместе с завершением: открытая карточка вида ещё не
     * имеет, потому что контроль не проведён.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "input_control_type", length = 20)
    private InputControlType inputControlType;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(
            name = "checked_by",
            foreignKey = @ForeignKey(
                    name = "fk_input_control_card_checker"
            )
    )
    private AppUser checkedBy;

    @CreationTimestamp
    @Column(name = "started_at", nullable = false, updatable = false)
    private LocalDateTime startedAt;

    @Column(name = "completed_at")
    private LocalDateTime completedAt;

    @Column(name = "note", length = 2000)
    private String note;

    @OneToMany(mappedBy = "card", orphanRemoval = true)
    private List<InputControlCardCell> cells = new ArrayList<>();

    /**
     * Группы брака в порядке заполнения.
     *
     * Коллекция ленивая и из шаблона не читается: {@code open-in-view}
     * выключен, поэтому перечень групп карточка получает от сервиса
     * отдельным запросом с {@code join fetch}.
     */
    @OneToMany(mappedBy = "card", orphanRemoval = true)
    private List<InputControlDefectGroup> defectGroups = new ArrayList<>();

    @OneToMany(mappedBy = "card", orphanRemoval = true)
    private List<InputControlCardReason> reasons = new ArrayList<>();

    /** Карточку ещё не закрыли — она и есть «партия в работе». */
    @Transient
    public boolean isInWork() {
        return status == InputControlStatus.IN_WORK;
    }

    /*
     * Списки номеров в карточку передаёт сервис: коллекции ленивые, а
     * open-in-view выключен, поэтому обращение к ним из шаблона
     * прервало бы рендеринг страницы.
     */
    @Transient
    public String getStatusLabel() {
        return status == null ? "—" : status.getDisplayName();
    }

    @Transient
    public String getStatusBadgeClass() {
        return status == null ? "draft" : status.getBadgeClass();
    }

    @Transient
    public String getFormattedStartedAt() {
        return format(startedAt);
    }

    @Transient
    public String getFormattedCompletedAt() {
        return format(completedAt);
    }

    /** Контролёр — в карточку выводится фамилия, а не логин. */
    @Transient
    public String getCheckerName() {
        if (checkedBy == null) {
            return "—";
        }

        String fullName = checkedBy.getFullName();

        return fullName == null || fullName.isBlank()
                ? checkedBy.getUsername()
                : fullName;
    }

    /** Вид контроля в итог закрытой проверки. */
    @Transient
    public String getInputControlTypeLabel() {
        return inputControlType == null
                ? "контроль не проведён"
                : inputControlType.getDisplayName();
    }

    private static String format(LocalDateTime value) {
        return value == null ? "—" : value.format(DATE_TIME_FORMATTER);
    }
}
