package ru.company.production.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import ru.company.production.entity.ProductionOrder;
import ru.company.production.service.RepairKittingService;

import java.util.List;
import java.util.Set;

/**
 * Бюро технического контроля: собственные реестры бюро.
 *
 * Адрес отдельный от {@code /input-control}, хотя оба бюро принадлежат
 * службе качества: реестры партий — входной контроль, изолятор и
 * возврат поставщику — закрываются ролью BVK_EXECUTOR, и общая ссылка
 * повела бы контролёра БТК в 403. У бюро технического контроля свой
 * раздел пока один — комплектование ремонтных ЗНП; остальная его работа
 * (операции, распределение, отбраковка) живёт в цеховом модуле и в
 * «Мои операции».
 *
 * Боковое меню страница рисует цеховое (workshop/fragments/layout) —
 * тот же приём, что у раздела «Отбраковка»: у БТК нет своих реестров
 * партий, и панель бюро показала бы ему пункты, закрытые перед его
 * ролью. Подписи и счётчики этого меню доносит
 * {@link InputControlViewAdvice}, подключённый к разделам обоих бюро.
 *
 * Проводка со страницы не делается: здесь только перечень заказов, а
 * отмеченные номера проводят в форме комплектования
 * (/my-operations/repair-kitting/{id}) — там же, где исполнитель бюро
 * видит свою очередь.
 */
@Controller
@RequestMapping("/technical-control")
@RequiredArgsConstructor
public class TechnicalControlController {

    private final RepairKittingService repairKittingService;

    /**
     * Перечень ЗНП на ремонт ячеек и подсборок, готовых к
     * комплектованию силами бюро технического контроля.
     *
     * Заказы заводит и ведёт диспетчерская, поэтому страница только
     * показывает: состав и статус ремонтного ЗНП из бюро менять нельзя.
     * Отбор держится комплектовочной операции БТК в маршруте заказа —
     * тот же признак, что у раздела БВК, но с другим подразделением,
     * поэтому бюро не видит чужие заказы.
     */
    @GetMapping({"/repair-kitting", "/repair-kitting/"})
    public String repairKitting(Model model) {

        List<ProductionOrder> orders =
                repairKittingService.findBtkRepairKittingOrders();
        Set<Long> completedIds = repairKittingService.completedOrderIds(orders);

        model.addAttribute("activePage", "btk-repair-kitting");
        model.addAttribute("orders", orders);
        model.addAttribute("completedOrderIds", completedIds);

        return "technical-control/repair-kitting/index";
    }
}
