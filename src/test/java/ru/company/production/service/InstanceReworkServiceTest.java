package ru.company.production.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import ru.company.production.repository.ControlReturnRepository;
import ru.company.production.repository.ReworkReturnRepository;

import java.util.Collection;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Признак доработки экземпляра — единое основание блокировки.
 *
 * <p>Ошибка здесь стоит дороже ошибки любого раздела: заблокированная
 * строка лишает исполнителя кнопки, а снятая там, где препятствие ещё
 * не устранено, отдаёт на проведение недоделанную работу. Поэтому
 * проверяются оба направления — и что блокировка приходит от сигнала
 * цеха, и что она уходит, когда цех своё решение вынес.
 *
 * <p>Отдельный блок держит правило барьерных подразделений (V88,
 * позиция на доработке): сигнал, заведённый на строку склада или
 * контроля, основанием не служит, потому что такую строку цех
 * повторным проведением не закрывает. Именно этот случай запирал
 * экземпляр намертво — контроль ждал закрытия собственного сигнала,
 * а сигнал не закрывался, пока контроль заблокирован.
 */
@ExtendWith(MockitoExtension.class)
class InstanceReworkServiceTest {

    @Mock private ControlReturnRepository controlReturnRepository;
    @Mock private ReworkReturnRepository reworkReturnRepository;

    private InstanceReworkService service;

    @BeforeEach
    void setUp() {
        service = new InstanceReworkService(
                controlReturnRepository,
                reworkReturnRepository
        );
    }

    /**
     * Оба источника блокировки пусты, исключений нет — признака нет.
     *
     * Заглушки собираются здесь, а не в каждом тесте: тесты блока
     * различаются только составом возвращаемых строк.
     */
    private void noReworkAtAll() {
        when(controlReturnRepository.findRowIdsOfSignaledInstances(
                anyCollection(), anyCollection())).thenReturn(List.of());
        when(reworkReturnRepository.findRowIdsOfReworkInstances(anyCollection()))
                .thenReturn(List.of());
        when(reworkReturnRepository.findRowIdsWithOpenReturn(anyCollection()))
                .thenReturn(List.of());
    }

    /* ============================================================
       ПУСТОЙ ВХОД
       ============================================================ */

    @Nested
    @DisplayName("пустой список строк")
    class EmptyInput {

        /**
         * Экран без работ не обязан идти в базу: запрос «где id in ()»
         * ничего не нашёл бы, но обращение к базе осталось бы, а
         * список экземпляров вызывается на каждую группу.
         */
        @Test
        @DisplayName("обращений к базе нет")
        void noQueries() {

            assertTrue(service.incompleteRowIds(null).isEmpty());
            assertTrue(service.incompleteRowIds(List.of()).isEmpty());

            verifyNoInteractions(controlReturnRepository);
            verifyNoInteractions(reworkReturnRepository);
        }
    }

    /* ============================================================
       ОСНОВАНИЕ БЛОКИРОВКИ
       ============================================================ */

    @Nested
    @DisplayName("что держит блокировку")
    class Basis {

        /**
         * Открытый сигнал БТК по строке цеха того же изделия —
         * основание: переделку ещё никто не начал, и закрывать работу
         * нельзя, пока цех не отметил операции.
         */
        @Test
        @DisplayName("сигнал БТК блокирует")
        void signaledInstanceIsBlocked() {

            when(controlReturnRepository.findRowIdsOfSignaledInstances(
                    anyCollection(), anyCollection()))
                    .thenReturn(List.of(952L));
            when(reworkReturnRepository.findRowIdsOfReworkInstances(anyCollection()))
                    .thenReturn(List.of(952L));
            when(reworkReturnRepository.findRowIdsWithOpenReturn(anyCollection()))
                    .thenReturn(List.of());

            assertEquals(Set.of(952L),
                    service.incompleteRowIds(List.of(952L)));
        }

        /**
         * Возврат, заведённый самим цехом, блокирует не только свою
         * строку, а всё изделие: переделка касается монтажа и контроля
         * одного и того же номера.
         */
        @Test
        @DisplayName("открытый возврат цеха блокирует")
        void reworkInstanceIsBlocked() {

            when(controlReturnRepository.findRowIdsOfSignaledInstances(
                    anyCollection(), anyCollection())).thenReturn(List.of());
            when(reworkReturnRepository.findRowIdsOfReworkInstances(anyCollection()))
                    .thenReturn(List.of(949L, 950L, 951L));
            when(reworkReturnRepository.findRowIdsWithOpenReturn(anyCollection()))
                    .thenReturn(List.of(950L));

            /*
             * 950 — строка, отмеченная на переделку: её повторное
             * проведение и есть закрытие доработки.
             */
            assertEquals(Set.of(949L, 951L),
                    service.incompleteRowIds(List.of(949L, 950L, 951L)));
        }

        /**
         * Ни сигнала, ни возврата — проводить можно: позиция чиста,
         * и блокировка обязана отсутствовать, иначе список показывал бы
         * «ждёт» над каждой работой.
         */
        @Test
        @DisplayName("чистый экземпляр не блокируется")
        void cleanInstanceIsFree() {

            noReworkAtAll();

            assertTrue(service.incompleteRowIds(List.of(948L, 952L)).isEmpty());
        }
    }

    /* ============================================================
       БАРЬЕРНЫЕ ПОДРАЗДЕЛЕНИЯ
       ============================================================ */

    @Nested
    @DisplayName("сигналы барьерных подразделений")
    class BarrierSignals {

        /**
         * Запрос обязан уходить с границей по службам: без неё
         * складская строка и строка контроля держали бы блокировку
         * над всем изделием, и переделку нельзя было бы закрыть.
         */
        @Test
        @DisplayName("в запрос передаются службы-барьеры")
        void barrierCodesArePassedDown() {

            noReworkAtAll();

            service.incompleteRowIds(List.of(952L));

            ArgumentCaptor<Collection<String>> codes =
                    ArgumentCaptor.forClass(Collection.class);

            verify(controlReturnRepository)
                    .findRowIdsOfSignaledInstances(anyCollection(), codes.capture());

            assertTrue(codes.getValue().containsAll(
                    Set.of("SGD", "SCD", "SQ")),
                    "запрос обязан исключать склады и контроль, "
                            + "пришли: " + codes.getValue());
        }

        /**
         * Правило держится одной константой на весь модуль: блокировка
         * проведения и владение изделием обязаны понимать «барьер»
         * одинаково, иначе интерфейс разошёлся бы с сервером.
         */
        @Test
        @DisplayName("граница взята из общего правила участка")
        void boundaryIsSharedRule() {

            noReworkAtAll();

            service.incompleteRowIds(List.of(952L));

            verify(controlReturnRepository).findRowIdsOfSignaledInstances(
                    anyCollection(),
                    eq(ReworkReturnOwnerService.BARRIER_SERVICES));
        }

        /**
         * Тот самый случай из позиции 500400002: БТК вернула изделие,
         * цех переделал свои строки, сигналы остались только на складе
         * и на самой строке контроля. Открытых возвратов цеха нет —
         * значит блокировки быть не должно, и контроль проводит работу.
         */
        @Test
        @DisplayName("изделие с барьерными сигналами разблокировано")
        void onlyBarrierSignalsLeaveInstanceFree() {

            /*
             * Репозиторий уже отфильтровал барьерные строки — на вход
             * блокировки приходят только сигналы цеха, и их нет.
             */
            noReworkAtAll();

            assertTrue(service.incompleteRowIds(List.of(948L, 952L)).isEmpty());
        }
    }

    /* ============================================================
       ПАКЕТНЫЙ РАСЧЁТ
       ============================================================ */

    /**
     * Список считается одним пакетом: панель экземпляров раскрывается
     * на десятки строк, и построчный сбор превратил бы один экран в
     * сотни обращений к базе.
     */
    @Test
    @DisplayName("строки уходят в репозиторий одним пакетом")
    void rowsGoInOneBatch() {

        noReworkAtAll();

        service.incompleteRowIds(List.of(948L, 949L, 952L, 948L));

        ArgumentCaptor<Collection<Long>> ids =
                ArgumentCaptor.forClass(Collection.class);

        verify(controlReturnRepository)
                .findRowIdsOfSignaledInstances(ids.capture(), anyCollection());

        /*
         * Дубликаты снимаются до запроса: одна строка могла попасть в
         * выборку из двух групп списка, и повтор ускорил бы запрос без
         * всякой пользы.
         */
        assertEquals(List.of(948L, 949L, 952L), List.copyOf(ids.getValue()));

        verify(reworkReturnRepository)
                .findRowIdsOfReworkInstances(anyCollection());
        verify(reworkReturnRepository)
                .findRowIdsWithOpenReturn(anyCollection());
        verify(controlReturnRepository, never())
                .findOpenByRowIds(anyCollection());
    }
}
