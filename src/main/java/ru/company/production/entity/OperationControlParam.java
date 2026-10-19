package ru.company.production.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * Факт контролируемого параметра при проведении операции.
 *
 * Когда исполнитель проводит экземпляр операции, он обязан отметить
 * все КП, заданные технологом в ТП этой операции. Значение заполняется
 * в зависимости от режима параметра:
 *   LIST     — controlParamValue (вариант из справочника);
 *   TEXT     — textValue (свободный ввод);
 *   ЯЧЕЙКА   — cellClassifier (проверка по классификатору ячейки).
 *
 * mandatory = true — поле не может быть пустым при проведении.
 */
@Entity
@Table(name = "operation_control_params")
@Getter
@Setter
@NoArgsConstructor
public class OperationControlParam {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(
            name = "schedule_row_id",
            nullable = false,
            foreignKey = @ForeignKey(name = "fk_ocp_schedule_row")
    )
    private OrderScheduleRow scheduleRow;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(
            name = "control_param_id",
            nullable = false,
            foreignKey = @ForeignKey(name = "fk_ocp_control_param")
    )
    private ControlParam controlParam;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(
            name = "control_param_value_id",
            foreignKey = @ForeignKey(name = "fk_ocp_control_param_value")
    )
    private ControlParamValue controlParamValue;

    @Column(name = "text_value", length = 255)
    private String textValue;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(
            name = "cell_classifier_id",
            foreignKey = @ForeignKey(name = "fk_ocp_cell_classifier")
    )
    private ProductClassifier cellClassifier;

    @Column(nullable = false)
    private boolean mandatory;

    @Column(name = "completed_at")
    private LocalDateTime completedAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(
            name = "actual_classifier_id",
            foreignKey = @ForeignKey(name = "fk_ocp_actual_classifier")
    )
    private ProductClassifier actualClassifier;
}
