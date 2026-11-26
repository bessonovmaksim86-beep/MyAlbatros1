package ru.company.production.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import ru.company.production.repository.CustomerOrderRepository;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * Номер заказа при импорте чек-листа Б24.
 *
 * Диспетчер ищет заказ номером заказчика в 1С, а не порядковым
 * номером программы: «№ 478 от 24.11.2026» обязан дать
 * «ЗП-ГГГГ-478», иначе номер заказа приходится сверять по
 * примечанию. Занятый или нечисловой номер не имеет права ломать
 * импорт — в этом случае остаётся ближайший свободный номер.
 */
@ExtendWith(MockitoExtension.class)
class CustomerOrderNumberFromSourceTest {

    @Mock
    private CustomerOrderRepository customerOrderRepository;

    private CustomerOrderService service;

    @BeforeEach
    void setUp() {
        /*
         * @InjectMocks с Lombok @RequiredArgsConstructor
         * (11 параметров) иногда не срабатывает, поэтому
         * конструируем вручную — единственный мокаемый
         * репозиторий, все остальные null.
         */
        service = new CustomerOrderService(
                customerOrderRepository,
                null, null, null, null, null, null, null, null, null, null
        );
    }

    /** Тот же год, что подставляет сервис. */
    private static String number(String tail) {
        return "ЗП-" + LocalDate.now().getYear() + "-" + tail;
    }

    @Test
    @DisplayName("номер договора 1С становится номером заказа")
    void usesSourceOrderNumber() {

        String expectedNum = number("478");
        when(customerOrderRepository.existsByNumber(eq(expectedNum)))
                .thenReturn(false);

        assertEquals(expectedNum,
                service.numberForSourceOrder("№ 478 от 24.11.2026"));
    }

    @Test
    @DisplayName("номер занят — берётся ближайший свободный")
    void fallsBackWhenNumberTaken() {

        String expectedNum = number("478");
        when(customerOrderRepository.existsByNumber(eq(expectedNum)))
                .thenReturn(true);
        when(customerOrderRepository.findAll())
                .thenReturn(List.of());

        assertEquals(number("001"), service.numberForSourceOrder("478"));
    }

    @Test
    @DisplayName("без номера договора — обычный автономер")
    void fallsBackWithoutSourceNumber() {

        when(customerOrderRepository.findAll())
                .thenReturn(List.of());

        assertEquals(number("001"), service.numberForSourceOrder(null));
        assertEquals(number("001"), service.numberForSourceOrder("   "));
    }
}
