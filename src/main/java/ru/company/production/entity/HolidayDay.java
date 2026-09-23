package ru.company.production.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * Выходной или праздничный день производственного календаря.
 *
 * Справочник ведёт администратор: нерабочие дни одинаковы для всего
 * предприятия, и отмечать их каждому руководителю цеха по отдельности
 * означало бы расхождение календарей при первом же пропуске. Запись
 * отвечает только на вопрос «рабочий ли день»; отметки сотрудникам
 * создаёт {@code HolidayService} при добавлении дня.
 */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(
        name = "holiday_days",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_holiday_day_date",
                columnNames = {"holiday_date"}
        )
)
public class HolidayDay {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(
            name = "holiday_date",
            nullable = false
    )
    private LocalDate holidayDate;

    @Enumerated(EnumType.STRING)
    @Column(
            name = "kind",
            nullable = false,
            length = 20
    )
    private HolidayKind kind;

    @Column(
            name = "name",
            nullable = false,
            length = 200
    )
    private String name;

    @CreationTimestamp
    @Column(
            name = "created_at",
            nullable = false,
            updatable = false
    )
    private LocalDateTime createdAt;
}
