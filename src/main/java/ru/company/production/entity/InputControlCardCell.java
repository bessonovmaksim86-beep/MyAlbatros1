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
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Номер ячейки, внесённый в карточку входного контроля.
 *
 * Один и тот же по составу список служит двум перечням — проверенных
 * выборочно и забракованных; их различает {@link #cellType}.
 *
 * Номер хранится строкой, а не ссылкой на {@link ProductSerial}:
 * карточку заполняют с этикетки или сканера, и сверка идёт по
 * полному номеру партии. Ссылка на реестр поставила бы результат
 * контроля в зависимость от записи, которую производство меняет
 * независимо.
 */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "input_control_card_cells")
public class InputControlCardCell {

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
                    name = "fk_input_control_cell_card"
            )
    )
    private InputControlCard card;

    /**
     * Группа брака для забракованного номера; {@code null} у ячеек
     * выборочного контроля — они никуда не перемещаются.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(
            name = "group_id",
            foreignKey = @ForeignKey(
                    name = "fk_input_control_cell_group"
            )
    )
    private InputControlDefectGroup group;

    @Column(name = "full_number", nullable = false, length = 20)
    private String fullNumber;

    @Enumerated(EnumType.STRING)
    @Column(name = "cell_type", nullable = false, length = 10)
    private InputControlCellType cellType;

    public InputControlCardCell(InputControlCard card,
                                String fullNumber,
                                InputControlCellType cellType) {
        this.card = card;
        this.fullNumber = fullNumber;
        this.cellType = cellType;
    }

    public InputControlCardCell(InputControlDefectGroup group,
                                String fullNumber) {
        this.card = group.getCard();
        this.group = group;
        this.fullNumber = fullNumber;
        this.cellType = InputControlCellType.DEFECT;
    }
}
