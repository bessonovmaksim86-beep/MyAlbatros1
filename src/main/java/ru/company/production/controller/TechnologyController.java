package ru.company.production.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import ru.company.production.repository.OperationRepository;
import ru.company.production.repository.ProductClassifierRepository;
import ru.company.production.repository.TechProcessRepository;
import ru.company.production.repository.WorkPlaceRepository;

@Controller
@RequestMapping("/technology")
@RequiredArgsConstructor
public class TechnologyController {

    private final OperationRepository operationRepository;
    private final ProductClassifierRepository classifierRepository;
    private final TechProcessRepository techProcessRepository;
    private final WorkPlaceRepository workPlaceRepository;

    @GetMapping({"", "/"})
    public String index() {
        return "redirect:/technology/general";
    }

    @GetMapping("/general")
    public String general(Model model) {
        model.addAttribute("activePage", "general");

        model.addAttribute(
                "techprocessCount",
                techProcessRepository.countByActiveTrue()
        );

        model.addAttribute(
                "operationCount",
                operationRepository.count()
        );

        model.addAttribute(
                "classifierCount",
                classifierRepository.count()
        );

        model.addAttribute(
                "workplaceCount",
                workPlaceRepository.countByActiveTrue()
        );

        /*
         * Согласований пока нет: отдельного модуля согласования
         * в системе не реализовано, поэтому счётчик остаётся нулевым.
         */
        model.addAttribute("approvalCount", 0L);

        model.addAttribute(
                "techProcesses",
                techProcessRepository.findAllDetailed()
        );

        return "technology/general";
    }

    /*
     * Поддержка старого адреса.
     * Основной адрес операций:
     * /technology/operation
     */
    @GetMapping("/operations")
    public String oldOperations() {
        return "redirect:/technology/operation";
    }
}
