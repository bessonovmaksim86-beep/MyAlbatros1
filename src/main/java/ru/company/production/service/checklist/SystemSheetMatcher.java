package ru.company.production.service.checklist;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.company.production.entity.ProductClassifier;
import ru.company.production.entity.ProductType;
import ru.company.production.repository.ProductClassifierRepository;
import ru.company.production.repository.SystemComponentProjection;
import ru.company.production.service.checklist.ChecklistParser.ParsedRow;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Иерархия чек-листа: изделие и его состав.
 *
 * Номер строки в чек-листе («№ п/п») несёт структуру заказа: «1» —
 * изделие, «1.1» и «1.2» — то, из чего оно состоит. Изделие со
 * составом — это система: в заказе она одной позицией (тип SYSTEM),
 * а её состав определён классификатором (system_components).
 *
 * Проверка состава нужна, пока лист не попал в заказ: если прибор или
 * род датчика из листа отсутствует в составе системы-классификатора,
 * выпуск системы не соберёт её из этих изделий, и диспетчер узнает об
 * этом уже в цеху. Программа сообщает об этом сразу при разборе —
 * «добавьте изделие в состав системы» — и заказчик заводит состав в
 * классификаторе, а не разводит руками.
 */
@Service
@RequiredArgsConstructor
public class SystemSheetMatcher {

    private final ProductClassifierRepository classifierRepository;

    /**
     * Разбор строк листа на изделия и их состав.
     *
     * Ключ — номер родительской строки («1»), значение — строки
     * состава («1.1», «1.2»). Строки без номера и строки с целым
     * номером без дробной части в состав не попадают.
     */
    public Map<String, List<ParsedRow>> componentsByParent(List<ParsedRow> rows) {

        Map<String, List<ParsedRow>> grouped = new LinkedHashMap<>();

        for (ParsedRow row : rows) {
            if (row.isComponent()) {
                grouped.computeIfAbsent(row.parentNumber(), k ->
                        new ArrayList<>()).add(row);
            }
        }

        return grouped;
    }

    /**
     * Система классификатора, которой соответствует родительская
     * строка листа; null — система не найдена или выбор неоднозначен.
     *
     * Наименование в листе («Уровнемер многоканальный ДУУ10
     * исполнение 2-») и в классификаторе («Система ДУУ10 исп 2»)
     * различаются: сравнение идёт по нормализованным строкам, где
     * «исполнение» свёрнуто до «исп», а служебное слово «Система»
     * снято. Точное совпадение важнее вхождения: из нескольких
     * совпадений по вхождению выбирается единственное, иначе система
     * не определяется (угадывать нельзя).
     */
    @Transactional(readOnly = true)
    public ProductClassifier findSystem(String sheetName) {

        String needle = normalize(stripQuantity(sheetName));

        if (needle.isEmpty()) {
            return null;
        }

        ProductClassifier contained = null;
        boolean ambiguous = false;

        for (ProductClassifier candidate
                : classifierRepository.findAllByOrderByCodeAsc()) {

            if (candidate.getProductType() != ProductType.SYSTEM) {
                continue;
            }

            String name = normalize(candidate.getName());

            if (name.isEmpty()) {
                continue;
            }

            if (name.equals(needle)) {
                return candidate;
            }

            if (needle.contains(name) || name.contains(needle)) {
                if (contained != null) {
                    ambiguous = true;
                }
                contained = candidate;
            }
        }

        return ambiguous ? null : contained;
    }

    /**
     * Состав системы из классификатора (наименования родов датчиков и
     * приборов); пустой список — у системы состава нет.
     */
    @Transactional(readOnly = true)
    public List<String> systemComponentNames(Long systemId) {

        List<String> names = new ArrayList<>();

        for (SystemComponentProjection component
                : classifierRepository.findSystemComponents(systemId)) {

            String name = component.getSensorCatalogName() != null
                    ? component.getSensorCatalogName()
                    : component.getComponentName();

            if (name != null && !name.isBlank()) {
                names.add(name.trim());
            }
        }

        return names;
    }

    /**
     * Строки состава листа, которых нет в классификаторе системы.
     *
     * Изделие считается входящим в состав, если наименование
     * компонента классификатора («Уровнемер поплавковый ДУУ10») —
     * начало наименования из листа
     * («Уровнемер поплавковый ДУУ10-02-0-3,55-…», т.е. конкретное
     * исполнение этого рода).
     */
    @Transactional(readOnly = true)
    public List<ParsedRow> missingComponents(
            ProductClassifier system,
            List<ParsedRow> sheetComponents
    ) {

        List<String> allowed = systemComponentNames(system.getId()).stream()
                .map(SystemSheetMatcher::normalize)
                .filter(name -> !name.isEmpty())
                .toList();

        List<ParsedRow> missing = new ArrayList<>();

        for (ParsedRow row : sheetComponents) {

            String name = normalize(stripQuantity(row.name()));

            boolean known = !name.isEmpty()
                    && allowed.stream().anyMatch(name::contains);

            if (!known) {
                missing.add(row);
            }
        }

        return missing;
    }

    /**
     * Ошибка иерархии листа или null, если всё в порядке.
     *
     * Проверяются две вещи: строки состава без своей родительской
     * строки (лист скопирован без неё — позиции потерялись бы) и
     * состав, отсутствующий в классификаторе системы. Система, которой
     * в классификаторе нет, ошибкой не считается: позиция останется с
     * не выбранным изделием, и диспетчер укажет его в форме сам.
     */
    @Transactional(readOnly = true)
    public String compositionProblem(List<ParsedRow> rows) {

        Map<String, List<ParsedRow>> byParent = componentsByParent(rows);

        List<String> parents = rows.stream()
                .filter(row -> row.number() != null && !row.isComponent())
                .map(ParsedRow::number)
                .toList();

        for (Map.Entry<String, List<ParsedRow>> entry
                : byParent.entrySet()) {

            if (!parents.contains(entry.getKey())) {
                String numbers = entry.getValue().stream()
                        .map(ParsedRow::number)
                        .reduce((a, b) -> a + ", " + b)
                        .orElse("");
                return "В листе есть строки состава «" + numbers
                        + "», но нет строки изделия «" + entry.getKey()
                        + "». Скопируйте чек-лист целиком, вместе со"
                        + " строкой изделия.";
            }
        }

        for (ParsedRow row : rows) {

            if (row.number() == null || row.isComponent()) {
                continue;
            }

            List<ParsedRow> children = byParent.get(row.number());

            if (children == null || children.isEmpty()) {
                continue;
            }

            ProductClassifier system = findSystem(row.name());

            if (system == null) {
                continue;
            }

            List<ParsedRow> missing = missingComponents(system, children);

            if (!missing.isEmpty()) {
                StringBuilder message = new StringBuilder(
                        "Система «" + system.getName()
                                + "»: в её классификаторе нет состава из листа — "
                );
                for (int i = 0; i < missing.size(); i++) {
                    if (i > 0) {
                        message.append("; ");
                    }
                    message.append('«')
                            .append(shorten(stripQuantity(missing.get(i).name())))
                            .append('»');
                }
                message.append(". Добавьте эти изделия в состав системы"
                        + " в классификаторе и повторите загрузку листа.");
                return message.toString();
            }
        }

        return null;
    }

    /**
     * Количество из конца наименования для сопоставления имён:
     * «…, 1 шт» и хвостовое «-» — не часть наименования изделия.
     */
    static String stripQuantity(String name) {

        if (name == null) {
            return "";
        }

        return name
                .replaceAll(",?\\s*\\d+(?:[.,]\\d+)?\\s*"
                        + "(шт\\.?|компл\\.?|комплект|ком\\.?|кг|г|м|мм|мл|л)"
                        + "\\s*$", "")
                .replaceAll("[\\s,;\\-.]+$", "")
                .trim();
    }

    private static String shorten(String value) {
        return value.length() <= 80 ? value : value.substring(0, 77) + "...";
    }

    /**
     * Наименование для сравнения: регистр, «ё», кавычки, «исполнение»
     * и служебное слово «Система» различий не несут.
     */
    private static String normalize(String value) {

        if (value == null) {
            return "";
        }

        String normalized = value
                .replace('\u00A0', ' ')
                .toLowerCase(Locale.ROOT)
                .replace('ё', 'е')
                .replaceAll("[«»\"'`.,;:]", " ")
                .replace("исполнение", "исп");

        normalized = normalized.replaceAll("(^|\\s)система(\\s|$)", " ");
        normalized = normalized.replaceAll("\\s+", " ").trim();
        normalized = normalized.replaceAll("^-+|-+$", "");

        return normalized.trim();
    }
}
