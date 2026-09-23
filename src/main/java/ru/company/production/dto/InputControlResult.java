package ru.company.production.dto;

/**
 * Итог сохранения карточки входного контроля.
 *
 * Результат несёт только признак успеха и надпись: в отличие от
 * сканирования прихода, здесь проверяют не перечень номеров, а
 * согласованность проверки в целом (номера из партии, причина при
 * браке, решение при браке), поэтому достаточно первой найденной
 * проблемы — оператор устраняет её и сохраняет снова.
 *
 * @param completed карточка закрыта, результаты записаны
 * @param message   готовая надпись для контролёра
 */
public record InputControlResult(
        boolean completed,
        String message
) {

    /*
     * Фабричный метод не может называться completed(): record уже
     * объявляет аксессор completed() для компонента с тем же именем.
     */
    public static InputControlResult accepted(String message) {
        return new InputControlResult(true, message);
    }

    public static InputControlResult rejected(String message) {
        return new InputControlResult(false, message);
    }
}
