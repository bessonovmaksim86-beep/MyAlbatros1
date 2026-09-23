package ru.company.production.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import ru.company.production.service.CellReceivingService;

import java.util.List;

/**
 * Склад полуфабрикатов: главная страница модуля.
 *
 * Страница показывает только то, чем склад занят: незакрытые приходы
 * и сколько ячеек уже стоит на складе. Заказов и техпроцессов здесь
 * нет — у склада одна задача, и разворот модуля ей соответствует.
 */
@Controller
@RequestMapping("/warehouse")
@RequiredArgsConstructor
public class WarehouseController {

    private final CellReceivingService receivingService;

    @GetMapping({"", "/", "/general", "/general/"})
    public String general(Model model) {
        List<?> batches = receivingService.findAll();

        model.addAttribute("activePage", "general");
        model.addAttribute("batches", batches);
        model.addAttribute("batchTotal", batches.size());
        model.addAttribute(
                "pendingCount",
                receivingService.countPending()
        );
        model.addAttribute(
                "receivedCells",
                receivingService.countReceivedCells()
        );

        return "warehouse/general";
    }
}