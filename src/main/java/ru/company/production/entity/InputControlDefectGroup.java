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

/**
 * Группа брака: ячейки, у которых одна причина и один дальнейший
 * исход.
 *
 * Партию бракуют частями: часть ячеек возвращают поставщику по одному
 * дефекту, часть оставляют в изоляторе брака по другому. Одна причина
 * и одно решение на всю карточку такую партию описать не могут —
 * контролёр был бы вынужден выбирать меньшее зло и терять часть
 * информации о дефектах.
 *
 * Группа — не справочник, а часть проверки: карточка удаляет свои
 * группы вместе с собой, поэтому связь смотрит на карточку, а не
 * наоборот.
 */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "input_control_defect_groups")
public class InputControlDefectGroup {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(
            fetch = FetchType.LAZY,
            optional = false
    )
    @JoinColumn(
            name = "card_id",
            nullable = false,
            foreignKey = @ForeignKey(
                    name = "fk_input_control_group_card"
            )
    )
    private InputControlCard card;

    /** Исход для ячеек группы: изолятор брака либо возврат поставщику. */
    @Enumerated(EnumType.STRING)
    @Column(name = "decision", nullable = false, length = 30)
    private InputControlDecision decision;

    /** Брак признан по всей партии — отдельные номера не вводят. */
    @Column(name = "defect_all", nullable = false)
    private boolean defectAll;

    /** Порядок групп на экране: дефекты перечисляют сверху вниз. */
    @Column(name = "sort_order", nullable = false)
    private int sortOrder = 1;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    /** Подпись решения — для перечня групп в закрытой карточке. */
    @Transient
    public String getDecisionLabel() {
        return decision == null ? "не выбрано" : decision.getDisplayName();
    }

    /** Модификатор CSS-класса подписи решения. */
    @Transient
    public String getDecisionBadgeClass() {
        return decision == null ? "draft" : decision.getBadgeClass();
    }
}
