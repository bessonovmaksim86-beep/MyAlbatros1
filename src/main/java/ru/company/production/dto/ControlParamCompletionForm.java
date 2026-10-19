package ru.company.production.dto;

import lombok.Getter;
import lombok.Setter;

import java.util.ArrayList;
import java.util.List;

/**
 * Форма проведения экземпляра с отметкой контролируемых параметров.
 *
 * Обёртка нужна из-за нумерованного индекса в именах полей: Spring
 * связывает {@code values[0].paramId} со списком только когда у
 * формы есть это свойство — разметка {@code th:field} над отдельной
 * строкой список не соберёт.
 *
 * Порядок {@code values} задаёт шаблон по порядку параметров,
 * поэтому сервис сопоставляет отметку с параметром по {@code paramId},
 * а не по индексу: пропущенная строка не сдвигала бы отметки
 * остальных параметров.
 */
@Getter
@Setter
public class ControlParamCompletionForm {

    private List<ControlParamValueForm> values = new ArrayList<>();
}
