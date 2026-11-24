package ru.company.production.service;

import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.springframework.stereotype.Component;
import ru.company.production.dto.OneCZpImportRow;

import java.io.InputStream;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Разбор выгрузки отчёта «Анализ заказов покупателей» (1С: УПП 1.3).
 *
 * Отчёт построен матрицей с иерархией группировок
 * «Контрагент → Договор → Заказ → Номенклатура», поэтому строка заказа
 * не является отдельной таблицей: она вложена между строками родителя и
 * позиций, а её реквизиты стоят в разных колонках.
 *
 * Из листа берутся ТОЛЬКО строки уровня «Заказ покупателя» — это и есть
 * ЗП, ради которого затянута интеграция. Строки контрагентов, договоров
 * и номенклатуры в выдачу не попадают: их суммы — свёртки по вложенным
 * заказам, и показав их рядом с заказами, мы вручили бы администратору
 * перевернутые итоги.
 *
 * Номер и дата приходят одной ячейкой: «Заказ покупателя 00000000450
 * от 11.11.2026 14:27:50». Разбирается парой «номер / дата».
 *
 * Числа читаются из значения ячейки, а не из её текста: выгрузка
 * приходит в разных настройках разделителей, и в одном листе «7.000»
 * означает семь, а в другом — семь тысяч. POI отдаёт число так, как оно
 * лежит в книге, и расхождение разделителей исчезает.
 *
 * Уровень строки восстанавливается по отступу ячейки. Отступ пишет
 * именно «Сохранить как… → Файл Excel» из окна отчёта; вставка из
 * буфера обмена отступов не несёт, и тогда контрагент не
 * восстанавливается — об этом сообщает {@link #hierarchyLost()}.
 */
@Component
public class OneCAnalysisParser {

    /**
     * Строка заказа покупателя: «Заказ покупателя 00000000450
     * от 11.11.2026 14:27:50».
     *
     * Время после даты факультативно: в выгрузке оно есть, но
     * требовать его нельзя — договоры («…сч. 364 от 29/10/2026») тоже
     * несут дату, и по одному лишь предлогу «от» уровни не разойдутся.
     */
    private static final Pattern ORDER_LINE = Pattern.compile(
            "(?i)заказ\\s+покупателя\\s+(\\d+)\\s+от\\s+"
                    + "(\\d{1,2}\\.\\d{1,2}\\.\\d{4})",
            Pattern.UNICODE_CASE
    );

    /** Русская дата с двумя или четырьмя цифрами года. */
    private static final Pattern RU_DATE = Pattern.compile(
            "(\\d{1,2})\\.(\\d{1,2})\\.(\\d{4})"
    );

    /**
     * Заголовок колонки остатка отгрузки.
     *
     * Колонка ищется по этому заголовку, а не по букве: отчёт
     * перенастраивают, колонки вставляют. Слово уникально в листе —
     * «Осталось оплатить» отличается окончанием, — и потому годится
     * как якорь.
     */
    private static final String REMAINING_HEADER = "осталосьотгрузить";

    /** Период отчёта: «Период: Ноябрь 2026 г.». */
    private static final Pattern PERIOD_LINE = Pattern.compile(
            "(?i)период[:\\s]+(.+)"
    );

    /**
     * Разбирает книгу выгрузки.
     *
     * Листы перебираются все: в одной книге отчёт лежит на вкладке с
     * месяцем («Октябрь26», «Лист3»), и порядок вкладок никто не
     * согласовывал. Годным считается лист, давший больше всего строк
     * заказов.
     */
    public ParsedAnalysis parse(InputStream in) {
        try (Workbook workbook = WorkbookFactory.create(in)) {

            DataFormatter formatter = new DataFormatter();
            ParsedAnalysis best = null;

            for (int i = 0; i < workbook.getNumberOfSheets(); i++) {
                Sheet sheet = workbook.getSheetAt(i);

                List<OneCZpImportRow> rows = parseSheet(sheet, formatter);

                if (best == null || rows.size() > best.rows().size()) {
                    best = new ParsedAnalysis(sheet.getSheetName(), rows);
                }
            }

            if (best == null) {
                throw new IllegalArgumentException(
                        "В файле нет ни одного листа"
                );
            }

            if (best.rows().isEmpty()) {
                throw new IllegalArgumentException(
                        "В файле не найдено ни одной строки вида «Заказ"
                                + " покупателя <номер> от <дата>». Проверьте,"
                                + " что выгружен отчёт «Анализ заказов"
                                + " покупателей», а не список документов."
                );
            }

            return best;
        } catch (IllegalArgumentException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IllegalArgumentException(
                    "Не удалось прочитать файл выгрузки: " + exception.getMessage()
            );
        }
    }

    /** Разбирает один лист: ищет колонку остатка и строки заказов. */
    private List<OneCZpImportRow> parseSheet(Sheet sheet, DataFormatter formatter) {
        int remainingColumn = findRemainingColumn(sheet, formatter);

        if (remainingColumn < 0) {
            return List.of();
        }

        List<OneCZpImportRow> rows = new ArrayList<>();
        List<String> labels = new ArrayList<>();
        List<Integer> indents = new ArrayList<>();

        for (int r = 0; r <= sheet.getLastRowNum(); r++) {
            Row row = sheet.getRow(r);

            if (row == null) {
                continue;
            }

            String label = text(row, 0, formatter);
            int indent = indent(row, 0);

            labels.add(label);
            indents.add(indent);

            Matcher matcher = ORDER_LINE.matcher(label);

            if (!matcher.find()) {
                continue;
            }

            rows.add(new OneCZpImportRow(
                    matcher.group(1),
                    parseDate(matcher.group(2)),
                    parentAt(labels, indents, r, 2),
                    parentAt(labels, indents, r, 1),
                    number(row, remainingColumn - 1),
                    number(row, remainingColumn),
                    r + 1
            ));
        }

        return rows;
    }

    /**
     * Ищет колонку «Осталось отгрузить».
     *
     * Заголовки матрицы разнесены по нескольким строкам и идут с
     * переносами, поэтому сравнение по «плотной» форме: пробелы и
     * регистр снимаются. Колонка ищется по строкам шапки, а не по
     * первой строке: над заголовками всегда стоит имя отчёта и период.
     */
    private int findRemainingColumn(Sheet sheet, DataFormatter formatter) {
        for (int r = 0; r <= Math.min(sheet.getLastRowNum(), 20); r++) {
            Row row = sheet.getRow(r);

            if (row == null) {
                continue;
            }

            for (int c = 1; c < row.getLastCellNum(); c++) {
                if (compact(text(row, c, formatter)).contains(REMAINING_HEADER)) {
                    return c;
                }
            }
        }

        return -1;
    }

    /**
     * Подпись предка нужного уровня иерархии.
     *
     * Идём вверх от строки заказа и ищем ближайшую строку с отступом на
     * {@code levels} меньше: один уровень — договор, два — контрагент.
     * Отступ сравнивается с отступом строки заказа, а не с абсолютным
     * значением: в разных выгрузках база отсчёта разная.
     *
     * Отступов нет (вставка из буфера) — все они нулевые, условие не
     * выполняется и предок не находится. Это честно возвращается как
     * null, а не подменяется первой попавшейся строкой: выдать
     * договор за контрагента хуже, чем показать прочерк.
     */
    private static String parentAt(
            List<String> labels,
            List<Integer> indents,
            int orderLine,
            int levels
    ) {
        int target = indents.get(orderLine) - levels;

        if (target < 0) {
            return null;
        }

        for (int i = orderLine - 1; i >= 0; i--) {
            int indent = indents.get(i);

            if (indent == target && !labels.get(i).isBlank()) {
                return labels.get(i).trim();
            }

            // Выше нужного уровня предков строки заказа уже нет.
            if (indent < target) {
                return null;
            }
        }

        return null;
    }

    /** Текст ячейки, включая объединённые и служебные строки листа. */
    private static String text(Row row, int column, DataFormatter formatter) {
        if (column < 0) {
            return "";
        }

        Cell cell = row.getCell(column);

        return cell == null ? "" : formatter.formatCellValue(cell).trim();
    }

    /**
     * Отступ ячейки в символах; 0 — без отступа.
     *
     * В POI метод называется getIndention() и объявлен в самом
     * интерфейсе CellStyle, поэтому работает одинаково для xls (HSSF)
     * и xlsx (XSSF) — а выгрузка 1С приходит то в одном, то в другом
     * формате.
     */
    private static int indent(Row row, int column) {
        Cell cell = row.getCell(column);

        if (cell == null) {
            return 0;
        }

        CellStyle style = cell.getCellStyle();

        return style == null ? 0 : style.getIndention();
    }

    /**
     * Число из ячейки.
     *
     * Числовой тип берётся как есть. Текст приводится к числу с
     * прощением разделителей: в выгрузке встречаются и «7.000», и
     * «264,300.80», и пустые клетки. Пустое остаётся null — отсутствие
     * числа значимо (по нему заказ считается закрытым), и подменять его
     * нулём нельзя.
     */
    private static BigDecimal number(Row row, int column) {
        if (column < 0) {
            return null;
        }

        Cell cell = row.getCell(column);

        if (cell == null) {
            return null;
        }

        if (cell.getCellType() == CellType.NUMERIC) {
            return BigDecimal.valueOf(cell.getNumericCellValue());
        }

        if (cell.getCellType() == CellType.FORMULA) {
            try {
                return BigDecimal.valueOf(cell.getNumericCellValue());
            } catch (Exception exception) {
                return null;
            }
        }

        if (cell.getCellType() != CellType.STRING) {
            return null;
        }

        return parseDecimal(cell.getStringCellValue());
    }

    /** Число из текста с разделителями групп; нечитаемое — null. */
    private static BigDecimal parseDecimal(String value) {
        if (value == null) {
            return null;
        }

        String text = value.trim()
                .replace("\u00A0", "")
                .replace(" ", "");

        if (text.isEmpty()) {
            return null;
        }

        // Запятая как разделитель тысяч («264,300.80»), точка как
        // десятичная. Обратный порядок (европейский) в выгрузках УПП
        // не встречался, и угадывать его по одной точке нельзя.
        if (text.contains(",") && text.contains(".")) {
            text = text.replace(",", "");
        } else if (text.contains(",")) {
            text = text.replace(",", ".");
        }

        try {
            return new BigDecimal(text);
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    /** Дата «дд.мм.гггг» в календарную дату; нечитаемая — null. */
    private static LocalDate parseDate(String value) {
        Matcher matcher = RU_DATE.matcher(value == null ? "" : value);

        if (!matcher.matches()) {
            return null;
        }

        try {
            return LocalDate.of(
                    Integer.parseInt(matcher.group(3)),
                    Integer.parseInt(matcher.group(2)),
                    Integer.parseInt(matcher.group(1))
            );
        } catch (Exception exception) {
            return null;
        }
    }

    /** Снятие пробелов, переносов и регистра для сравнения заголовков. */
    private static String compact(String value) {
        return value == null
                ? ""
                : value.toLowerCase()
                        .replaceAll("[^\\p{L}\\p{N}]", "");
    }

    /**
     * Разобранная выгрузка.
     *
     * @param sheetName имя листа, из которого взяты строки — видно
     *                  в предпросмотре, какой лист взят
     * @param rows      строки заказов
     */
    public record ParsedAnalysis(String sheetName, List<OneCZpImportRow> rows) {

        /**
         * Утрачена ли иерархия: ни у одной строки не восстановлен
         * контрагент.
         *
         * Признак говорит администратору, что файл не сохранён из отчёта
         * («Сохранить как…»), а вставлен из буфера обмена: в таком
         * тексте отступов нет, и номера заказов видны, а клиенты — нет.
         */
        public boolean hierarchyLost() {
            return rows.stream().allMatch(row -> row.customer() == null);
        }
    }
}
