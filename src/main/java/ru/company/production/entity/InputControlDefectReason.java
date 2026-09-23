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
 * Причина брака в справочнике бюро входного контроля.
 *
 * Список выбираемый, а не свободный ввод: по причинам считают
 * рекламации поставщику, и «царапина», «Царапина» и «царапина на
 * корпусе» должны сходиться в одну строку отчёта.
 *
 * Дерево плоское и открытое по глубине: {@code parent} задаёт ступень
 * выше, поэтому уточнение появляется только после выбора предыдущей
 * причины.
 *
 * Ступень «Другое» помечена {@code custom}: у неё контролёр вводит
 * текст, и внесённое написание заводится в этот же справочник —
 * перечень пополняется по мере работы.
 */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "input_control_defect_reasons")
public class InputControlDefectReason {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "name", nullable = false, length = 255)
    private String name;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(
            name = "parent_id",
            foreignKey = @ForeignKey(
                    name = "fk_input_control_reason_parent"
            )
    )
    private InputControlDefectReason parent;

    /** Ступень «Другое»: требует текста и пополняет справочник. */
    @Column(name = "is_custom", nullable = false)
    private boolean custom;

    @Column(name = "active", nullable = false)
    private boolean active = true;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(
            name = "created_by",
            foreignKey = @ForeignKey(
                    name = "fk_input_control_reason_author"
            )
    )
    private AppUser createdBy;

    /*
     * Уточнения причины (следующую ступень) читают отдельным
     * запросом: ленивая коллекция при выключенном open-in-view
     * обращалась бы к базе из шаблона и прерывала рендеринг.
     */
    /** Причина первого уровня — у неё нет ступени выше. */
    @Transient
    public boolean isRoot() {
        return parent == null;
    }
}
