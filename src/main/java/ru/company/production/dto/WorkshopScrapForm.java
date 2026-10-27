package ru.company.production.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Форма браковки ячейки/подсборки руководителем цеха.
 *
 * Класс с геттерами и сеттерами, а не record: {@code th:field}
 * читает и пишет свойства по JavaBeans-соглашению, и record без
 * сеттеров форма Thymeleaf отклонила бы при первом же рендеринге.
 *
 * Номер ЗНП в форме отсутствует намеренно: его определяет матрица
 * комплектования, и переданное браузером значение пришлось бы сверять
 * с базой и отклонять при расхождении — та же работа, но с
 * недоверенным источником.
 */
@Getter
@Setter
@NoArgsConstructor
public class WorkshopScrapForm {

    /** Полный номер ячейки: 4 цифры кода + 5 цифр серии. */
    @NotBlank(message = "Укажите номер ячейки")
    @Size(max = 20, message = "Номер слишком длинный")
    private String fullNumber;

    /**
     * Номер изделия, в которое установлена ячейка.
     *
     * Факультативен: ячейку браковали и до установки, и тогда сверять
     * перемещение изделия не с чем.
     */
    @Size(max = 20, message = "Номер изделия слишком длинный")
    private String deviceNumber;

    /** Причина брака — обязательна и тренируется справочником БВК. */
    @NotBlank(message = "Укажите причину брака")
    @Size(max = 500, message = "Причина длиннее 500 символов")
    private String defectReason;

    /** Этап, на котором брак выявлен; пустой подписывает сам сервис. */
    @Size(max = 100, message = "Этап слишком длинный")
    private String defectStage;
}
