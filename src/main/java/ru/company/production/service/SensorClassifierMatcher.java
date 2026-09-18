package ru.company.production.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.company.production.entity.ProductClassifier;
import ru.company.production.entity.ProductType;
import ru.company.production.entity.SensorCatalog;
import ru.company.production.repository.ProductClassifierRepository;
import ru.company.production.repository.SensorCatalogRepository;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Подбор классификатора датчика по полному обозначению,
 * которое диспетчер вносит в позицию заказа вручную.
 *
 * Разбор выполняется в три приёма (правило согласовано
 * с технологами и зафиксировано в миграции V18):
 *
 *   1. РОД. Полное обозначение начинается с наименования рода
 *      вместе с моделью, поэтому род ищется как самый длинный
 *      префикс строки среди sensor_catalog.name
 *      («Датчик уровня ультразвуковой ДУУ2М»). Поиск идёт по
 *      всему наименованию рода, а не по его «семейной» части:
 *      модель входит в name и различает рода с одинаковым
 *      описанием.
 *
 *   2. ЭЛЕМЕНТЫ ОБОЗНАЧЕНИЯ. Остаток после наименования рода —
 *      это исполнение: он отсекается от первого пробела (текст
 *      в скобках и любые пояснения в исполнение не входят),
 *      ведущие дефисы и подчёркивания отбрасываются, а далее
 *      строка разбивается по дефису. Элементы нумеруются С ЕДИНИЦЫ:
 *      «ДУУ2М-12-1-15,00-0,15-ОМ1,5**-3» → 1=12, 2=1, 3=15,00 …
 *
 *   3. ЗНАЧИМЫЕ ПАРАМЕТРЫ. Номера значимых элементов заданы
 *      в sensor_catalog.search_parameters («1,2»). Из них
 *      собирается маска исполнения, которая и сравнивается
 *      с product_name классификаторов этого же рода
 *      (product_type = SENSOR). Для примера выше маска «12-1»
 *      ищет датчик ДУУ2М исполнения 12-1.
 *
 * Сравнение с product_name выполняется двумя способами: сначала
 * точное совпадение маски (так устроены короткие маски вроде
 * «12-1»), затем совпадение только значимых позиций — оно нужно
 * для длинных масок, где значимых параметров меньше, чем сегментов
 * («01-02-00-00-00-03-00» при значимых 1 и 6).
 */
@Service
@RequiredArgsConstructor
public class SensorClassifierMatcher {

    private final SensorCatalogRepository sensorCatalogRepository;
    private final ProductClassifierRepository productClassifierRepository;

    /**
     * Результат подбора: найденный классификатор и разбор,
     * полезный для сообщения об ошибке.
     */
    public record Match(
            ProductClassifier classifier,
            SensorCatalog sensorClass,
            String mask
    ) {
    }

    /**
     * Подбирает классификатор по полному обозначению датчика.
     *
     * @param fullName обозначение, введённое диспетчером
     * @throws IllegalArgumentException обозначение разобрать не удалось
     */
    @Transactional(readOnly = true)
    public Match match(String fullName) {

        String normalized = normalize(fullName);

        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(
                    "Укажите полное наименование датчика"
            );
        }

        SensorCatalog sensorClass = findSensorClass(normalized);
        List<String> elements = parseElements(normalized, sensorClass);
        List<Integer> positions = parseSearchPositions(sensorClass);
        String mask = buildMask(elements, positions, sensorClass);

        return new Match(
                findClassifier(sensorClass, mask, elements, positions),
                sensorClass,
                mask
        );
    }

    /**
     * Род датчика — самое длинное наименование sensor_catalog,
     * которым начинается обозначение. Сравнение регистронезависимое:
     * обозначения переносят из документации, где регистр букв
     * и пробелы гуляют.
     */
    private SensorCatalog findSensorClass(String normalized) {

        SensorCatalog best = null;

        for (SensorCatalog catalog : sensorCatalogRepository.findAll()) {

            String name = normalize(catalog.getName());

            if (name.isEmpty()
                    || !startsWithIgnoreCase(normalized, name)) {
                continue;
            }

            /*
             * Род обязан заканчиваться на границе слова: иначе
             * «Датчик давления ДПУ5» ложно подошёл бы к «…ДПУ5А».
             */
            if (normalized.length() > name.length()
                    && !isDelimiter(normalized.charAt(name.length()))) {
                continue;
            }

            if (best == null
                    || normalize(best.getName()).length() < name.length()) {
                best = catalog;
            }
        }

        if (best == null) {
            throw new IllegalArgumentException(
                    "Не удалось определить род датчика по началу наименования. "
                            + "Начните строку с наименования рода из справочника "
                            + "датчиков, например: «Датчик уровня ультразвуковой ДУУ2М-…\""
            );
        }

        return best;
    }

    /**
     * Элементы исполнения: остаток после наименования рода
     * до первого пробела, разбитый по дефису.
     */
    private List<String> parseElements(String normalized, SensorCatalog sensorClass) {

        String rest = normalized
                .substring(normalize(sensorClass.getName()).length())
                .replaceAll("^[-_\\s]+", "");

        int space = rest.indexOf(' ');

        if (space >= 0) {
            rest = rest.substring(0, space);
        }

        if (rest.isBlank()) {
            throw new IllegalArgumentException(
                    "В наименовании нет исполнения после рода «"
                            + sensorClass.getName().trim()
                            + "». Укажите параметры через дефис, например «…ДУУ2М-12-1-…\""
            );
        }

        List<String> elements = new ArrayList<>();

        for (String element : rest.split("-")) {
            elements.add(element.trim());
        }

        return elements;
    }

    /**
     * Номера значимых элементов из search_parameters
     * (перечисление через запятую, нумерация с единицы).
     */
    private List<Integer> parseSearchPositions(SensorCatalog sensorClass) {

        String parameters = sensorClass.getSearchParameters();

        if (parameters == null || parameters.isBlank()) {
            throw new IllegalArgumentException(
                    "У рода «" + sensorClass.getName().trim()
                            + "» не заданы значимые параметры поиска "
                            + "(search_parameters), поэтому классификатор "
                            + "по полному наименованию не подбирается. "
                            + "Заполните параметры в справочнике датчиков "
                            + "или выберите изделие из списка."
            );
        }

        List<Integer> positions = new ArrayList<>();

        for (String token : parameters.split("[,;]")) {

            String value = token.trim();

            if (value.isEmpty()) {
                continue;
            }

            try {
                positions.add(Integer.parseInt(value));
            } catch (NumberFormatException exception) {
                throw new IllegalArgumentException(
                        "В справочнике датчиков у рода «"
                                + sensorClass.getName().trim()
                                + "» значимые параметры заданы неверно: «"
                                + parameters.trim() + "». Ожидается перечень "
                                + "номеров через запятую, например «1,2»."
                );
            }
        }

        if (positions.isEmpty()) {
            throw new IllegalArgumentException(
                    "У рода «" + sensorClass.getName().trim()
                            + "» значимые параметры поиска пусты"
            );
        }

        return positions;
    }

    /**
     * Маска исполнения из значимых элементов обозначения.
     */
    private String buildMask(
            List<String> elements,
            List<Integer> positions,
            SensorCatalog sensorClass
    ) {

        List<String> maskParts = new ArrayList<>();

        for (Integer position : positions) {

            if (position < 1 || position > elements.size()) {
                throw new IllegalArgumentException(
                        "Род «" + sensorClass.getName().trim()
                                + "» требует параметр №" + position
                                + ", но в обозначении только "
                                + elements.size()
                                + " элемент(ов) через дефис: «"
                                + String.join("-", elements) + "»"
                );
            }

            String value = elements.get(position - 1);

            if (value.isEmpty()) {
                throw new IllegalArgumentException(
                        "Параметр №" + position + " пуст в обозначении «"
                                + String.join("-", elements) + "»"
                );
            }

            maskParts.add(value);
        }

        return String.join("-", maskParts);
    }

    /**
     * Классификатор этого же рода с таким же набором значимых параметров.
     */
    private ProductClassifier findClassifier(
            SensorCatalog sensorClass,
            String mask,
            List<String> elements,
            List<Integer> positions
    ) {

        List<ProductClassifier> candidates = productClassifierRepository
                .findByProductTypeAndSensorCatalog_IdOrderByCodeAsc(
                        ProductType.SENSOR,
                        sensorClass.getId()
                );

        if (candidates.isEmpty()) {
            throw new IllegalArgumentException(
                    "В классификаторе нет изделий рода «"
                            + sensorClass.getName().trim() + "»"
            );
        }

        /*
         * Короткие маски (Датчик уровня ультразвуковой ДУУ2М — «12-1»)
         * совпадают с product_name целиком.
         */
        List<ProductClassifier> exact = candidates.stream()
                .filter(candidate -> mask.equalsIgnoreCase(
                        normalize(candidate.getName())
                ))
                .toList();

        if (exact.size() == 1) {
            return exact.get(0);
        }

        /*
         * Длинные маски содержат больше сегментов, чем значимых
         * параметров, поэтому сравниваются только значимые позиции.
         */
        List<ProductClassifier> partial = candidates.stream()
                .filter(candidate -> matchesPositions(
                        candidate.getName(),
                        elements,
                        positions
                ))
                .toList();

        List<ProductClassifier> found = partial.isEmpty() ? exact : partial;

        if (found.isEmpty()) {
            throw new IllegalArgumentException(
                    "В классификаторе нет датчика рода «"
                            + sensorClass.getName().trim()
                            + "» с параметрами «" + mask + "». "
                            + "Проверьте параметры исполнения или подберите "
                            + "изделие в классификаторе."
            );
        }

        if (found.size() > 1) {
            throw new IllegalArgumentException(
                    "Полному наименованию соответствуют несколько изделий "
                            + "рода «" + sensorClass.getName().trim()
                            + "» с параметрами «" + mask + "»: "
                            + codes(found)
                            + ". Уточните параметры исполнения."
            );
        }

        return found.get(0);
    }

    /**
     * Совпадение значимых позиций: сегмент product_name на номере
     * из search_parameters должен равняться соответствующему элементу
     * обозначения.
     */
    private boolean matchesPositions(
            String productName,
            List<String> elements,
            List<Integer> positions
    ) {

        String normalized = normalize(productName);

        if (normalized.isEmpty()) {
            return false;
        }

        String[] parts = normalized.split("-");

        for (Integer position : positions) {

            if (position > parts.length) {
                return false;
            }

            String expected = elements.get(position - 1);

            if (!parts[position - 1].equalsIgnoreCase(expected)) {
                return false;
            }
        }

        return true;
    }

    private String codes(List<ProductClassifier> classifiers) {
        return classifiers.stream()
                .map(classifier -> "код " + classifier.getCode())
                .reduce((left, right) -> left + ", " + right)
                .orElse("");
    }

    /**
     * Приведение строки к виду, пригодному для разбора:
     * неразрывные пробелы и переносы становятся обычными,
     * повторные пробелы схлопываются.
     */
    private String normalize(String value) {
        if (value == null) {
            return "";
        }

        return value
                .replace('\u00A0', ' ')
                .replaceAll("\\s+", " ")
                .trim();
    }

    private boolean startsWithIgnoreCase(String text, String prefix) {
        return text.regionMatches(
                true,
                0,
                prefix,
                0,
                prefix.length()
        );
    }

    /**
     * Граница слова обозначения: дефис, подчёркивание, пробел
     * или открывающая скобка уточнения.
     */
    private boolean isDelimiter(char symbol) {
        return switch (symbol) {
            case '-', '_', ' ', '(', '[', ',' -> true;
            default -> false;
        };
    }
}
