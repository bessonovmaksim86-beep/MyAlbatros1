package ru.company.production.service.checklist;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import ru.company.production.entity.ProductClassifier;
import ru.company.production.entity.ProductType;
import ru.company.production.repository.ProductClassifierRepository;
import ru.company.production.repository.SystemComponentProjection;
import ru.company.production.service.checklist.ChecklistParser.ParsedRow;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * Проверка иерархии чек-листа по классификатору систем.
 *
 * <p>Строка со составом обязана соответствовать системе из
 * классификатора, а её строки состава — входить в состав этой
 * системы. Ошибка составляется так, чтобы диспетчер понял, ЧТО именно
 * добавить в классификатор: без перечня недостающих изделий он шёл бы
 * по списку систем вслепую.
 */
@ExtendWith(MockitoExtension.class)
class SystemSheetMatcherTest {

    @Mock
    private ProductClassifierRepository classifierRepository;

    @InjectMocks
    private SystemSheetMatcher matcher;

    private static ProductClassifier system(
            long id, int code, String name) {

        ProductClassifier classifier = new ProductClassifier();

        classifier.setId(id);
        classifier.setCode(code);
        classifier.setProductType(ProductType.SYSTEM);
        classifier.setName(name);

        return classifier;
    }

    private static ParsedRow row(String number, String name) {

        return new ParsedRow(1, name, BigDecimal.ONE, "PCS", null, number);
    }

    private static SystemComponentProjection component(String name) {

        return new SystemComponentProjection() {
            @Override
            public Long getSensorCatalogId() {
                return 1L;
            }

            @Override
            public String getSensorCatalogName() {
                return name;
            }

            @Override
            public Long getComponentClassifierId() {
                return null;
            }

            @Override
            public Integer getComponentCode() {
                return null;
            }

            @Override
            public String getComponentName() {
                return null;
            }
        };
    }

    @Test
    @DisplayName("Система находится по написанию из листа")
    void findsSystemBySheetName() {

        when(classifierRepository.findAllByOrderByCodeAsc())
                .thenReturn(List.of(
                        system(10, 3016, "Система ДУУ10 исп 0"),
                        system(11, 3017, "Система ДУУ10 исп 1"),
                        system(12, 3018, "Система ДУУ10 исп 2")
                ));

        /*
         * В листе — «Уровнемер многоканальный ДУУ10 исполнение 2-»:
         * иное начало строки, полное слово «исполнение» и дефис на
         * конце. Сравнение обязано это выдержать.
         */
        ProductClassifier found = matcher.findSystem(
                "Уровнемер многоканальный ДУУ10 исполнение 2-, 1 шт");

        assertNotNull(found);
        assertEquals(12, found.getId());
    }

    @Test
    @DisplayName("Неоднозначное вхождение систему не определяет")
    void ambiguousNameKeepsSystemUnfound() {

        when(classifierRepository.findAllByOrderByCodeAsc())
                .thenReturn(List.of(
                        system(10, 3017,
                                "Уровнемер многоканальный ДУУ10 исп 1"),
                        system(11, 3018,
                                "Уровнемер многоканальный ДУУ10 исп 2")
                ));

        /*
         * Исполнение в листе не указано: наименование входит и в
         * «исп 1», и в «исп 2». Выбрать нельзя, и программа оставляет
         * выбор диспетчеру — иначе заказ ушёл бы на чужую систему.
         */
        assertNull(matcher.findSystem("Уровнемер многоканальный ДУУ10"));
    }

    @Test
    @DisplayName("Состав из листа отсутствует — ошибка с перечнем")
    void reportsMissingComponents() {

        ProductClassifier sys = system(12, 3018, "Система ДУУ10 исп 2");

        when(classifierRepository.findAllByOrderByCodeAsc())
                .thenReturn(List.of(sys));
        when(classifierRepository.findSystemComponents(12L))
                .thenReturn(List.of(component("Уровнемер поплавковый ДУУ10")));

        String problem = matcher.compositionProblem(List.of(
                row("1", "Уровнемер многоканальный ДУУ10 исполнение 2, 1 шт"),
                row("1.1", "Уровнемер поплавковый ДУУ10-02-0-3,55, 1 шт"),
                /* Прибора БСД5Н в составе классификатора нет. */
                row("1.2", "Блок сопряжения с датчиками БСД5Н, 1 шт")
        ));

        assertNotNull(problem);
        assertTrue(problem.contains("БСД5Н"), problem);
        assertTrue(problem.contains("3018") || problem.contains("ДУУ10"),
                problem);
    }

    @Test
    @DisplayName("Состав по классификатору — ошибки нет")
    void knownCompositionPasses() {

        ProductClassifier sys = system(12, 3018, "Система ДУУ10 исп 2");

        when(classifierRepository.findAllByOrderByCodeAsc())
                .thenReturn(List.of(sys));
        when(classifierRepository.findSystemComponents(12L))
                .thenReturn(List.of(
                        component("Уровнемер поплавковый ДУУ10"),
                        component("Блок сопряжения с датчиками БСД5Н")
                ));

        assertNull(matcher.compositionProblem(List.of(
                row("1", "Уровнемер многоканальный ДУУ10 исполнение 2, 1 шт"),
                row("1.1", "Уровнемер поплавковый ДУУ10-02-0-3,55, 1 шт"),
                row("1.2", "Блок сопряжения с датчиками БСД5Н, 1 шт")
        )));
    }

    @Test
    @DisplayName("Состав без своей строки изделия — ошибка листа")
    void componentWithoutParentReported() {

        /*
         * Классификатор не запрашивается: ошибка структуры выявляется
         * до сопоставления систем.
         */
        lenient().when(classifierRepository.findAllByOrderByCodeAsc())
                .thenReturn(List.of());

        String problem = matcher.compositionProblem(List.of(
                row("1", "Уровнемер ДУУ10, 1 шт"),
                row("3.1", "Датчик ДПУ8, 1 шт")
        ));

        assertNotNull(problem);
        assertTrue(problem.contains("3.1"), problem);
        assertTrue(problem.contains("целиком"), problem);
    }

    @Test
    @DisplayName("Строки без номера иерархию не образуют")
    void flatSheetPasses() {

        lenient().when(classifierRepository.findAllByOrderByCodeAsc())
                .thenReturn(List.of());

        assertNull(matcher.compositionProblem(List.of(
                row(null, "Датчик ДПУ8-3, 1 шт"),
                row(null, "Преобразователь ПВС8, 1 шт")
        )));
    }
}
