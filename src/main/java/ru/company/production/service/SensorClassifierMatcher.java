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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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

    /*
     * Диапазон в шаблоне классификатора: «(1-4)» — допустимые значения
     * от первой границы до второй включительно.
     */
    private static final Pattern RANGE =
            Pattern.compile("\\((\\d+)\\s*-\\s*(\\d+)\\)");

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
     *
     * Разбиение скобочное (см. {@link #tokenizeParameters}): у
     * длинных обозначений вроде ДУУ10 параметр сам содержит дефис
     * внутри скобок — «(1-4)», — и обычный split по дефису разрезал
     * бы его на два элемента, сдвинув все последующие позиции.
     * Значимые параметры рода (search_parameters) задаются именно по
     * целым параметрам, поэтому и здесь обязан считаться целый
     * параметр: без этого обозначение «…ДУУ10-02-0-3,55-…-I» дало бы
     * маску не «02-10-1-I», а сдвинутую, и классификатор не нашёлся бы.
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

        return tokenizeParameters(rest);
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
                            + ". Уточните параметры исполнения "
                            + "или выберите изделие в справочнике вручную."
            );
        }

        return found.get(0);
    }

    /**
     * Совпадение значимых позиций: сегмент product_name на номере
     * из search_parameters должен равняться соответствующему элементу
     * обозначения.
     *
     * product_name разбирается тем же скобочным разбором, что и
     * обозначение (см. {@link #tokenizeParameters}): иначе «(1-4)»
     * сдвинул бы позиции и значимый параметр сравнивался бы не со
     * своим сегментом.
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

        List<String> parts = tokenizeParameters(normalized);

        for (Integer position : positions) {

            if (position > parts.size()) {
                return false;
            }

            if (!parameterMatches(
                    parts.get(position - 1),
                    elements.get(position - 1))) {
                return false;
            }
        }

        return true;
    }

    /**
     * Совпадает ли обозначение с описанием изделия в классификаторе.
     *
     * Классификатор описывает изделие шаблоном, а не конкретным
     * обозначением: там, где номинал не влияет на выбор изделия,
     * стоит заглушка «X» («XXX» — для параметра переменной длины),
     * а там, где допустимы несколько значений — диапазон «(1-4)».
     * Сравнение по точному равенству такую строку не принимало бы
     * никогда, и род ДУУ10 с полностью шаблонными product_name не
     * подбирался бы вовсе.
     *
     * Заглушка принимает любое непустое значение; диапазон — только
     * число из указанного отрезка (границы включительно). Конкретный
     * сегмент, как и раньше, обязан совпасть с обозначением.
     */
    private boolean parameterMatches(String template, String value) {

        String element = value == null ? "" : value.trim();

        if (element.isEmpty()) {
            return false;
        }

        if (isPlaceholder(template)) {
            return true;
        }

        Matcher range = RANGE.matcher(template.trim());

        if (range.matches()) {
            try {
                int number = Integer.parseInt(element);

                return number >= Integer.parseInt(range.group(1))
                        && number <= Integer.parseInt(range.group(2));
            } catch (NumberFormatException exception) {
                return false;
            }
        }

        return template.equalsIgnoreCase(element);
    }

    /**
     * Заглушка шаблона: параметр, заданный не номиналом, а знаком
     * «любой» — «X», «XX», «XXX».
     *
     * Проверяются оба алфавита: normalize приводит латинскую «X»
     * к кириллической «Х», и в разобранный product_name заглушка
     * приходит уже кириллицей, даже если в базе она набрана латиницей.
     */
    private boolean isPlaceholder(String template) {

        String trimmed = template == null ? "" : template.trim();

        return !trimmed.isEmpty()
                && trimmed.chars().allMatch(SensorClassifierMatcher::isWildcardLetter);
    }

    private static boolean isWildcardLetter(int symbol) {

        return symbol == 'X'
                || symbol == 'x'
                || symbol == '\u0425'   // кириллическая «Х»
                || symbol == '\u0445';  // кириллическая «х»
    }

    /**
     * Разбиение строки исполнения на параметры.
     *
     * Разделитель — дефис вне скобок. Скобочная часть («(1-4)» у
     * ДУУ10) принадлежит одному параметру: в обозначении КД и в
     * шаблоне классификатора она пишется одинаково, и только так
     * номера позиций из search_parameters соответствуют одним и тем
     * же параметрам с обеих сторон.
     */
    private List<String> tokenizeParameters(String value) {

        List<String> parts = new ArrayList<>();

        StringBuilder current = new StringBuilder();

        int depth = 0;

        for (int index = 0; index < value.length(); index++) {

            char symbol = value.charAt(index);

            if (symbol == '(') {
                depth++;
                current.append(symbol);
            } else if (symbol == ')') {
                if (depth > 0) {
                    depth--;
                }
                current.append(symbol);
            } else if (symbol == '-' && depth == 0) {
                parts.add(current.toString().trim());
                current.setLength(0);
            } else {
                current.append(symbol);
            }
        }

        parts.add(current.toString().trim());

        return parts;
    }

    /**
     * Перечисление кодов для текста ошибки. Числа значений search_parameters
     * у части родов задают слишком общий признак (у ДТМ-3 позиции 1, 6, 11 и
     * 13 могут давать несколько изделий с одинаковым product_name), поэтому
     * список ограничивается первыми кодами: иначе сообщение растягивается на
     * десятки позиций и в интерфейсе становится бесполезным.
     */
    private static final int MAX_LISTED_CODES = 5;

    private String codes(List<ProductClassifier> classifiers) {

        List<String> listed = classifiers.stream()
                .limit(MAX_LISTED_CODES)
                .map(classifier -> "код " + classifier.getCode())
                .toList();

        String text = String.join(", ", listed);

        int rest = classifiers.size() - listed.size();

        return rest > 0 ? text + " и ещё " + rest : text;
    }

    /**
     * Приведение строки к виду, пригодному для разбора:
     * неразрывные пробелы и переносы становятся обычными,
     * повторные пробелы схлопываются, латинские буквы, визуально
     * совпадающие с кириллицей, приводятся к кириллице.
     *
     * Латиницу приводит и SystemSheetMatcher для систем и приборов
     * (normalizeCyrillic): обозначения переписываются из КД на
     * латинской раскладке («01A-0» вместо «01А-0»), и это не опечатка,
     * а особенность ввода. Для датчиков такая путаница раньше роняла
     * подбор на точном сравнении маски: «01A-0» (латиница) не равен
     * «01А-0» (кириллица) даже через equalsIgnoreCase — это разные
     * алфавиты, а не разный регистр. Приведение симметрично для обеих
     * сравниваемых сторон (обозначение и product_name/наименование
     * рода), поэтому корректный кириллический ввод оно не меняет.
     */
    private String normalize(String value) {
        if (value == null) {
            return "";
        }

        return normalizeCyrillic(
                value
                        .replace('\u00A0', ' ')
                        .replaceAll("\\s+", " ")
                        .trim()
        );
    }

    /**
     * Латиница → кириллица по визуальной схожести, с сохранением
     * регистра: замена посимвольная, длина строки не меняется,
     * поэтому повторная нормализация идемпотентна.
     */
    private String normalizeCyrillic(String value) {

        StringBuilder normalized = new StringBuilder(value.length());

        for (int i = 0; i < value.length(); i++) {
            normalized.append(cyrillicLookalike(value.charAt(i)));
        }

        return normalized.toString();
    }

    private char cyrillicLookalike(char symbol) {

        return switch (symbol) {
            case 'A' -> '\u0410';
            case 'a' -> '\u0430';
            case 'B' -> '\u0412';
            case 'b' -> '\u0432';
            case 'C' -> '\u0421';
            case 'c' -> '\u0441';
            case 'E' -> '\u0415';
            case 'e' -> '\u0435';
            case 'H' -> '\u041D';
            case 'h' -> '\u043D';
            case 'K' -> '\u041A';
            case 'k' -> '\u043A';
            case 'M' -> '\u041C';
            case 'm' -> '\u043C';
            case 'O' -> '\u041E';
            case 'o' -> '\u043E';
            case 'P' -> '\u0420';
            case 'p' -> '\u0440';
            case 'T' -> '\u0422';
            case 't' -> '\u0442';
            case 'X' -> '\u0425';
            case 'x' -> '\u0445';
            case 'Y' -> '\u0423';
            case 'y' -> '\u0443';
            default -> symbol;
        };
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
