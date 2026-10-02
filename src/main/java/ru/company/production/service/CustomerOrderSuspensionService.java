package ru.company.production.service;

import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.company.production.entity.CustomerOrder;
import ru.company.production.entity.CustomerOrderStatus;
import ru.company.production.entity.OrderScheduleRow;
import ru.company.production.repository.CustomerOrderRepository;
import ru.company.production.repository.OrderScheduleRowRepository;

import java.time.LocalDateTime;

/**
 * Приостановка и возобновление заказа покупателя (миграция V46).
 *
 * ПРИОСТАНОВКА — не удаление. Заказ остаётся действующим
 * (active = true): он по-прежнему виден во всех списках и счётчиках,
 * но получает статус SUSPENDED и «замораживается»: до возобновления
 * по нему нельзя проводить и назначать работы цеха. Для этого здесь
 * же живут проверки {@link #assertOrderOperable} и
 * {@link #assertRowOperable}, которые вызывают точки проведения
 * операций (проведение экземпляра, назначение исполнителя).
 *
 * КУДА ВОЗВРАЩАТЬ. Приостановка запоминает статус, в котором заказ
 * стоял (suspendedFromStatus), и возобновление возвращает именно в
 * него: остановленный «в работе» едет обратно «в работу», а не
 * «новый». Если заказ был взят в форму сразу приостановленным и
 * прошлый статус неизвестен, возобновление отдаёт его в «Новый» —
 * нейтральное состояние, из которого диспетчер снова запланирует
 * заказ осознанно.
 */
@Service
@RequiredArgsConstructor
public class CustomerOrderSuspensionService {

    private final CustomerOrderRepository customerOrderRepository;
    private final OrderScheduleRowRepository orderScheduleRowRepository;

    /**
     * Приостанавливает заказ: статус → SUSPENDED, прежнее состояние
     * сохраняется в suspendedFromStatus, причина и время — рядом.
     *
     * Закрытые (исполнен, отменён, удалён) и уже приостановленный
     * заказ остановке не подлежат — иначе возобновление вернуло бы
     * заказ в жизненный цикл, из которого он уже вышел.
     */
    @Transactional
    public CustomerOrder suspend(Long id, String reason) {

        CustomerOrder order = customerOrderRepository
                .findHeaderById(id)
                .orElseThrow(() -> new EntityNotFoundException(
                        "Заказ покупателя не найден"
                ));

        if (!order.isActive()) {
            throw new IllegalStateException(
                    "Недействующий заказ «" + order.getNumber()
                            + "» нельзя приостановить"
            );
        }

        CustomerOrderStatus status = order.getStatus();

        if (status == CustomerOrderStatus.SUSPENDED) {
            throw new IllegalStateException(
                    "Заказ «" + order.getNumber()
                            + "» уже приостановлен"
            );
        }

        if (status == CustomerOrderStatus.DONE
                || status == CustomerOrderStatus.CANCELLED
                || status == CustomerOrderStatus.DELETED) {
            throw new IllegalStateException(
                    "Закрытый заказ «" + order.getNumber()
                            + "» нельзя приостановить"
            );
        }

        order.setSuspendedFromStatus(status);
        order.setSuspendedReason(blankToNull(reason));
        order.setSuspendedAt(LocalDateTime.now());
        order.setStatus(CustomerOrderStatus.SUSPENDED);

        return customerOrderRepository.save(order);
    }

    /**
     * Возобновляет заказ: возвращает его в статус, в котором тот стоял
     * до остановки, и стирает признаки приостановки.
     */
    @Transactional
    public CustomerOrder resume(Long id) {

        CustomerOrder order = customerOrderRepository
                .findHeaderById(id)
                .orElseThrow(() -> new EntityNotFoundException(
                        "Заказ покупателя не найден"
                ));

        if (order.getStatus() != CustomerOrderStatus.SUSPENDED) {
            throw new IllegalStateException(
                    "Заказ «" + order.getNumber()
                            + "» не приостановлен, возобновлять нечего"
            );
        }

        CustomerOrderStatus restored =
                order.getSuspendedFromStatus() == null
                        ? CustomerOrderStatus.NEW
                        : order.getSuspendedFromStatus();

        order.setStatus(restored);
        order.setSuspendedFromStatus(null);
        order.setSuspendedReason(null);
        order.setSuspendedAt(null);

        return customerOrderRepository.save(order);
    }

    /**
     * «Заказ приостановлен — операции недоступны»: точка проведения
     * операций по заказу (назначение исполнителя, проведение
     * экземпляра) обязана вызвать её до изменения данных.
     *
     * Отсутствующий заказ не блокируется: у вызывающей операции своя
     * проверка существования, а здесь важно поймать именно приостановку,
     * а не подменять чужую валидацию.
     */
    @Transactional(readOnly = true)
    public void assertOrderOperable(Long orderId) {
        if (orderId == null) {
            return;
        }

        checkSuspended(customerOrderRepository
                .findHeaderById(orderId)
                .orElse(null));
    }

    /**
     * То же, но по строке графика: у исполнителя в «Мои операции» есть
     * только идентификатор строки, а заказ вытаскивается из неё.
     */
    @Transactional(readOnly = true)
    public void assertRowOperable(Long rowId) {
        if (rowId == null) {
            return;
        }

        OrderScheduleRow row = orderScheduleRowRepository
                .findById(rowId)
                .orElse(null);

        if (row == null) {
            return;
        }

        checkSuspended(row.getCustomerOrder());
    }

    /*
     * Единственная формулировка отказа: и назначение, и проведение
     * обязаны называть заказ и причину, чтобы исполнитель понимал,
     * почему работа «не проводится», а не получал безличную ошибку.
     */
    private void checkSuspended(CustomerOrder order) {
        if (order == null
                || order.getStatus() != CustomerOrderStatus.SUSPENDED) {
            return;
        }

        String reason = order.getSuspendedReason();

        throw new IllegalStateException(
                "Заказ «" + order.getNumber() + "» приостановлен"
                        + (reason == null || reason.isBlank()
                                ? "" : ": " + reason)
                        + ". Операции по заказу недоступны до возобновления."
        );
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
