package ru.company.production.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import ru.company.production.entity.ProductClassifier;
import ru.company.production.entity.ProductType;
import ru.company.production.entity.SensorCatalog;
import ru.company.production.repository.ProductClassifierRepository;
import ru.company.production.repository.SensorCatalogRepository;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.when;

/**
 * Проверка подбора классификатора датчика ДУУ2М.
 *
 * <p>Данные повторяют реальные строки справочника: род «Датчик уровня
 * ультразвуковой ДУУ2М» со значимыми параметрами «1,2» и изделие кода
 * 1192 с product_name «01А-0» (кириллическая «А»). Обозначение из КД
 * диспетчер может ввести и в латинской раскладке — «01A-0» с латинской
 * «A», — и подбор не имеет права на этом спотыкаться: SystemSheetMatcher
 * для систем и приборов такую путаницу уже прощает (normalizeCyrillic),
 * а для датчиков раньше нет.
 */
@ExtendWith(MockitoExtension.class)
class SensorClassifierMatcherTest {

    @Mock
    private SensorCatalogRepository sensorCatalogRepository;

    @Mock
    private ProductClassifierRepository productClassifierRepository;

    @InjectMocks
    private SensorClassifierMatcher matcher;

    private static final String CYRILLIC_A = "\u0410";
    private static final String LATIN_A = "A";

    private SensorCatalog sensorClass() {

        SensorCatalog catalog = new SensorCatalog();

        catalog.setId(2L);
        catalog.setName("Датчик уровня ультразвуковой ДУУ2М");
        catalog.setSearchParameters("1,2");

        return catalog;
    }

    private ProductClassifier classifier(String productName) {

        ProductClassifier classifier = new ProductClassifier();

        classifier.setId(1192L);
        classifier.setCode(1192);
        classifier.setProductType(ProductType.SENSOR);
        classifier.setName(productName);
        classifier.setSensorCatalog(sensorClass());

        return classifier;
    }

    private void givenSensorCatalog() {

        when(sensorCatalogRepository.findAll())
                .thenReturn(List.of(sensorClass()));

        when(productClassifierRepository
                .findByProductTypeAndSensorCatalog_IdOrderByCodeAsc(
                        ProductType.SENSOR, 2L))
                .thenReturn(List.of(
                        classifier("01" + CYRILLIC_A + "-0")));
    }

    private String designation(String letter) {

        return "Датчик уровня ультразвуковой ДУУ2М-01" + letter
                + "-0-15,00-0,15-ОМ1,5**-3 (поплавок титановый тип II"
                + " УНКР.305446.080; поплавок титановый тип III)";
    }

    @Test
    @DisplayName("Кириллическое обозначение подбирается как раньше")
    void matchesCyrillicDesignation() {

        givenSensorCatalog();

        SensorClassifierMatcher.Match match =
                matcher.match(designation(CYRILLIC_A));

        assertEquals(1192L, match.classifier().getId());
    }

    @Test
    @DisplayName("Латинская «A» в обозначении не должна ломать подбор")
    void matchesLatinLookalikeDesignation() {

        givenSensorCatalog();

        SensorClassifierMatcher.Match match = assertCanMatch(
                designation(LATIN_A));

        assertEquals(1192L, match.classifier().getId());
    }

    /**
     * Возвращает результат подбора, не требуя успеха: тест обязан
     * увидеть и причину падения, если оно есть.
     */
    private SensorClassifierMatcher.Match assertCanMatch(String fullName) {

        try {
            return matcher.match(fullName);
        } catch (IllegalArgumentException exception) {
            throw new AssertionError(
                    "Подбор не должен падать на латинской раскладке: "
                            + exception.getMessage(),
                    exception
            );
        }
    }

    @Test
    @DisplayName("Ошибочный род по-прежнему отклоняется")
    void rejectsUnknownSensorClass() {

        when(sensorCatalogRepository.findAll())
                .thenReturn(List.of(sensorClass()));

        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> matcher.match(
                        "Датчик давления ДПУ5-12-1"));

        assertEquals(true,
                exception.getMessage().contains("род датчика"));
    }

    /*
     * Род ДУУ10 устроен иначе, чем ДУУ2М: его изделия описаны
     * шаблоном, а не конкретным обозначением. В product_name стоят
     * заглушки «X» (любой номинал), «XXX» (любой многозначный) и
     * диапазон «(1-4)», а значимых параметров четыре — позиции
     * 1, 6, 7 и 13.
     *
     * Данные ниже — реальные строки классификатора: рядом с искомаем
     * 1255 оставлены его близнецы, отличающиеся одним значимым
     * параметром (1254 — длина 02 вместо 10, 1256 — исполнение D
     * вместо I). Подбор обязан выделить среди них ровно один код.
     */

    private static final String DUU10_CLASS =
            "Уровнемер поплавковый ДУУ10";

    /**
     * Обозначение из чек-листа: параметры 02 … и исполнение I на
     * тринадцатой позиции; всё в скобках — особенности, которые
     * разбор отсекает по первому пробелу.
     */
    private static final String DUU10_DESIGNATION = DUU10_CLASS
            + "-02-0-3,55-0-0-10-1-0-1-1-000-1-I"
            + " (с фторопластовым покрытием ЧЭ и фторопластовыми"
            + " втулками на поплавках; поплавок тип IV"
            + " УНКР.305446.012)";

    private SensorCatalog duu10Class() {

        SensorCatalog catalog = new SensorCatalog();

        catalog.setId(3L);
        catalog.setName(DUU10_CLASS);
        catalog.setSearchParameters("1,6,7,13");

        return catalog;
    }

    private ProductClassifier duu10(long id, String template) {

        ProductClassifier classifier = new ProductClassifier();

        classifier.setId(id);
        classifier.setCode((int) id);
        classifier.setProductType(ProductType.SENSOR);
        classifier.setName(template);
        classifier.setSensorCatalog(duu10Class());

        return classifier;
    }

    @Test
    @DisplayName("ДУУ10: шаблон с заглушками X и диапазоном (1-4) подбирается")
    void matchesDuu10TemplateWithPlaceholders() {

        when(sensorCatalogRepository.findAll())
                .thenReturn(List.of(duu10Class()));

        when(productClassifierRepository
                .findByProductTypeAndSensorCatalog_IdOrderByCodeAsc(
                        ProductType.SENSOR, 3L))
                .thenReturn(List.of(
                        duu10(1247, "02-X-(1-4)-X-X-00-1-X-X-X-XXX-X-I"),
                        duu10(1254, "02-X-(1-4)-X-X-02-1-X-X-X-XXX-X-D0"),
                        duu10(1255, "02-X-(1-4)-X-X-10-1-X-X-X-XXX-X-I"),
                        duu10(1256, "02-X-(1-4)-X-X-10-1-X-X-X-XXX-X-D")));

        SensorClassifierMatcher.Match match =
                matcher.match(DUU10_DESIGNATION);

        /*
         * Маска значимых позиций 1, 6, 7 и 13 — «02-10-1-I»: ровно
         * то, что диспетчер ждёт от этого обозначения.
         */
        assertEquals("02-10-1-I", match.mask());
        assertEquals(1255L, match.classifier().getId());
    }

    @Test
    @DisplayName("ДУУ10: исполнение D вместо I выбирает другой код")
    void matchesDuu10ByLastSignificantParameter() {

        when(sensorCatalogRepository.findAll())
                .thenReturn(List.of(duu10Class()));

        when(productClassifierRepository
                .findByProductTypeAndSensorCatalog_IdOrderByCodeAsc(
                        ProductType.SENSOR, 3L))
                .thenReturn(List.of(
                        duu10(1255, "02-X-(1-4)-X-X-10-1-X-X-X-XXX-X-I"),
                        duu10(1256, "02-X-(1-4)-X-X-10-1-X-X-X-XXX-X-D")));

        SensorClassifierMatcher.Match match = matcher.match(
                DUU10_DESIGNATION.replace("-1-I (", "-1-D ("));

        assertEquals(1256L, match.classifier().getId());
    }
}
