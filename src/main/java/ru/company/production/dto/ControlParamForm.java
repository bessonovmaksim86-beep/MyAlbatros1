package ru.company.production.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import ru.company.production.entity.ControlParamType;

import java.util.ArrayList;
import java.util.List;

/**
 * Форма создания и изменения контролируемого параметра.
 *
 * Тип параметра выбирают при создании: LIST — со списком вариантов,
 * TEXT_INPUT — со свободным вводом. У текстового параметра вариантов
 * нет, и блок вариантов форма скрывает.
 *
 * Состав вариантов определяют по их идентификаторам, а не по
 * служебным флажкам: строки формы рисует шаблон, и пользователь
 * всегда видит под последним вариантом пустую строку для нового
 * значения. Пустую строку сервис отбрасывает, вариант без
 * идентификатора добавляет, а отсутствующий в отправленной форме
 * сохранённый вариант удаляет.
 */
public class ControlParamForm {

    @NotBlank(message = "Укажите наименование параметра")
    @Size(
            max = 255,
            message = "Наименование не должно превышать 255 символов"
    )
    private String name;

    /*
     * Список по умолчанию: большинство параметров заводят именно
     * справочником вариантов, и не выбранное значение не должно
     * ломать форму.
     */
    @NotNull(message = "Выберите тип параметра")
    private ControlParamType type = ControlParamType.LIST;

    private List<ValueItem> values = new ArrayList<>();

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public ControlParamType getType() {
        return type;
    }

    public void setType(ControlParamType type) {
        this.type = type;
    }

    public List<ValueItem> getValues() {
        return values;
    }

    public void setValues(List<ValueItem> values) {
        this.values = values;
    }

    public static class ValueItem {

        private Long id;

        @Size(
                max = 255,
                message = "Значение не должно превышать 255 символов"
        )
        private String value;

        public Long getId() {
            return id;
        }

        public void setId(Long id) {
            this.id = id;
        }

        public String getValue() {
            return value;
        }

        public void setValue(String value) {
            this.value = value;
        }
    }
}
