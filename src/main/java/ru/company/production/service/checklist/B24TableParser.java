package ru.company.production.service.checklist;

import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Разбор содержимого Б24-таблицы, вставленного из буфера обмена.
 *
 * Диспетчер выделяет в таблице Битрикс24 («Чек-лист приборов по заказу
 * покупателя №…») строки и нажимает Ctrl+C — в буфер попадает текст
 * в формате TSV: колонки разделены знаком табуляции, строки — переводом
 * строки. Тот же приём, что и «Копирование» из отчёта 1С, поэтому
 * разбивать таблицу по ячейкам не нужно.
 *
 * Почему не чтение по ссылке: таблица Б24 доступна по адресу вида
 * {@code /sheet/<код>}, и такой адрес требует авторизации на портале
 * (без неё Битрикс24 отвечает 403). Публичных методов REST API для
 * объекта «Таблицы» у Битрикс24 нет — обратиться к нему может только
 * приложение, установленное на самом портале. Поэтому источник данных —
 * вставка, а не ссылка.
 *
 * Парсер извлекает из вставки три вещи:
 *
 *   1. ЗАКАЗЧИКА и ДАТУ ОТГРУЗКИ из шапки листа
 *      («Заказчик: ООО "Новые Технологии"», «Дата отгрузки: 22.01.2027») —
 *      они становятся покупателем и сроком заказа.
 *   2. ПОЗИЦИИ: строку-заголовок ищет по колонке «Наименование»,
 *      количество берёт либо из отдельной колонки, либо из конца
 *      наименования («…, 1 шт») — как и {@link ChecklistParser}.
 *   3. ПРИМЕЧАНИЕ строки, если колонка есть.
 *
 * Колонка «Сер. №» сознательно НЕ читается: номера изделий выдаёт
 * программа, а вписанные в таблице значения остаются ориентиром для
 * диспетчера при сверке.
 */
@Component
public class B24TableParser {

    private static final List<String> NAME_KEYS =
            List.of("наименов", "изделие", "назван", "номенклат");
    private static final List<String> QTY_KEYS =
            List.of("кол-во", "количество", "кол", "qty", "объём", "объем");
    private static final List<String> UNIT_KEYS =
            List.of("единиц", "изм", "ед изм");
    private static final List<String> NOTE_KEYS =
            List.of("примеч", "коммент", "отмет", "прим");

    /**
     * Колонка порядкового номера строки («№ п/п»).
     *
     * Номер несёт иерархию чек-листа: «1» — изделие, «1.1» и «1.2» —
     * его состав. Без этой колонки состав читался бы отдельными
     * изделиями, и заказ получил бы лишние позиции.
     */
    private static final List<String> NUMBER_KEYS =
            List.of("№ п/п", "н п/п", "номер строки", "пп");

    /** Суффиксы количества, которые ищутся в конце наименования. */
    private static final List<String> NAME_QTY_SUFFIXES =
            List.of("шт", "компл", "комплект", "ком", "кг", "г", "м", "мм", "мл", "л");

    /*
     * REGEX_FLAGS обязателен во всех шаблонах с кириллицей: без
     * UNICODE_CASE флаг CASE_INSENSITIVE работает только для латиницы,
     * и «Заказчик:» с большой буквы не матчится вовсе.
     */
    private static final int REGEX_FLAGS =
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE;

    private static final Pattern TRAILING_QTY = Pattern.compile(
            "(\\d+(?:[.,]\\d+)?)\\s*(шт|компл|комплект|ком|кг|г|м|мм|мл|л)\\s*$",
            REGEX_FLAGS);

    /**
     * Шапка листа: «Заказчик: ООО "Новые Технологии"».
     *
     * Значение ищется и в той же ячейке после двоеточия, и в соседней —
     * в таблицах Б24 подпись и значение нередко стоят в разных колонках.
     */
    private static final Pattern CUSTOMER_LINE = Pattern.compile(
            "^\\s*заказчик\\s*:?\\s*(.*)$", REGEX_FLAGS);

    /** Шапка листа: «Дата отгрузки: 22.01.2027». */
    private static final Pattern DUE_DATE_LINE = Pattern.compile(
            "^\\s*дата\\s+отгрузки\\s*:?\\s*(.*)$", REGEX_FLAGS);

    /**
     * Заголовок листа: «Чек-лист приборов по заказу покупателя № 469
     * от 20 ноября 2026».
     *
     * Номер и дата нужны для сверки с 1С: в программе заказ получает
     * собственный номер по шаблону ЗП-ГГГГ-NNN, поэтому значение из
     * листа записывается отдельно, иначе при проверке карточки
     * диспетчер не найдёт, из какого заказа 1С он исходит.
     *
     * Дата ищется отдельно от номера: в листах встречается и «№ 469
     * от 20 ноября 2026», и «№ 469 от 20.11.2026», и «№ 469» вовсе без
     * даты. Требовать оба значения одним шаблоном — значит потерять
     * номер в последнем случае.
     */
    private static final Pattern SOURCE_ORDER_LINE = Pattern.compile(
            "заказ[у]?\\s+покупателя\\s*(?:№|#)?\\s*(\\d+)",
            REGEX_FLAGS);

    /** Дата заголовка в числовом виде — «20.11.2026». */
    private static final Pattern SOURCE_ORDER_DATE_DIGITS = Pattern.compile(
            "от\\s*(\\d{1,2}[./]\\d{1,2}[./]\\d{4})", REGEX_FLAGS);

    /** Дата заголовка словесно — «20 ноября 2026». */
    private static final Pattern SOURCE_ORDER_DATE_WORDS = Pattern.compile(
            "от\\s*(\\d{1,2})\\s+(январ[а-яё]*|феврал[а-яё]*|март[а-яё]*|"
                    + "апрел[а-яё]*|ма[а-яё]*|июн[а-яё]*|июл[а-яё]*|август[а-яё]*|"
                    + "сентябр[а-яё]*|октябр[а-яё]*|ноябр[а-яё]*|декабр[а-яё]*)"
                    + "(?:\\s+(\\d{4}))?",
            REGEX_FLAGS);

    /**
     * Дата в шапке: точка или слэш как разделитель, год — четыре цифры.
     * В листе встречаются оба написания — «22.01.2027» и «16/12/2026».
     */
    private static final Pattern DATE_VALUE = Pattern.compile(
            "(\\d{1,2})[./](\\d{1,2})[./](\\d{4})");

    /**
     * Разбирает TSV-текст (вставленный из таблицы Б24) в предпросмотр.
     *
     * @param tsvText содержимое буфера после Ctrl+C в таблице
     * @throws IllegalArgumentException если заголовок или позиции не найдены
     */
    public ParsedTable parse(String tsvText) {

        if (tsvText == null || tsvText.isBlank()) {
            throw new IllegalArgumentException(
                    "Вставьте содержимое таблицы Б24: выделите строки "
                            + "в таблице, нажмите Ctrl+C и вставьте сюда"
            );
        }

        String[] lines = tsvText.split("\\r?\\n");

        String sheetTitle = null;
        String customerName = null;
        LocalDate dueDate = null;
        String sourceOrderNumber = null;
        LocalDate sourceOrderDate = null;

        for (String line : lines) {

            String[] cells = splitLine(line);

            /*
             * Подпись и значение шапки могут стоять в разных ячейках:
             * в чек-листе Б24 «Заказчик:» занимает колонку A, а
             * наименование — колонку B. Значение ищется сначала в той же
             * ячейке после двоеточия, затем в следующей непустой.
             */
            for (int i = 0; i < cells.length; i++) {

                String value = cells[i];

                if (value.isEmpty()) {
                    continue;
                }

                /*
                 * Заголовок листа («Чек-лист приборов по заказу
                 * покупателя № 469 от 20 ноября 2026») — первая строка
                 * с словом «чек-лист». По нему определяется род изделий
                 * листа: в «Чек-листе приборов» позиции — приборы, и
                 * ставить им тип «датчик» нельзя (см. isDeviceSheet()).
                 */
                if (sheetTitle == null
                        && normalize(value).contains("чек-лист")) {
                    sheetTitle = value;
                }

                if (customerName == null) {
                    customerName = labelValue(CUSTOMER_LINE, cells, i);
                }
                if (dueDate == null) {
                    dueDate = labelDate(DUE_DATE_LINE, cells, i);
                }
                if (sourceOrderNumber == null) {
                    Matcher number = SOURCE_ORDER_LINE.matcher(value);
                    if (number.find()) {
                        sourceOrderNumber = number.group(1);
                        sourceOrderDate = sourceOrderDateFrom(value);
                    }
                }
            }
        }

        int headerRow = findHeaderRow(lines);
        if (headerRow < 0) {
            throw new IllegalArgumentException(
                    "В вставленном тексте не найдена колонка «Наименование». "
                            + "Скопируйте таблицу вместе со строкой заголовков"
            );
        }

        Map<String, Integer> cols = mapColumns(lines[headerRow]);
        boolean hasQtyColumn = cols.containsKey("qty");

        List<ChecklistParser.ParsedRow> rows = new ArrayList<>();

        for (int r = headerRow + 1; r < lines.length; r++) {

            if (lines[r].isBlank()) {
                continue;
            }

            String[] cells = lines[r].split("\t", -1);
            String name = safeCell(cells, cols.get("name"));

            if (name.isBlank()) {
                continue;
            }

            /*
             * Строка-дубликат условных обозначений («(1)  (2)  (3) …»),
             * которую Б24 печатает под настоящими заголовками: позиций
             * в ней нет, и принимать её за позицию нельзя.
             */
            if (isNumberingLine(cells)) {
                continue;
            }

            BigDecimal qty = hasQtyColumn
                    ? parseNumber(safeCell(cells, cols.get("qty")))
                    : null;

            if (qty == null || qty.signum() <= 0) {
                qty = extractQtyFromName(name);
            }
            if (qty == null || qty.signum() <= 0) {
                continue;
            }

            String unit = safeCell(cells, cols.get("unit"));
            if (unit.isBlank()) {
                unit = toDefaultUnit(name);
            }

            rows.add(new ChecklistParser.ParsedRow(
                    r + 1,
                    name,
                    qty,
                    unit,
                    safeCell(cells, cols.get("note")),
                    rowNumber(safeCell(cells, cols.get("number")))
            ));
        }

        if (rows.isEmpty()) {
            throw new IllegalArgumentException(
                    "В вставленном тексте нет ни одной позиции с количеством. "
                            + "Проверьте, что скопированы строки изделий, "
                            + "а не только шапка листа"
            );
        }

        return new ParsedTable(
                sheetTitle,
                customerName,
                dueDate,
                sourceOrderNumber,
                sourceOrderDate,
                rows
        );
    }

    /** Дата в формате дд.мм.гггг; нечитаемое значение — null. */
    private static LocalDate parseDate(String value) {

        Matcher m = DATE_VALUE.matcher(value);

        if (!m.find()) {
            return null;
        }

        try {
            return LocalDate.of(
                    Integer.parseInt(m.group(3)),
                    Integer.parseInt(m.group(2)),
                    Integer.parseInt(m.group(1))
            );
        } catch (RuntimeException e) {
            return null;
        }
    }

    /**
     * Дата заказа 1С из заголовка: числом или словом.
     *
     * Год может отсутствовать («от 20 ноября») — тогда дата не
     * возвращается: придумывать год программа не вправе, а номер заказа
     * при этом всё равно найден и записан.
     */
    private static LocalDate sourceOrderDateFrom(String header) {

        Matcher digits = SOURCE_ORDER_DATE_DIGITS.matcher(header);

        if (digits.find()) {
            return parseDate(digits.group(1));
        }

        Matcher words = SOURCE_ORDER_DATE_WORDS.matcher(header);

        if (words.find()) {
            return parseMonthNameDate(
                    words.group(1), words.group(2), words.group(3)
            );
        }

        return null;
    }

    /**
     * Дата, записанная словом: «20 ноября 2026».
     *
     * Месяц определяется по началу названия, поэтому «ноября», «ноябрю»
     * и «ноябрь» читаются одинаково. Пустой год — не дата.
     */
    private static LocalDate parseMonthNameDate(
            String day, String month, String year) {

        if (year == null || year.isBlank()) {
            return null;
        }

        String lower = month.toLowerCase(Locale.ROOT);

        Integer number = null;

        for (int i = 0; i < MONTHS.length; i++) {
            if (lower.startsWith(MONTHS[i])) {
                number = i + 1;
                break;
            }
        }

        if (number == null) {
            return null;
        }

        try {
            return LocalDate.of(
                    Integer.parseInt(year),
                    number,
                    Integer.parseInt(day)
            );
        } catch (RuntimeException e) {
            return null;
        }
    }

    /**
     * Начало названия месяца — окончание не учитывается.
     *
     * «мар» стоит раньше «ма»: иначе «март» был бы принят за май.
     */
    private static final String[] MONTHS = {
            "янв", "фев", "мар", "апр", "ма", "июн",
            "июл", "авг", "сен", "окт", "ноя", "дек"
    };

    /**
     * Ячейки строки: нерегулярные разделители и неразрывные пробелы
     * приводятся к plain-тексту, пустые ячейки сохраняются — по ним
     * считается сдвиг колонок.
     */
    private static String[] splitLine(String line) {

        String[] cells = line.split("\t", -1);

        for (int i = 0; i < cells.length; i++) {
            cells[i] = cells[i].replace('\u00A0', ' ').trim();
        }

        return cells;
    }

    /**
     * Значение подписи шапки.
     *
     * Сначала — остаток той же ячейки после двоеточия («Заказчик: ООО
     * "Ромашка"»). Если после подписи ничего нет, значением считается
     * следующая непустая ячейка строки: в таблицах Б24 подпись и
     * значение часто занимают разные колонки.
     *
     * Пустое значение подписью не считается, иначе «Дата отгрузки:» без
     * даты рядом прочиталась бы как наименование заказчика из следующей
     * строки.
     */
    private static String labelValue(Pattern label, String[] cells, int index) {

        Matcher m = label.matcher(cells[index]);

        if (!m.matches()) {
            return null;
        }

        String tail = m.group(1).trim();

        if (!tail.isEmpty()) {
            return tail;
        }

        for (int next = index + 1; next < cells.length; next++) {
            if (!cells[next].isEmpty()) {
                return cells[next];
            }
        }

        return null;
    }

    /** Дата подписи шапки — первое вхождение dd.mm.yyyy или dd/mm/yyyy. */
    private static LocalDate labelDate(
            Pattern label, String[] cells, int index) {

        String tail = labelValue(label, cells, index);

        if (tail == null) {
            return null;
        }

        Matcher m = DATE_VALUE.matcher(tail);

        if (!m.find()) {
            return null;
        }

        try {
            return LocalDate.of(
                    Integer.parseInt(m.group(3)),
                    Integer.parseInt(m.group(2)),
                    Integer.parseInt(m.group(1))
            );
        } catch (RuntimeException e) {
            return null;
        }
    }

    /**
     * Строка условных обозначений колонок: «(1)», «(2)», «(3)» …
     * Все непустые ячейки — номер в скобках.
     */
    private static boolean isNumberingLine(String[] cells) {

        boolean seen = false;

        for (String cell : cells) {
            String value = cell.trim();
            if (value.isEmpty()) {
                continue;
            }
            if (!value.matches("\\(\\d{1,3}\\)")) {
                return false;
            }
            seen = true;
        }

        return seen;
    }

    /**
     * Строка-заголовок: первая, где есть колонка с наименованием.
     *
     * Колонка «Количество» не обязательна: в чек-листе Б24 её обычно
     * нет, а количество дописано в конец наименования («…, 1 шт»).
     * Требовать обе колонки — значит не найти заголовок вовсе.
     */
    private static int findHeaderRow(String[] lines) {

        int limit = Math.min(lines.length - 1, 40);

        for (int r = 0; r <= limit; r++) {
            for (String cell : lines[r].split("\t", -1)) {
                if (containsAny(normalize(cell), NAME_KEYS)) {
                    return r;
                }
            }
        }

        return -1;
    }

    /** Ключи колонок → их индексы; первое совпадение по ключу и есть колонка. */
    private static Map<String, Integer> mapColumns(String headerLine) {

        String[] cells = headerLine.split("\t", -1);
        Map<String, Integer> cols = new HashMap<>();

        for (int i = 0; i < cells.length; i++) {

            String v = normalize(cells[i]);
            if (v.isEmpty()) {
                continue;
            }

            if (!cols.containsKey("name") && containsAny(v, NAME_KEYS)) {
                cols.put("name", i);
            } else if (!cols.containsKey("qty") && containsAny(v, QTY_KEYS)) {
                cols.put("qty", i);
            } else if (!cols.containsKey("unit") && containsAny(v, UNIT_KEYS)) {
                cols.put("unit", i);
            } else if (!cols.containsKey("note") && containsAny(v, NOTE_KEYS)) {
                cols.put("note", i);
            } else if (!cols.containsKey("number")
                    && containsAny(v, NUMBER_KEYS)) {
                cols.put("number", i);
            }
        }

        return cols;
    }

    /** Ячейка по индексу: пустая строка вместо выхода за границы массива. */
    private static String safeCell(String[] cells, Integer idx) {

        if (idx == null || idx < 0 || idx >= cells.length) {
            return "";
        }

        return cells[idx].replace('\u00A0', ' ').trim();
    }

    /** Число из ячейки: разделитель — запятая или точка, пробелы значатся. */
    private static BigDecimal parseNumber(String raw) {

        if (raw.isBlank()) {
            return null;
        }

        try {
            String cleaned = raw
                    .replace(" ", "")
                    .replace(',', '.');

            return cleaned.isEmpty() ? null : new BigDecimal(cleaned);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * Номер строки листа («1», «5.1»); всё, что не похоже на номер,
     * не читается.
     *
     * Дробный номер — не число в обычном смысле: «5.10» означает
     * десятый состав пятого изделия, а не число пять и десять целых,
     * поэтому значение остаётся строкой и сравнивается посимвольно.
     * Пустая ячейка и текст вроде «итого» дают null: строка тогда
     * считается позицией без иерархии.
     */
    private static String rowNumber(String raw) {

        String value = raw.replace(" ", "");

        return value.matches("\\d+(\\.\\d+)?") ? value : null;
    }

    /**
     * Количество из конца наименования: «…, 1 шт» → 1, «…, 4 компл» → 4.
     */
    private static BigDecimal extractQtyFromName(String name) {

        String trimmed = name.trim();
        int lastComma = trimmed.lastIndexOf(',');

        if (lastComma > 0) {
            String suffix = trimmed.substring(lastComma + 1)
                    .replaceAll("\\s*из\\s*", " ")
                    .trim();

            if (!suffix.isEmpty()) {
                int spaceIdx = suffix.indexOf(' ');
                String numericPart = spaceIdx > 0
                        ? suffix.substring(0, spaceIdx).trim()
                        : suffix;

                try {
                    BigDecimal qty = new BigDecimal(numericPart.replace(',', '.'));
                    if (qty.signum() > 0) {
                        return qty;
                    }
                } catch (NumberFormatException ignored) {
                    /*
                     * Хвост после запятой — не число (например, «Ni Excl»):
                     * пробуем общий паттерн с единицей измерения.
                     */
                }
            }
        }

        Matcher m = TRAILING_QTY.matcher(trimmed);

        if (m.find()) {
            try {
                return new BigDecimal(m.group(1).replace(',', '.'));
            } catch (NumberFormatException e) {
                return null;
            }
        }

        return null;
    }

    /** Единица по суффиксу наименования; неизвестный суффикс — «шт.». */
    private static String toDefaultUnit(String name) {

        String lower = name.toLowerCase(Locale.ROOT).trim();

        for (String suffix : NAME_QTY_SUFFIXES) {
            if (lower.endsWith(" " + suffix) || lower.endsWith("," + suffix)) {
                return toUnitName(suffix);
            }
        }

        return "PCS";
    }

    /**
     * Имя enum единицы измерения.
     *
     * Порядок проверок важен: «мм» и «мл» обязаны проверяться раньше «м»,
     * иначе миллиметры читаются как метры.
     */
    private static String toUnitName(String suffix) {

        String s = suffix.toLowerCase(Locale.ROOT).trim();

        if (s.startsWith("кг")) return "KG";
        if (s.startsWith("мм")) return "MM";
        if (s.startsWith("мл")) return "ML";
        if (s.startsWith("г"))  return "G";
        if (s.startsWith("м"))  return "M";
        if (s.startsWith("л"))  return "L";

        return "PCS";
    }

    private static String normalize(String raw) {

        if (raw == null) {
            return "";
        }

        return raw.replace('\u00A0', ' ')
                .replaceAll("\\s+", " ")
                .trim()
                .toLowerCase(Locale.ROOT);
    }

    private static boolean containsAny(String v, List<String> keys) {

        for (String k : keys) {
            if (v.contains(k)) {
                return true;
            }
        }

        return false;
    }

    /**
     * Разобранная таблица: реквизиты шапки и позиции.
     *
     * Позиции отдаются тем же типом, что и разбор файла чек-листа,
     * — форма заказа заполняется из обоих источников одинаково.
     *
     * sourceOrderNumber и sourceOrderDate — номер и дата заказа
     * покупателя в 1С из заголовка листа: они не заменяют номер
     * программы, а сохраняются для сверки.
     */
    public record ParsedTable(
            String title,
            String customerName,
            LocalDate dueDate,
            String sourceOrderNumber,
            LocalDate sourceOrderDate,
            List<ChecklistParser.ParsedRow> rows
    ) {

        private static final DateTimeFormatter DATE_FORMATTER =
                DateTimeFormatter.ofPattern("dd.MM.yyyy");

        public String formattedDueDate() {
            return dueDate == null ? "" : dueDate.format(DATE_FORMATTER);
        }

        /**
         * Лист про приборы, а не про датчики.
         *
         * Тип позиции в форме обязан быть верным: для датчика изделие
         * подбирается по полному обозначению автоматически, а прибор,
         * ячейка и система требуют выбора из классификатора. Сервис
         * заказа отклоняет позицию «прибор без выбранного изделия», и
         * если бы лист приборов импортировался как датчики, диспетчер
         * получил бы ошибку на сохранении вместо заполненной карточки.
         *
         * Род читается из заголовка листа («Чек-лист приборов по заказу
         * покупателя № 469»): название — единственный признак, по
         * которому его можно определить до разбора позиций.
         */
        public boolean deviceSheet() {

            if (title == null) {
                return false;
            }

            String lower = title
                    .replace('\u00A0', ' ')
                    .toLowerCase(java.util.Locale.ROOT);

            return (lower.contains("прибор") || lower.contains("ячейк"))
                    && !lower.contains("датчик");
        }

        /** Заказ 1С одной строкой для вывода в форме: «№ 469 от 20.11.2026». */
        public String sourceOrderLabel() {

            if (sourceOrderNumber == null) {
                return "";
            }

            return sourceOrderDate == null
                    ? "№ " + sourceOrderNumber
                    : "№ " + sourceOrderNumber
                            + " от " + sourceOrderDate.format(DATE_FORMATTER);
        }
    }
}
