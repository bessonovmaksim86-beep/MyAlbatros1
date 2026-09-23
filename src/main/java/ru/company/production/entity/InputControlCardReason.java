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

/**
 * Причина брака, выбранная в карточке входного контроля.
 *
 * Строка — один шаг цепочки, а не одна причина на карточку: дерево
 * означает, что партию бракуют и по группе («Механическое
 * повреждение»), и по уточнению («Скол корпуса»); в карточке остаются
 * оба шага. Иначе отчёт по группе собирался бы разбором текстов.
 *
 * {@code customText} сохраняет формулировку, введённую в ступени
 * «Другое»: запись справочника появляется по написанию, а в самой
 * проверке остаётся исходный текст контролёра.
 */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "input_control_card_reasons")
public class InputControlCardReason {

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
                    name = "fk_input_control_reason_card"
            )
    )
    private InputControlCard card;

    /**
     * Группа брака, к которой относится причина.
     *
     * Причина вне группы ничего не описывает: дефект имеет смысл
     * только вместе с решением о том, куда уходят его ячейки.
     */
    @ManyToOne(
            fetch = FetchType.LAZY,
            optional = false
    )
    @JoinColumn(
            name = "group_id",
            nullable = false,
            foreignKey = @ForeignKey(
                    name = "fk_input_control_reason_group"
            )
    )
    private InputControlDefectGroup group;

    @ManyToOne(
            fetch = FetchType.LAZY,
            optional = false
    )
    @JoinColumn(
            name = "reason_id",
            nullable = false,
            foreignKey = @ForeignKey(
                    name = "fk_input_control_card_reason_ref"
            )
    )
    private InputControlDefectReason reason;

    /** Ступень в цепочке: 1 — причина первого уровня. */
    @Column(name = "level_number", nullable = false)
    private int levelNumber = 1;

    @Column(name = "custom_text", length = 500)
    private String customText;

    /**
     * Написание, которое видно в карточке: для ступени «Другое» —
     * текст контролёра, иначе наименование справочника.
     */
    @Transient
    public String getDisplayLabel() {
        if (customText != null && !customText.isBlank()) {
            return customText.trim();
        }

        return reason == null ? "—" : reason.getName();
    }
}
