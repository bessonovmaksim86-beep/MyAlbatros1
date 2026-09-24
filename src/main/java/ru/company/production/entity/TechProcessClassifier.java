package ru.company.production.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.Objects;

/**
 * Строка связки «техпроцесс — изделие» (таблица tech_process_classifiers).
 *
 * Связка стала сущностью, а не чистой ManyToMany, потому что у изделия
 * появилось два разных отношения к маршруту:
 *
 *   basic = true  — базовый техпроцесс изделия, основной маршрут, по
 *                   которому изделие выпускают по умолчанию;
 *   basic = false — применённый (обычный) техпроцесс: тот же маршрут
 *                   назначен ещё одному изделию.
 *
 * У изделия может быть несколько техпроцессов, но базовый — только один.
 */
@Entity
@Table(
        name = "tech_process_classifiers",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_tech_process_classifier",
                columnNames = {"tech_process_id", "product_classifier_id"}
        )
)
@Getter
@Setter
@NoArgsConstructor
public class TechProcessClassifier {

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
                    name = "fk_tech_process_classifier_process"
            )
    )
    private TechProcess techProcess;

    @ManyToOne(
            fetch = FetchType.LAZY,
            optional = false
    )
    @JoinColumn(
            name = "product_classifier_id",
            nullable = false,
            foreignKey = @ForeignKey(
                    name = "fk_tech_process_classifier_classifier"
            )
    )
    private ProductClassifier classifier;

    /*
     * Базовый ли это маршрут для изделия. По умолчанию — нет:
     * «применить техпроцесс» добавляет обычный маршрут, базовым его
     * делают отдельным действием.
     */
    @Column(nullable = false)
    private boolean basic;

    public TechProcessClassifier(
            TechProcess techProcess,
            ProductClassifier classifier,
            boolean basic
    ) {
        this.techProcess = techProcess;
        this.classifier = classifier;
        this.basic = basic;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }

        if (!(other instanceof TechProcessClassifier link)) {
            return false;
        }

        return id != null && id.equals(link.getId());
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(id);
    }
}
