package ru.company.production.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Контролируемый параметр операции маршрута.
 *
 * Строка блока «Контролируемые параметры» состоит из вида параметра
 * (например «Внесение номера ячейки»), его значения (например
 * «1 слойная 14мм») и отметки обязательности. И вид, и значение
 * выбирают из справочника технолога «Контролируемые параметры»,
 * поэтому в маршруте хранятся только ссылки на его записи.
 */
@Entity
@Table(
        name = "tech_process_operation_control_params",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_tp_operation_control_param",
                columnNames = {
                        "tech_process_operation_id",
                        "control_param_value_id"
                }
        )
)
@Getter
@Setter
@NoArgsConstructor
public class TechProcessOperationControlParam {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(
            fetch = FetchType.LAZY,
            optional = false
    )
    @JoinColumn(
            name = "tech_process_operation_id",
            nullable = false,
            foreignKey = @ForeignKey(
                    name = "fk_tp_op_control_param_operation"
            )
    )
    private TechProcessOperation techProcessOperation;

    /*
     * Вид параметра — строка справочника «Контролируемые параметры».
     * Хранится отдельно от значения: при выборе значения форма
     * отдаёт только его идентификатор, а подпись вида нужна и для
     * отображения маршрута, и для проверки на повтор.
     */
    @ManyToOne(
            fetch = FetchType.LAZY,
            optional = false
    )
    @JoinColumn(
            name = "control_param_id",
            nullable = false,
            foreignKey = @ForeignKey(
                    name = "fk_tp_op_control_param_param"
            )
    )
    private ControlParam controlParam;

    /*
     * Значение параметра — вариант выбранного вида.
     *
     * Заполнено только у параметра типа LIST со стандартным списком
     * значений. У режима «входящие ячейки» значение хранится в
     * cellClassifierId, у TEXT_INPUT — в textValue.
     */
    @ManyToOne(
            fetch = FetchType.LAZY,
            optional = true
    )
    @JoinColumn(
            name = "control_param_value_id",
            foreignKey = @ForeignKey(
                    name = "fk_tp_op_control_param_value"
            )
    )
    private ControlParamValue controlParamValue;

    /*
     * Значение параметра со свободным вводом (тип TEXT_INPUT):
     * номер ячейки или серийник исполнитель вписывает сам, поэтому
     * справочник его не перечисляет.
     */
    @Column(name = "text_value", length = 255)
    private String textValue;

    /*
     * Ячейка — для режима «входящие ячейки»: когда в названии
     * контролируемого параметра есть слово «ячейка», значение
     * выбирают из классификатора входящих ячеек датчика.
     */
    @ManyToOne(
            fetch = FetchType.LAZY,
            optional = true
    )
    @JoinColumn(
            name = "cell_classifier_id",
            foreignKey = @ForeignKey(
                    name = "fk_tp_op_control_param_cell"
            )
    )
    private ProductClassifier cellClassifier;

    /*
     * Отметка «обязательное поле»: исполнитель обязан внести
     * значение параметра при выполнении операции.
     */
    @Column(nullable = false)
    private boolean mandatory;
}
