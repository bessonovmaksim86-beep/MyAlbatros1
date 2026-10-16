package ru.company.production.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Вариант контролируемого параметра.
 * Пример: для параметра «Трубка» — «1 слой(13мм.)», «2 слоя(13мм)».
 */
@Entity
@Table(
        name = "control_param_values",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_cp_values_param_value",
                columnNames = {"control_param_id", "value"}
        )
)
@Getter
@Setter
@NoArgsConstructor
public class ControlParamValue {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /*
     * orphanRemoval здесь невозможен: у @ManyToOne его нет, а удаление
     * варианта обеспечивает обратная сторона — @OneToMany у
     * ControlParam — и ON DELETE CASCADE в схеме.
     */
    @ManyToOne(
            fetch = FetchType.LAZY,
            optional = false
    )
    @JoinColumn(
            name = "control_param_id",
            nullable = false,
            foreignKey = @ForeignKey(
                    name = "fk_cp_values_param"
            )
    )
    private ControlParam controlParam;

    @Column(nullable = false, length = 255)
    private String value;
}
