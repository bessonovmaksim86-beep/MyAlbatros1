package ru.company.production.dto;

import lombok.Getter;
import lombok.Setter;

/**
 * Значение контролируемого параметра, отправленное исполнителем.
 *
 * Идентификатор — строка КП в маршруте
 * ({@code TechProcessOperationControlParam.id}): по нему сервис
 * находит вид, обязательность и режим. Из трёх полей заполняется
 * ровно одно — то, которое соответствует режиму параметра:
 *
 *   LIST — valueId (идентификатор варианта справочника),
 *   TEXT — text (свободный ввод),
 *   CELL — confirmed (подтверждение соответствия классификатора).
 */
@Getter
@Setter
public class ControlParamValueForm {

    private Long paramId;
    private Long valueId;
    private String text;
}
