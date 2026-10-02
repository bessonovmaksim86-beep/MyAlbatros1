package ru.company.production.service.checklist;

import org.apache.poi.ss.usermodel.*;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import java.io.InputStream;
import java.math.BigDecimal;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Разбор чек-листа Б24 (.xls / .xlsx) в построчный предпросмотр.
 *
 * Раскладка листа заранее не известна, поэтому парсер:
 *  1) ищет строку-заголовок (первая, где есть и «наименование/изделие»,
 *     и «количество»);
 *  2) сопоставляет колонки по синонимам;
 *  3) извлекает строки с непустым наименованием и числовым количеством.
 *
 * Количество может храниться двумя способами:
 *  а) отдельная колонка «количество/кол-во» (устаревший формат);
 *  б) в конце наименования — «…, 1 шт», «…, 4 компл», «…, 10 шт».
 * Парсер пробует оба варианта.
 */
@Component
public class ChecklistParser {

    private static final List<String> NAME_KEYS =
            List.of("наименов", "изделие", "назван", "номенклат", "поз");
    private static final List<String> QTY_KEYS =
            List.of("кол-во", "количество", "кол", "qty", "объём", "объем");
    private static final List<String> UNIT_KEYS =
            List.of("единиц", "изм", "ед");
    private static final List<String> NOTE_KEYS =
            List.of("примеч", "коммент", "отмет");

    /** Суффиксы количества, которые ищутся в конце наименования. */
    private static final List<String> NAME_QTY_SUFFIXES =
            List.of("шт", "компл", "комплект", "ком", "кг", "г", "м", "мм", "л");

    private static final Pattern TRAILING_QTY = Pattern.compile(
            "(\\d+(?:[.,]\\d+)?)\\s*(шт|компл|комплект|ком|кг|г|м|мм|мл|л)\\s*$",
            Pattern.CASE_INSENSITIVE);

    public ParsedChecklist parse(MultipartFile file) {
        try (InputStream in = file.getInputStream();
             Workbook wb = WorkbookFactory.create(in)) {

            Sheet sheet = wb.getSheetAt(0);
            DataFormatter fmt = new DataFormatter();

            int headerRow = findHeaderRow(sheet, fmt);
            if (headerRow < 0) {
                throw new IllegalArgumentException(
                        "В файле не найдена строка заголовка "
                                + "(нужны колонки «наименование» и «количество»)");
            }

            Map<String, Integer> cols = mapColumns(sheet.getRow(headerRow), fmt);
            if (!cols.containsKey("name")) {
                throw new IllegalArgumentException(
                        "В файле не найдена колонка «наименование»");
            }
            // Колонка «количество» опциональна: для части форматов
            // количество «зашито» в конец наименования («…, 1 шт»).
            boolean hasQtyColumn = cols.containsKey("qty");

            List<ParsedRow> rows = new ArrayList<>();
            for (int r = headerRow + 1; r <= sheet.getLastRowNum(); r++) {
                Row row = sheet.getRow(r); // org.apache.poi.ss.usermodel.Row
                if (row == null) {
                    continue;
                }

                String name = cell(row, cols.get("name"), fmt);
                if (name.isBlank()) {
                    continue;
                }

                BigDecimal qty = null;
                if (hasQtyColumn) {
                    qty = number(row, cols.get("qty"), fmt);
                }
                if (qty == null || qty.signum() <= 0) {
                    qty = extractQtyFromName(name);
                }
                if (qty == null || qty.signum() <= 0) {
                    continue;
                }

                String unit = "";
                if (cols.containsKey("unit")) {
                    unit = cell(row, cols.get("unit"), fmt);
                }
                if (unit.isBlank() && hasQtyColumn) {
                    unit = toDefaultUnit(name);
                }

                rows.add(new ParsedRow(
                        r + 1,
                        name,
                        qty,
                        unit,
                        cols.containsKey("note") ? cell(row, cols.get("note"), fmt) : ""
                ));
            }

            if (rows.isEmpty()) {
                throw new IllegalArgumentException(
                        "В файле нет ни одной позиции с количеством");
            }
            return new ParsedChecklist(safeName(file.getOriginalFilename()), rows);
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException(
                    "Не удалось прочитать файл: " + e.getMessage());
        }
    }

    private static BigDecimal extractQtyFromName(String name) {
        String trimmed = name.trim();
        int lastComma = trimmed.lastIndexOf(',');
        if (lastComma < 0) {
            lastComma = trimmed.lastIndexOf('，');
        }
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
                    // пробуем общий паттерн ниже
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

    private static String toDefaultUnit(String name) {
        String lower = name.toLowerCase(Locale.ROOT).trim();
        for (String suffix : NAME_QTY_SUFFIXES) {
            if (lower.endsWith(" " + suffix) || lower.endsWith("," + suffix)) {
                return toUnitName(suffix);
            }
        }
        return "PCS";
    }

    private static String toUnitName(String suffix) {
        if (suffix == null) return "PCS";
        String s = suffix.toLowerCase(Locale.ROOT).trim();
        if (s.startsWith("кг")) return "KG";
        if (s.startsWith("мм")) return "MM";
        if (s.startsWith("мл")) return "ML";
        if (s.startsWith("г"))  return "G";
        if (s.startsWith("м"))  return "M";
        if (s.startsWith("л"))  return "L";
        return "PCS";
    }

    private int findHeaderRow(Sheet sheet, DataFormatter fmt) {
        int limit = Math.min(sheet.getLastRowNum(), 40);
        for (int r = 0; r <= limit; r++) {
            Row row = sheet.getRow(r);
            if (row == null) {
                continue;
            }
            boolean hasName = false;
            boolean hasQty = false;
            for (Cell c : row) {
                String v = normalize(fmt.formatCellValue(c));
                if (v.isEmpty()) continue;
                if (!hasName && containsAny(v, NAME_KEYS)) hasName = true;
                if (!hasQty  && containsAny(v, QTY_KEYS))  hasQty  = true;
            }
            if (hasName && hasQty) {
                return r;
            }
        }
        return -1;
    }

    private Map<String, Integer> mapColumns(Row header, DataFormatter fmt) {
        Map<String, Integer> cols = new HashMap<>();
        for (Cell c : header) {
            String v = normalize(fmt.formatCellValue(c));
            if (v.isEmpty()) continue;
            if (!cols.containsKey("name") && containsAny(v, NAME_KEYS)) {
                cols.put("name", c.getColumnIndex());
            } else if (!cols.containsKey("qty") && containsAny(v, QTY_KEYS)) {
                cols.put("qty", c.getColumnIndex());
            } else if (!cols.containsKey("unit") && containsAny(v, UNIT_KEYS)) {
                cols.put("unit", c.getColumnIndex());
            } else if (!cols.containsKey("note") && containsAny(v, NOTE_KEYS)) {
                cols.put("note", c.getColumnIndex());
            }
        }
        return cols;
    }

    private static String normalize(String raw) {
        if (raw == null) return "";
        return raw.replace('\u00A0', ' ')
                .replaceAll("\\s+", " ")
                .trim()
                .toLowerCase(Locale.ROOT);
    }

    private String cell(Row row, Integer idx, DataFormatter fmt) {
        if (idx == null) return "";
        Cell c = row.getCell(idx);
        return c == null ? "" : fmt.formatCellValue(c).trim();
    }

    private BigDecimal number(Row row, Integer idx, DataFormatter fmt) {
        if (idx == null) return null;
        Cell c = row.getCell(idx);
        if (c == null) return null;
        try {
            if (c.getCellType() == CellType.NUMERIC) {
                return BigDecimal.valueOf(c.getNumericCellValue());
            }
            String raw = fmt.formatCellValue(c)
                    .replace("\u00A0", "")
                    .replace(" ", "")
                    .replace(',', '.')
                    .trim();
            return raw.isEmpty() ? null : new BigDecimal(raw);
        } catch (Exception e) {
            return null;
        }
    }

    private boolean containsAny(String v, List<String> keys) {
        for (String k : keys) {
            if (v.contains(k)) return true;
        }
        return false;
    }

    private String safeName(String original) {
        if (original == null || original.isBlank()) {
            return "чек-лист";
        }
        return original.replaceAll(".*[/\\\\]", "");
    }

    public record ParsedChecklist(String fileName, List<ParsedRow> rows) { }

    public record ParsedRow(int lineNo, String name, BigDecimal qty,
                            String unit, String note) { }
}