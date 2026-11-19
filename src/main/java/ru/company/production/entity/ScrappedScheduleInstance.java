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
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

/**
 * Экземпляр плана, позиция которого ушла в брак.
 *
 * Матрица комплектования (V20) связывает ячейку с экземпляром
 * позиционно: i-я по внесению ячейка принадлежит экземпляру с номером
 * i. Подтверждённый брак снимает ячейку с комплектования, то есть
 * удаляет строку матрицы, — и весь порядок съезжает: экземпляр,
 * потерявший свою ячейку, остаётся без номера (пустая строка перечня с
 * рабочей кнопкой), а ячейки, шедшие за удалённой, переезжают на чужие
 * экземпляры. Цех видит «забракованное» изделие как обычную работу и
 * проводит его дальше.
 *
 * Поэтому привязка «заказ + индекс экземпляра → забракованная позиция»
 * хранится отдельно от матрицы и переживает её сжатие: строка плана
 * обязана помнить, что изделие на этом экземпляре списано в брак, и
 * не вправе предлагать его проведение.
 *
 * Номер ячейки и причину копируют на момент снятия: заявка тем временем
 * может быть решена иначе или удалена, а подпись экземпляра обязана
 * оставаться той, что видел цех.
 *
 * Соответствует таблице scrapped_schedule_instances (миграция V90).
 */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "scrapped_schedule_instances")
public class ScrappedScheduleInstance {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /* Заказ-комплектующий, чей экземпляр списан в брак. */
    @ManyToOne(
            fetch = FetchType.LAZY,
            optional = false
    )
    @JoinColumn(
            name = "production_order_id",
            nullable = false,
            foreignKey = @ForeignKey(
                    name = "fk_ssi_production_order"
            )
    )
    private ProductionOrder productionOrder;

    /*
     * Индекс экземпляра внутри заказа (0-based, см.
     * OrderScheduleRow.instanceIndex). Он и есть потерянная позиция:
     * матрица сжимается, а экземпляр плана остаётся.
     */
    @Column(name = "instance_index", nullable = false)
    private int instanceIndex;

    /* Номер ячейки, которая стояла на этом экземпляре. */
    @Column(name = "full_number", nullable = false, length = 20)
    private String fullNumber;

    /* Причина брака — читается в перечне работ вместо кнопки. */
    @Column(name = "reason", nullable = false, length = 500)
    private String reason;

    /* Заявка, по которой экземпляр списан (может быть удалена). */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(
            name = "defect_id",
            foreignKey = @ForeignKey(name = "fk_ssi_defect")
    )
    private PendingDefect defect;

    /* Кто и когда списал экземпляр: решение бюро либо отмена брака. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "created_by")
    private AppUser createdBy;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    /**
     * Подпись экземпляра для перечня работ: «Забраковано: причина».
     *
     * Готовая строка держится здесь, а не в шаблоне: колонку читают
     * сразу два раздела («Мои операции» и распределение), и две
     * формулировки одного факта разошлись бы при первой правке.
     */
    @Transient
    public String getLabel() {
        return "Забраковано: " + (reason == null || reason.isBlank()
                ? "причина не указана"
                : reason.trim());
    }
}
