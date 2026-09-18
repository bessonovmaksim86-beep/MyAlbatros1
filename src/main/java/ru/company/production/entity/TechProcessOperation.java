package ru.company.production.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.util.LinkedHashSet;
import java.util.Set;

@Entity
@Table(
        name = "tech_process_operations",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_tech_process_operation",
                columnNames = {"tech_process_id", "operation_id"}
        )
)
@Getter
@Setter
@NoArgsConstructor
public class TechProcessOperation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(
            fetch = FetchType.LAZY,
            optional = false
    )
    @JoinColumn(
            name = "tech_process_id",
            nullable = false,
            foreignKey = @ForeignKey(
                    name = "fk_tp_operation_process"
            )
    )
    private TechProcess techProcess;

    /*
     * Действующая операция из справочника.
     */
    @ManyToOne(
            fetch = FetchType.LAZY,
            optional = false
    )
    @JoinColumn(
            name = "operation_id",
            nullable = false,
            foreignKey = @ForeignKey(
                    name = "fk_tp_operation_operation"
            )
    )
    private Operation operation;

    @Column(nullable = false)
    private Integer position;

    /*
     * Трудоёмкость, человеко/часы (1 час = 1).
     */
    @Column(
            name = "labor_hours",
            nullable = false,
            precision = 10,
            scale = 3
    )
    private BigDecimal laborHours;

    /*
     * Машиновремя, часы (1 час = 1).
     */
    @Column(
            name = "machine_hours",
            nullable = false,
            precision = 10,
            scale = 3
    )
    private BigDecimal machineHours;

    /*
     * Максимальное количество выполнений операции в день
     * (пропускная способность оборудования).
     */
    @Column(
            name = "daily_limit",
            nullable = false
    )
    private Integer dailyLimit;

    /*
     * Описание выполнения операции в составе данного маршрута.
     */
    @Column(length = 2000)
    private String note;

    /*
     * Операции, которые должны быть выполнены раньше данной.
     */
    @ManyToMany(fetch = FetchType.LAZY)
    @JoinTable(
            name = "tech_process_operation_deps",
            joinColumns = @JoinColumn(
                    name = "successor_id",
                    foreignKey = @ForeignKey(
                            name = "fk_tp_dep_successor"
                    )
            ),
            inverseJoinColumns = @JoinColumn(
                    name = "predecessor_id",
                    foreignKey = @ForeignKey(
                            name = "fk_tp_dep_predecessor"
                    )
            )
    )
    private Set<TechProcessOperation> predecessors =
            new LinkedHashSet<>();

    /*
     * Рабочие места, на которых допускается выполнение операции.
     * Маршрут не считается корректным без хотя бы одного места.
     */
    @ManyToMany(fetch = FetchType.LAZY)
    @JoinTable(
            name = "tech_process_operation_work_places",
            joinColumns = @JoinColumn(
                    name = "tech_process_operation_id",
                    foreignKey = @ForeignKey(
                            name = "fk_tp_operation_work_place_operation"
                    )
            ),
            inverseJoinColumns = @JoinColumn(
                    name = "work_place_id",
                    foreignKey = @ForeignKey(
                            name = "fk_tp_operation_work_place_place"
                    )
            )
    )
    private Set<WorkPlace> workPlaces = new LinkedHashSet<>();
}
