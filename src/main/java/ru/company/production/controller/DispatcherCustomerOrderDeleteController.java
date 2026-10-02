package ru.company.production.controller;

import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import ru.company.production.entity.CustomerOrder;
import ru.company.production.service.CustomerOrderService;

/**
 * Диспетчерский отдел: удаление заказа покупателя.
 *
 * Контроллер выделен отдельно от {@code DispatcherController} по той же
 * причине, что {@code DispatcherPriorityController}: раздел диспетчера
 * разросся до нескольких тысяч строк, и держать в одном классе ещё и
 * снятие заказа с работы означало бы искать кнопку удаления по всему
 * модулю.
 *
 * Общий префикс {@code /dispatcher/customer-order} при этом совпадает с
 * путями основного контроллера — Spring сопоставляет обработчики по
 * полному пути, а не по классу, поэтому адрес остаётся привычным для
 * ссылок в шаблонах и ни с одним из существующих путей не спорит
 * (под-маршрута {@code /{id}/delete} в {@code DispatcherController}
 * нет, а {@code /customer-order/{id}/edit} и {@code /plan} остаются за
 * ним).
 */
@Controller
@RequestMapping("/dispatcher/customer-order")
@RequiredArgsConstructor
public class DispatcherCustomerOrderDeleteController {

    private final CustomerOrderService customerOrderService;

    /**
     * Снятие заказа покупателя с работы.
     *
     * Заказ не стирается, а помечается удалённым
     * ({@link CustomerOrderService#delete}): на него ссылаются выпуск,
     * график цеха и реестр серийников. Вместе с заказом сервис снимает
     * всё запланированное по нему — строки графика обеих стадий и
     * плановые цеховые операции изделий, иначе цех видел бы работу без
     * основания.
     *
     * Заказ с проведёнными операциями сервис не удаляет: отказ приходит
     * текстом исключения и возвращается флеш-сообщением, поэтому
     * диспетчер видит причину («по позициям отмечено готовое
     * количество», «есть заказ на производство», «проведены цеховые
     * операции»), а не безличный отказ.
     *
     * Ошибка возвращает в карточку заказа — там же, где кнопка: из
     * списка причина выглядела бы потерянной, потому что заказ после
     * неудачи из списка не исчезает и висел бы без объяснений.
     */
    @PostMapping("/{id}/delete")
    public String delete(
            @PathVariable Long id,
            RedirectAttributes redirectAttributes
    ) {
        CustomerOrder deleted;

        try {
            deleted = customerOrderService.delete(id);
        } catch (
                EntityNotFoundException
                | IllegalArgumentException
                | IllegalStateException exception
        ) {
            redirectAttributes.addFlashAttribute(
                    "errorMessage",
                    exception.getMessage()
            );

            return "redirect:/dispatcher/customer-order/" + id;
        }

        /*
         * О снятии плана говорится прямо: он исчезает из графика цеха и
         * из очереди распределения работ, и молча пропавшие строки
         * мастер принял бы за сбой, а не за следствие удаления заказа.
         */
        redirectAttributes.addFlashAttribute(
                "successMessage",
                "Заказ покупателя «" + deleted.getNumber()
                        + "» удалён, его плановые операции сняты"
        );

        return "redirect:/dispatcher/customer-order";
    }
}