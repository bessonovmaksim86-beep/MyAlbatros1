package ru.company.production.dto;

import java.util.List;

/**
 * Предпросмотр ячейки при вводе номера в форму браковки.
 *
 * Отвечает мастеру цеха на три вопроса до нажатия кнопки: что это за
 * ячейка, из какого она ЗНП и можно ли её браковать вовсе. Без него
 * форма выглядела бы как лотерея: проверки сервера мастер узнавал бы
 * только по отказу, а причину «ячейка на складе» искал бы по всем
 * разделам подряд.
 *
 * Проверки отдаёт те же, что и проведение заявки
 * ({@link ru.company.production.service#WorkshopScrapService}) — экран
 * и проведение обязаны совпадать, иначе форма обещала бы проводку,
 * которую сервер потом отклонил бы.
 *
 * @param fullNumber   нормализованный номер ячейки
 * @param found        номер есть в реестре
 * @param cellLabel    изделие ячейки: «1212 — Подсборка ЯПР56»
 * @param kindLabel    разновидность: «Ячейка» либо «Подсборка»
 * @param znpNumber    номер заказа, в который ячейка внесена
 * @param location     где ячейка числится — подписывается в заявке
 * @param problems     что мешает браковать; пусто — заявка пройдёт
 */
public record CellScrapPreview(
        String fullNumber,
        boolean found,
        String cellLabel,
        String kindLabel,
        String znpNumber,
        String location,
        List<String> problems
) {

    /** Номер ещё не доведён до девяти цифр — показывать нечего. */
    public static CellScrapPreview incomplete(String number) {
        return new CellScrapPreview(number, false, null, null, null, null,
                List.of());
    }

    /** Номера нет в реестре. */
    public static CellScrapPreview notFound(String number) {
        return new CellScrapPreview(number, false, null, null, null, null,
                List.of("Ячейка " + number + " не найдена в реестре"));
    }

    /** Ячейка вне цеха: заявка на неё невозможна. */
    public static CellScrapPreview notInWorkshop(String number,
                                                 List<String> problems) {
        return new CellScrapPreview(number, true, null, null, null, null,
                problems);
    }

    /** Ячейка найдена; мешают ли ей проблемы — решает вызывающий код. */
    public static CellScrapPreview of(String fullNumber,
                                      String cellLabel,
                                      String kindLabel,
                                      String znpNumber,
                                      String location,
                                      List<String> problems) {
        return new CellScrapPreview(fullNumber, true, cellLabel, kindLabel,
                znpNumber, location, problems);
    }

    /**
     * Браковать можно: номер в реестре, в цеху и без помех.
     *
     * Нужен шаблону, чтобы подписать строку предпросмотра зелёным, а
     * не заставлять страницу разбирать список проблем.
     */
    public boolean scrapable() {
        return found && problems.isEmpty();
    }

    /*
     * Геттеры с префиксом: Thymeleaf читает свойства по
     * JavaBeans-соглашению, а record компилируется с аксессором без
     * префикса — без этих методов предпросмотр отрисовался бы пустым.
     */
    public String getFullNumber() {
        return fullNumber;
    }

    public String getCellLabel() {
        return cellLabel;
    }

    public String getKindLabel() {
        return kindLabel;
    }

    public String getZnpNumber() {
        return znpNumber;
    }

    public String getLocation() {
        return location;
    }

    public boolean isFound() {
        return found;
    }

    public boolean isScrapable() {
        return scrapable();
    }

    public List<String> getProblems() {
        return problems;
    }
}
