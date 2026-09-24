package ru.company.production.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

@Entity
@Table(
        name = "tech_processes",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_tech_process_code",
                columnNames = "code"
        )
)
@Getter
@Setter
@NoArgsConstructor
public class TechProcess {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 50)
    private String code;

    /*
     * Основное изделие классификатора, для которого разработан
     * маршрут. Сохраняется как «родительское» изделие техпроцесса.
     */
    @ManyToOne(
            fetch = FetchType.LAZY,
            optional = false
    )
    @JoinColumn(
            name = "product_classifier_id",
            nullable = false,
            foreignKey = @ForeignKey(
                    name = "fk_tech_process_classifier"
            )
    )
    private ProductClassifier productClassifier;

    /*
     * Все изделия, к которым применён этот маршрут.
     * «Копирование» техпроцесса добавляет сюда новое изделие,
     * не создавая отдельного техпроцесса.
     *
     * Связка хранится сущностью, а не чистой ManyToMany: у изделия
     * должно быть помечено, какой из маршрутов для него базовый
     * (basic), а какой — обычный (применённый).
     */
    @OneToMany(
            mappedBy = "techProcess",
            cascade = CascadeType.ALL,
            orphanRemoval = true,
            fetch = FetchType.LAZY
    )
    private Set<TechProcessClassifier> classifierLinks =
            new LinkedHashSet<>();

    @Column(length = 2000)
    private String note;

    @Column(nullable = false)
    private boolean active = true;

    @CreationTimestamp
    @Column(
            name = "created_at",
            nullable = false,
            updatable = false
    )
    private LocalDateTime createdAt;

    /*
     * Операции маршрута в порядке их добавления.
     */
    @OneToMany(
            mappedBy = "techProcess",
            cascade = CascadeType.ALL,
            orphanRemoval = true
    )
    @OrderBy("position ASC")
    private List<TechProcessOperation> operations = new ArrayList<>();

    @Version
    private Long version;

    public void addOperation(TechProcessOperation operation) {
        operation.setTechProcess(this);
        operations.add(operation);
    }

    public void clearOperations() {
        for (TechProcessOperation operation : operations) {
            operation.getPredecessors().clear();

            /*
             * Связь с рабочими местами владеет эта сторона,
             * поэтому её тоже снимаем явно — иначе Hibernate
             * удалит строку маршрута раньше строк связи.
             */
            operation.getWorkPlaces().clear();
        }

        operations.clear();
    }
}
