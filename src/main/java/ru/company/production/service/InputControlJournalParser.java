package ru.company.production.service;

import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.DateUtil;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import java.io.InputStream;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Разбор листа журнала входного контроля комплектующих (.xls/.xlsx).
 *
 * Раскладка листа заранее не известна — файл ведут руками, колонки
 * вставляют и переставляют, — поэтому парсер, как и
 * {@link ru.company.production.service.checklist.ChecklistParser},
 * ищет строку заголовков и сопоставляет колонки по синонимам, а не
 * по фиксированным буквам A–M.
 *
 * Колонки, которые в журнал не пишутся («Вид контроля» — он всегда
 * «Входной», и «Контролируемая операция» — раздел её не ведёт),
 * отсеиваются сами: их заголовки не подходят ни под одно правило.
 * Отдельного чёрного списка нет намеренно — в новых бланках
 * «Наименование…» и «Контролируемая операция» склеены в одну ячейку,
 * и пропуск по ключевому слову выбросил бы вместе с ними и
 * наименование.
 *
 * Заголовки бланка набраны вертикально и рвутся переносами, поэтому
 * сравнение идёт по «плотной» форме строки — см. {@link #compact(String)}.
 *
 * Номер и дата стоят в одной ячейке: «СФ № 45512 от 07.08.2026»,
 * «ЗНП № 6553 от 06.08.2026». Так вносят и документ, и акт, поэтому
 * разбор общий: отсечение хвоста по предлогу «от» даёт пару
 * «номер / дата», а если даты нет — вся строка остаётся номером.
 */
@Component
public class InputControlJournalParser {

    /**
     * Правила сопоставления: более специфичное ключевое слово должно
     * стоять раньше.
     *
     * Порядок здесь важнее, чем список синонимов: заголовок колонки
     * акта («…установления акта об расхождении по количеству и (или)
     * качеству») содержит слово «количество», и правило количеств,
     * поставленное первым, забрало бы колонку акта себе.
     */
    private static final List<ColumnRule> RULES = List.of(
            new ColumnRule("controlDate",
                    List.of("дата контроля", "дата вх. контроля")),
            new ColumnRule("productName",
                    List.of("наименование", "наименован", "марка", "номенклат")),
            new ColumnRule("supplier",
                    List.of("поставщик", "предприятие-поставщик", "предприятие")),
            new ColumnRule("document",
                    List.of("номер и дата сопроводит", "сопроводительного документа")),
            new ColumnRule("totalQuantity",
                    List.of("общее количество", "общее кол")),
            new ColumnRule("checkedQuantity",
                    List.of("проверенной", "проверенное")),
            new ColumnRule("scrapQuantity",
                    List.of("забракованной", "забракованное")),
            new ColumnRule("act",
                    List.of("акта об", "акт об", "расхожден")),
            new ColumnRule("description",
                    List.of("описание несоответствия", "несоответствия")),
            new ColumnRule("measures",
                    List.of("принятые меры", "принятые")),
            new ColumnRule("note",
                    List.of("примечание", "примеч"))
    );

    /**
     * Дата в конце строки: «… от 07.08.2026».
     *
     * Предлог обязателен: «СФ № 1284357/1702 от 14.09.2026» — номер
     * со слэшами, и отделять дату по первому попавшему числу нельзя.
     */
    private static final Pattern TRAILING_DATE = Pattern.compile(
            "(?i)\\bот\\s+(\\d{1,2}\\.\\d{1,2}\\.\\d{2,4})\\s*$");

    /** Дата в произвольном месте строки — запасной разбор. */
    private static final Pattern ANY_DATE = Pattern.compile(
            "(\\d{1,2}\\.\\d{1,2}\\.\\d{2,4})");

    /**
     * Символы, которых в заголовке не видно, но которые мешают
     * сравнению: мягкий перенос, zero-width пробелы, BOM, узкий
     * пробел и управляющие переводы строк.
     */
    private static final Pattern INVISIBLE = Pattern.compile(
            "[\\u00AD\\u200B\\u200C\\u200D\\u2060\\uFEFF]");

    /**
     * Всё, что не буква и не цифра: пробелы, переносы, дефисы, точки,
     * скобки. Снимается в «плотной» форме заголовка — см.
     * {@link #compact(String)}.
     */
    private static final Pattern NON_LETTER = Pattern.compile("[^\\p{L}\\p{N}]+");

    /**
     * Разбирает лист журнала.
     *
     * Отдаёт непустые строки (без наименования строка в журнале
     * не имеет смысла) и указывает номер строки листа в каждой
     * записи — по нему администратор найдёт место ошибки.
     *
     * Ошибкой считается только случай, когда разбор не дал ни одной
     * строки или не найдена шапка: частичный результат ценнее отказа,
     * поэтому колонка, которой нет в листе, просто остаётся пустой.
     */
    /**
     * Разбирает книгу журнала.
     *
     * Листы перебираются все, а не только первый: в одной книге
     * обычно лежат журнал за прошлые годы, актуальный журнал и
     * служебный расчётный лист. Нужный лист — не первый, и брать его
     * по индексу значило бы зависеть от порядка вкладок, который
     * никто не согласовывал.
     *
     * Годным считается лист, на котором нашлась шапка журнала и есть
     * строки; среди годных берётся лист с самой свежей датой
     * контроля. Правило по дате, а не по порядку вкладок, потому что
     * архивных журналов в книге может быть несколько и первый
     * подошедший лист оказался бы журналом 2020 года.
     *
     * Имя листа отдаётся вместе со строками: администратор видит в
     * предпросмотре, какой лист разобран, а не предполагает это по
     * виду таблицы.
     *
     * Ошибкой считается только случай, когда ни один лист не дал
     * строк или нигде не найдена шапка: частичный результат ценнее
     * отказа, поэтому колонка, которой нет в листе, просто остаётся
     * пустой.
     */
    public ParsedJournal parse(MultipartFile file) {
        try (InputStream in = file.getInputStream();
             Workbook wb = WorkbookFactory.create(in)) {

            DataFormatter fmt = new DataFormatter();
            List<String> sheetProblems = new ArrayList<>();
            ParsedJournal best = null;
            LocalDate latestInBest = null;

            for (int i = 0; i < wb.getNumberOfSheets(); i++) {
                Sheet sheet = wb.getSheetAt(i);

                List<ParsedJournalRow> rows;

                try {
                    rows = parseSheet(sheet, fmt);
                } catch (IllegalArgumentException exception) {
                    /*
                     * Причина отказа по каждому листу собирается в
                     * одно сообщение: когда листов несколько, отказ
                     * только по последнему из них направил бы
                     * администратора не туда — проверял бы он тогда
                     * расчётный лист вместо журнала.
                     */
                    sheetProblems.add(exception.getMessage());
                    continue;
                }

                LocalDate latest = latestControlDate(rows);

                if (best == null
                        || (latest != null
                            && (latestInBest == null || latest.isAfter(latestInBest)))) {
                    best = new ParsedJournal(sheet.getSheetName(), rows);
                    latestInBest = latest;
                }
            }

            if (best != null) {
                return best;
            }

            if (wb.getNumberOfSheets() == 0) {
                throw new IllegalArgumentException("В файле нет ни одного листа");
            }

            throw new IllegalArgumentException(String.join(" / ", sheetProblems));
        } catch (IllegalArgumentException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IllegalArgumentException(
                    "Не удалось прочитать файл: " + exception.getMessage()
            );
        }
    }

    /** Самая свежая дата контроля среди строк листа (пустые не считаются). */
    private static LocalDate latestControlDate(List<ParsedJournalRow> rows) {
        LocalDate latest = null;

        for (ParsedJournalRow row : rows) {
            LocalDate date = row.controlDate();

            if (date != null && (latest == null || date.isAfter(latest))) {
                latest = date;
            }
        }

        return latest;
    }

    /** Разбирает один лист; годность листа решает найденная шапка. */
    private List<ParsedJournalRow> parseSheet(Sheet sheet, DataFormatter fmt) {
        int headerRow = findHeaderRow(sheet, fmt);
        if (headerRow < 0) {
            throw new IllegalArgumentException(
                    "В листе «" + sheet.getSheetName() + "» не найдена строка"
                            + " заголовков: нужна колонка «Наименование»."
                            + " Прочитано из листа: " + sheetDigest(sheet, fmt)
            );
        }

        Columns columns = mapColumns(sheet.getRow(headerRow), fmt);

        if (columns.productName < 0) {
            throw new IllegalArgumentException(
                    "В файле не найдена колонка «Наименование, марка, тип продукции»"
            );
        }

        List<ParsedJournalRow> rows = new ArrayList<>();

        for (int r = headerRow + 1; r <= sheet.getLastRowNum(); r++) {
            Row row = sheet.getRow(r);
            if (row == null) {
                continue;
            }

            String productName = cell(row, columns.productName, fmt);
            if (productName.isBlank()) {
                continue;
            }

            String document = cell(row, columns.document, fmt);
            String act = cell(row, columns.act, fmt);

            rows.add(new ParsedJournalRow(
                    r + 1,
                    parseDate(row, columns.controlDate, fmt),
                    productName,
                    blankToNull(cell(row, columns.supplier, fmt)),
                    numberPart(document),
                    datePart(row, columns, fmt, document),
                    integer(row, columns.totalQuantity, fmt),
                    integer(row, columns.checkedQuantity, fmt),
                    integer(row, columns.scrapQuantity, fmt),
                    numberPart(act),
                    dateFromText(act),
                    blankToNull(cell(row, columns.description, fmt)),
                    blankToNull(cell(row, columns.measures, fmt)),
                    blankToNull(cell(row, columns.note, fmt))
            ));
        }

        if (rows.isEmpty()) {
            throw new IllegalArgumentException(
                    "В файле нет ни одной строки с наименованием продукции"
            );
        }

        return rows;
    }

    /**
     * «Плотная» форма заголовка: из нормализованной строки сняты все
     * пробелы и знаки препинания.
     *
     * Сравнение идёт по плотной форме, а не по тексту с пробелами:
     * вертикальные заголовки бланка набирают в узких колонках, и
     * редакторы рвут слова переносами — «Наименова ние», «Общее
     * кол-во», «предпри-ятие-поставщик». Плотная форма приводит и
     * заголовок, и ключевое слово к одному виду, в котором слова
     * склейки не видно: «наименование» ищется в «наименовании»,
     * сколько бы переносов в него ни вставили.
     */
    private static String compact(String normalized) {
        return NON_LETTER.matcher(normalized).replaceAll("");
    }

    /** Ключевое слово встречается в плотной форме заголовка. */
    private static boolean matchesKey(String compactHeader, String key) {
        return compactHeader.contains(compact(normalize(key)));
    }

    /** Тот же поиск для нескольких ключевых слов. */
    private static boolean matchesAny(String compactHeader, String... keys) {
        for (String key : keys) {
            if (matchesKey(compactHeader, key)) {
                return true;
            }
        }

        return false;
    }

    /** Тот же разбор для списка синонимов правила. */
    private static boolean matchesAny(String compactHeader, List<String> keys) {
        for (String key : keys) {
            if (matchesKey(compactHeader, key)) {
                return true;
            }
        }

        return false;
    }

    /**
     * Строка заголовков: первая, где есть колонка наименования.
     *
     * Названия одного лишь достаточно, чтобы опознать шапку журнала:
     * «наименование» в служебных строках листа не встречается, а
     * колонку количества в разных бланках подписывают по-разному
     * («Общее количество», «Кол-во», «Количество по документу»), и
     * требовать её — значило бы отвергать годный журнал из-за
     * формулировки заголовка.
     *
     * Поиску ограничивают первыми сорока строками: выше шапки в
     * рабочем листе стоит заголовок таблицы и годы, и строка с
     * произносимыми словами там не означает, что данные начинаются
     * с неё.
     *
     * Сравнение по плотной форме (см. {@link #compact(String)}):
     * шапка ищется даже когда слова разорваны переносами.
     */
    private int findHeaderRow(Sheet sheet, DataFormatter fmt) {
        int limit = Math.min(sheet.getLastRowNum(), 40);

        for (int r = 0; r <= limit; r++) {
            Row row = sheet.getRow(r);
            if (row == null) {
                continue;
            }

            for (Cell cell : row) {
                String value = normalize(fmt.formatCellValue(cell));
                if (value.isEmpty()) {
                    continue;
                }

                if (matchesAny(compact(value), "наименование", "наименован", "марка")) {
                    return r;
                }
            }
        }

        return -1;
    }

    /**
     * Короткий слепок первых строк листа — для текста ошибки.
     *
     * Когда шапка не найдена, администратор остаётся один на один с
     * фразой «не найдена строка заголовков» и не понимает, что
     * система увидела в файле. Слепок показывает прочитанное как
     * есть: видно и опечатку в заголовке, и то, что вместо журнала
     * открыт расчётный лист.
     */
    private static String sheetDigest(Sheet sheet, DataFormatter fmt) {
        StringBuilder digest = new StringBuilder();
        int limit = Math.min(sheet.getLastRowNum(), 5);

        for (int r = 0; r <= limit && digest.length() < 400; r++) {
            Row row = sheet.getRow(r);
            if (row == null) {
                continue;
            }

            for (Cell cell : row) {
                String value = normalize(fmt.formatCellValue(cell));
                if (value.isEmpty()) {
                    continue;
                }

                if (digest.length() > 0) {
                    digest.append(" | ");
                }

                digest.append(value);

                if (digest.length() >= 400) {
                    break;
                }
            }
        }

        return digest.length() == 0 ? "лист пуст" : digest.toString();
    }

    /**
     * Сопоставление правил с индексами колонок шапки.
     *
     * Каждое поле получает первую подходящую колонку, и одна колонка
     * не может достаться двум полям: иначе «принятые меры» и
     * «примечание» читались бы из одного столбца.
     *
     * Сравнение по плотной форме — см. {@link #compact(String)}.
     */
    private Columns mapColumns(Row header, DataFormatter fmt) {
        Columns columns = new Columns();
        if (header == null) {
            return columns;
        }

        for (ColumnRule rule : RULES) {
            for (Cell cell : header) {
                String value = normalize(fmt.formatCellValue(cell));
                if (value.isEmpty()) {
                    continue;
                }

                String dense = compact(value);

                if (matchesAny(dense, rule.keys()) && !columns.isTaken(cell.getColumnIndex())) {
                    columns.assign(rule.field(), cell.getColumnIndex());
                    break;
                }
            }
        }

        return columns;
    }

    /** Номер из комбинированной ячейки: всё до предлога «от дд.мм.гггг». */
    private static String numberPart(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }

        Matcher matcher = TRAILING_DATE.matcher(raw.trim());

        return matcher.find()
                ? blankToNull(raw.substring(0, matcher.start()).trim())
                : blankToNull(raw.trim());
    }

    /**
     * Дата из комбинированной ячейки документа.
     *
     * Сначала пробуется числовое значение самой ячейки: Excel хранит
     * дату числом, и тогда предлог в тексте не нужен. Если ячейка
     * текстовая — дата берётся из хвоста после «от».
     */
    private static LocalDate datePart(
            Row row,
            Columns columns,
            DataFormatter fmt,
            String raw
    ) {
        LocalDate asDate = rawCellDate(row, columns.document, fmt);

        return asDate != null ? asDate : dateFromText(raw);
    }

    /** Дата из текста: хвост после «от», иначе любое вхождение даты. */
    private static LocalDate dateFromText(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }

        String value = raw.trim();
        Matcher trailing = TRAILING_DATE.matcher(value);

        if (trailing.find()) {
            return toLocalDate(trailing.group(1));
        }

        Matcher any = ANY_DATE.matcher(value);

        return any.find() ? toLocalDate(any.group(1)) : null;
    }

    /** Дата колонки: число Excel либо текст «дд.мм.гггг». */
    private static LocalDate parseDate(Row row, int index, DataFormatter fmt) {
        if (index < 0) {
            return null;
        }

        LocalDate numeric = rawCellDate(row, index, fmt);

        return numeric != null ? numeric : dateFromText(cell(row, index, fmt));
    }

    /**
     * Чтение даты из ячейки, отформатированной как дата.
     *
     * Проверка формата обязательна: количество тоже хранится числом,
     * и без неё число 45512 превратилось бы в дату 2024 года.
     */
    private static LocalDate rawCellDate(Row row, int index, DataFormatter fmt) {
        if (index < 0) {
            return null;
        }

        Cell cell = row.getCell(index);
        if (cell == null) {
            return null;
        }

        try {
            boolean isDate = cell.getCellType() == CellType.NUMERIC
                    && DateUtil.isCellDateFormatted(cell);

            if (isDate) {
                return cell.getLocalDateTimeCellValue().toLocalDate();
            }
        } catch (RuntimeException ignored) {
            /* Формат не распознан — строку разберёт текстовая ветка. */
        }

        return null;
    }

    /**
     * Целое из ячейки.
     *
     * Числа листа хранятся дробями (10 читается как 10.0), поэтому
     * значение округляется, а не отбрасывается: «9,5» в колонке
     * количества — опечатка источника, и целое из неё всё равно нужно.
     */
    private static Integer integer(Row row, int index, DataFormatter fmt) {
        if (index < 0) {
            return null;
        }

        Cell cell = row.getCell(index);
        if (cell == null) {
            return null;
        }

        if (cell.getCellType() == CellType.NUMERIC) {
            return (int) Math.round(cell.getNumericCellValue());
        }

        String value = normalize(fmt.formatCellValue(cell))
                .replace("\u00A0", "")
                .replace(" ", "")
                .replace(",", ".");

        if (value.isEmpty()) {
            return null;
        }

        try {
            return (int) Math.round(Double.parseDouble(value));
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    /** Дата текста «дд.мм.гггг» (год из двух цифр читается как 20xx). */
    private static LocalDate toLocalDate(String value) {
        String[] parts = value.split("\\.");

        if (parts.length < 3) {
            return null;
        }

        try {
            int day = Integer.parseInt(parts[0].trim());
            int month = Integer.parseInt(parts[1].trim());
            int year = Integer.parseInt(parts[2].trim());

            if (year < 100) {
                year += 2000;
            }

            return LocalDate.of(year, month, day);
        } catch (RuntimeException exception) {
            return null;
        }
    }

    private static String cell(Row row, int index, DataFormatter fmt) {
        if (index < 0) {
            return "";
        }

        Cell cell = row.getCell(index);

        return cell == null ? "" : fmt.formatCellValue(cell).trim();
    }

    /**
     * Нормализация заголовка: невидимые символы сняты, пробелы и
     * переносы схлопнуты, регистр убран.
     *
     * Заголовки бланка набраны вертикально и в несколько строк,
     * поэтому «Номер и дата сопроводительного документа» приходит из
     * POI с переносами внутри.
     *
     * Невидимые символы удаляются перед сравнением: в узких
     * вертикальных колонках Excel и WPS расставляют мягкие переносы
     * (U+00AD) и вставляют zero-width пробелы (U+200B, U+FEFF), и
     * «Наиме­но­ва­ние» с мягкими переносами не содержало бы слова
     * «наименование» — шапка журнала не находилась бы вовсе, хотя
     * глазами она на месте.
     */
    private static String normalize(String raw) {
        if (raw == null) {
            return "";
        }

        return INVISIBLE.matcher(raw).replaceAll("")
                .replace('\u00A0', ' ')
                .replaceAll("\\s+", " ")
                .trim()
                .toLowerCase(Locale.ROOT);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    /** Правило: поле модели и ключевые слова его заголовка. */
    private record ColumnRule(String field, List<String> keys) { }

    /**
     * Найденные колонки.
     *
     * Значение -1 означает «колонки нет»: строка журнала остаётся с
     * пустым реквизитом, а не отбрасывается — лист заполняют частями,
     * и акт могут оформить позже.
     */
    private static final class Columns {

        private int controlDate = -1;
        private int productName = -1;
        private int supplier = -1;
        private int document = -1;
        private int totalQuantity = -1;
        private int checkedQuantity = -1;
        private int scrapQuantity = -1;
        private int act = -1;
        private int description = -1;
        private int measures = -1;
        private int note = -1;

        boolean isTaken(int index) {
            return controlDate == index
                    || productName == index
                    || supplier == index
                    || document == index
                    || totalQuantity == index
                    || checkedQuantity == index
                    || scrapQuantity == index
                    || act == index
                    || description == index
                    || measures == index
                    || note == index;
        }

        void assign(String field, int index) {
            switch (field) {
                case "controlDate" -> controlDate = index;
                case "productName" -> productName = index;
                case "supplier" -> supplier = index;
                case "document" -> document = index;
                case "totalQuantity" -> totalQuantity = index;
                case "checkedQuantity" -> checkedQuantity = index;
                case "scrapQuantity" -> scrapQuantity = index;
                case "act" -> act = index;
                case "description" -> description = index;
                case "measures" -> measures = index;
                case "note" -> note = index;
                default -> { }
            }
        }
    }

    /**
     * Одна строка листа.
     *
     * @param lineNo         номер строки листа — по нему указывают
     *                       ошибку администратору
     * @param controlDate    дата контроля из колонки A (может отсутствовать)
     * @param productName    наименование, марка, тип
     * @param supplier       написание поставщика из листа
     * @param docNumber      номер сопроводительного документа
     * @param docDate        дата сопроводительного документа
     * @param totalQuantity  общее количество по документу
     * @param checkedQuantity проверенное количество
     * @param scrapQuantity  забракованное количество
     * @param actNumber      номер акта о расхождении
     * @param actDate        дата акта о расхождении
     * @param description    описание несоответствия
     * @param measures       принятые меры
     * @param note           примечание
     */
    public record ParsedJournalRow(
            int lineNo,
            LocalDate controlDate,
            String productName,
            String supplier,
            String docNumber,
            LocalDate docDate,
            Integer totalQuantity,
            Integer checkedQuantity,
            Integer scrapQuantity,
            String actNumber,
            LocalDate actDate,
            String description,
            String measures,
            String note
    ) { }

    /**
     * Результат разбора одной книги: имя листа и строки.
     *
     * Имя листа показывается в предпросмотре — когда в файле
     * несколько листов, администратор видит, какой именно разобран.
     */
    public record ParsedJournal(String sheetName, List<ParsedJournalRow> rows) { }
}
