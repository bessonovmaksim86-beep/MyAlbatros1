package ru.company.production.service.checklist;

import java.util.regex.Pattern;

/**
 * Примечание позиции из чек-листа Б24.
 *
 * <p>В листах колонка «Прим.» обычно пустая, а все особенности
 * исполнения («с фторопластовым покрытием ЧЭ; поплавок тип IV …»)
 * написаны в скобках после обозначения. В поле обозначения такой текст
 * не влезает и читается обрывком «…; с кабельным», поэтому особенности
 * едут в колонку «Примечание», где диспетчер видит их целиком.
 */
public final class ChecklistNoteUtils {

    /**
     * Хвост количества, который лист пишет после обозначения:
     * «, 1 шт», « 2 компл», «, 1,5 м». Скобка, за которой стоит только
     * такое, всё равно считается последней в обозначении.
     */
    private static final Pattern QUANTITY_TAIL = Pattern.compile(
            "^\\s*[,;]?\\s*(?:\\d+(?:[.,]\\d+)?)?\\s*"
                    + "(?:шт|компл|ком|кг|г|м|мм|мл|л)\\.?\\s*$",
            Pattern.CASE_INSENSITIVE);

    /**
     * Дата в начале блока (ДД.ММ.ГГ или ДД.ММ.ГГГГ) — пометка листа,
     * а не особенности исполнения: «(25.08.26 СГП)».
     */
    private static final Pattern DATE_FIRST = Pattern.compile(
            "^\\d{2}\\.\\d{2}\\.\\d{2,4}");

    /**
     * Дата формата ДД.ММ.ГГГГ или ДД.ММ.ГГ — пометка партии в колонке
     * «Прим.», а не примечание к изделию.
     */
    private static final Pattern NOTE_DATE = Pattern.compile(
            "^\\d{2}\\.\\d{2}\\.\\d{2,4}$");

    /**
     * Код партии / пометка склада (1–3 заглавных латинских/кириллических
     * буквы или цифр, возможно с точкой): «СГП», «А», «ВК-1» и т. п. —
     * в колонке «Прим.» это мусор, а не примечание.
     */
    private static final Pattern SHORT_CODE = Pattern.compile(
            "^[А-ЯA-Z0-9][А-ЯA-Z0-9., -]{0,2}$");

    /**
     * Дата + код партии в колонке «Прим.»: «25.08.26 СГП», «01.09.2026 А».
     */
    private static final Pattern NOTE_DATE_CODE = Pattern.compile(
            "^\\d{2}\\.\\d{2}\\.\\d{2,4}\\s+[А-ЯA-Z0-9][А-ЯA-Z0-9., -]{0,4}$");

    /**
     * Сколько блоков скобок просмотреть справа налево: лист не пишет
     * длинных цепочек, а защита нужна от патологического входа.
     */
    private static final int MAX_PAREN_GROUPS = 5;

    private ChecklistNoteUtils() {
    }

    /**
     * Примечание позиции: колонка «Прим.», а если она пуста или
     * содержит только дату / код партии — особенности исполнения
     * из обозначения. Наименование изделия при этом не дублируется:
     * его диспетчер видит в самом обозначении или в подобранном изделии.
     */
    public static String resolveNote(String rawNote, String name) {

        String column = cleanColumnNote(rawNote);

        return column != null ? column : extractExecutionDetails(name);
    }

    /**
     * Примечание по колонке «Прим.» без извлечения из обозначения.
     *
     * <p>Для позиции, где особенности исполнения уже видны целиком
     * (обозначение датчика), извлекать их повторно не нужно: строка
     * получила бы примечание-дубль того, что диспетчер читает в поле
     * наименования. Здесь остаётся только содержимое колонки «Прим.»,
     * если оно не сведено к дате или коду партии.
     */
    public static String cleanColumnNote(String rawNote) {

        if (rawNote == null || rawNote.isBlank()) {
            return null;
        }

        String trimmed = rawNote.trim();

        return isTrash(trimmed) ? null : trimmed;
    }

    /** Краткая пометка в колонке «Прим.» — мусор, а не примечание. */
    private static boolean isTrash(String text) {

        return NOTE_DATE.matcher(text).matches()
                || NOTE_DATE_CODE.matcher(text).matches()
                || SHORT_CODE.matcher(text).matches();
    }

    /**
     * Особенности исполнения — правый блок скобок обозначения,
     * подходящий под примечание.
     *
     * <p>Блок обязан:
     * <ul>
     *   <li>не иметь после себя текста, кроме хвоста количества
     *       («, 1 шт»), — иначе скобки стоят в середине обозначения;</li>
     *   <li>содержать буквы — диапазон параметров «(1-4)» и отсылка
     *       «(2)» примечанием не являются;</li>
     *   <li>не начинаться с даты — «(25.08.26 СГП)» в листе помечает
     *       партию, а не исполнение.</li>
     * </ul>
     *
     * <p>Если правый блок примечанием не является, просмотр продолжается
     * левее: в листе ДПУ5А особенности стоят в своей скобке, а дата —
     * в следующей, и примечанием обязаны остаться именно особенности.
     * Соседним блок считается только когда между скобками одни пробелы,
     * так что скобка внутри наименования в выборку не попадёт.
     *
     * <p>Блок ищется по парной открывающей скобке, поэтому вложенные
     * скобки его не режут.
     */
    public static String extractExecutionDetails(String name) {

        if (name == null || name.isBlank()) {
            return null;
        }

        int close = name.lastIndexOf(')');

        if (close < 0 || !isTrivialTail(name.substring(close + 1))) {
            return null;
        }

        for (int group = 0; group < MAX_PAREN_GROUPS; group++) {

            int open = matchingOpenBracket(name, close);

            if (open < 0) {
                return null;
            }

            String details = name.substring(open + 1, close).trim();

            if (isNote(details)) {
                return details;
            }

            int previous = name.lastIndexOf(')', open - 1);

            if (previous < 0 || !name.substring(previous + 1, open).isBlank()) {
                return null;
            }

            close = previous;
        }

        return null;
    }

    /** Блок скобок — примечание: есть буквы и нет даты в начале. */
    private static boolean isNote(String details) {

        return !details.isEmpty()
                && hasLetter(details)
                && !DATE_FIRST.matcher(details).find();
    }

    /** После обозначения допускается пустота или только количество. */
    private static boolean isTrivialTail(String tail) {

        return tail.isBlank() || QUANTITY_TAIL.matcher(tail).matches();
    }

    /** Текст содержит хотя бы одну букву — не цифры и не спецсимволы. */
    private static boolean hasLetter(String text) {

        return text.chars().anyMatch(Character::isLetter);
    }

    /**
     * Индекс открывающей скобки, парной закрывающей на позиции
     * {@code close}, либо {@code -1}.
     */
    private static int matchingOpenBracket(String text, int close) {

        int depth = 0;

        for (int i = close; i >= 0; i--) {

            char c = text.charAt(i);

            if (c == ')') {
                depth++;
            } else if (c == '(') {
                depth--;

                if (depth == 0) {
                    return i;
                }
            }
        }

        return -1;
    }
}
