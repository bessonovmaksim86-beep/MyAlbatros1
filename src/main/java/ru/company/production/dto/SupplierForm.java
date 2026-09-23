package ru.company.production.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Новая запись справочника поставщиков склада.
 *
 * Имя — единственное обязательное поле: сводка считается по партиям,
 * и поставщик без единого прихода остаётся просто подсказкой для
 * формы оприходования.
 */
@Getter
@Setter
@NoArgsConstructor
public class SupplierForm {

    @NotBlank(message = "Укажите наименование поставщика")
    @Size(max = 255, message = "Наименование: не более 255 символов")
    private String name;

    @Size(max = 1000, message = "Примечание: не более 1000 символов")
    private String note;
}
