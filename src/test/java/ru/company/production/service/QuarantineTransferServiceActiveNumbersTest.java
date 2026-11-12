package ru.company.production.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import ru.company.production.dto.QuarantineFilter;
import ru.company.production.repository.ProductClassifierRepository;
import ru.company.production.repository.ProductSerialRepository;
import ru.company.production.repository.QuarantineTransferRepository;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * Граница «номер лежит в изоляторе брака сейчас».
 *
 * <p>Этим расчётом пользуются реестр наличия, комплектование подсборок
 * и привязка ячейки к ЗНП: позиция, снятая бюро с полки, не имеет права
 * числиться доступной ни в одном из перечней и ни в одном проведении.
 * Ошибка здесь незаметна по отдельному экрану и заметна всему складу
 * сразу, поэтому проверка стоит на самом расчёте, а не на потребителях.
 *
 * <p>Смысл теста в правиле вычитания: принятое складом перемещение
 * номер из изолятора убирает, но не имеет права снять незакрытую
 * строку — один номер могли браковать дважды, и тогда старая приёмка
 * не отменяет того, что позицию снова убрали с полки.
 */
@ExtendWith(MockitoExtension.class)
class QuarantineTransferServiceActiveNumbersTest {

    @Mock private QuarantineTransferRepository transferRepository;
    @Mock private InputControlService inputControlService;
    @Mock private ProductClassifierRepository classifierRepository;
    @Mock private ProductSerialRepository serialRepository;
    @Mock private SerialMovementJournalService journal;

    private QuarantineTransferService service;

    @BeforeEach
    void setUp() {
        service = new QuarantineTransferService(
                transferRepository,
                inputControlService,
                classifierRepository,
                serialRepository,
                journal
        );
    }

    private static QuarantineCellView row(String number) {
        return new QuarantineCellView(null, number);
    }

    @Test
    @DisplayName("Карточка БВК даёт номер изолятора")
    void defectGroupNumberIsQuarantined() {

        when(inputControlService.findQuarantineCells(
                any(QuarantineFilter.class)))
                .thenReturn(List.of(row("510200052")));
        when(transferRepository.findPendingFullNumbers())
                .thenReturn(List.of());
        when(transferRepository.findAcceptedFullNumbers())
                .thenReturn(List.of());

        Set<String> numbers = service.findActiveQuarantineNumbers();

        assertTrue(numbers.contains("510200052"), numbers.toString());
    }

    @Test
    @DisplayName("Принятое складом перемещение из изолятора убирается")
    void acceptedTransferLeavesQuarantine() {

        when(inputControlService.findQuarantineCells(
                any(QuarantineFilter.class)))
                .thenReturn(List.of(row("510200052")));
        when(transferRepository.findPendingFullNumbers())
                .thenReturn(List.of());
        when(transferRepository.findAcceptedFullNumbers())
                .thenReturn(List.of("510200052"));

        Set<String> numbers = service.findActiveQuarantineNumbers();

        assertFalse(numbers.contains("510200052"),
                "принятая складом ячейка снова на полке: " + numbers);
    }

    @Test
    @DisplayName("Повторная браковка не снимается старой приёмкой")
    void secondDefectOverridesOlderAcceptance() {

        /*
         * Номер приняли на склад, а затем забраковали снова: строка
         * перемещения незакрыта, и принятая строка того же номера не
         * имеет права вернуть позицию в перечень доступных.
         */
        when(inputControlService.findQuarantineCells(
                any(QuarantineFilter.class)))
                .thenReturn(List.of());
        when(transferRepository.findPendingFullNumbers())
                .thenReturn(List.of("510200054"));
        when(transferRepository.findAcceptedFullNumbers())
                .thenReturn(List.of("510200054"));

        Set<String> numbers = service.findActiveQuarantineNumbers();

        assertTrue(numbers.contains("510200054"),
                "незакрытое перемещение остаётся в изоляторе: " + numbers);
    }

    @Test
    @DisplayName("Пустые перечни не дают ни одного номера")
    void emptySourcesGiveEmptyBoundary() {

        when(inputControlService.findQuarantineCells(
                any(QuarantineFilter.class)))
                .thenReturn(List.of());
        when(transferRepository.findPendingFullNumbers())
                .thenReturn(List.of());
        when(transferRepository.findAcceptedFullNumbers())
                .thenReturn(List.of());

        assertTrue(service.findActiveQuarantineNumbers().isEmpty());
    }
}
