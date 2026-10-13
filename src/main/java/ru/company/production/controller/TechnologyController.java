package ru.company.production.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import ru.company.production.dto.SearchResultView;
import ru.company.production.repository.OperationRepository;
import ru.company.production.repository.ProductClassifierRepository;
import ru.company.production.repository.TechProcessRepository;
import ru.company.production.repository.WorkPlaceRepository;
import ru.company.production.service.TechnologySearchService;

import java.util.List;

@Controller
@RequestMapping("/technology")
@RequiredArgsConstructor
public class TechnologyController {

    private final OperationRepository operationRepository;
    private final ProductClassifierRepository classifierRepository;
    private final TechProcessRepository techProcessRepository;
    private final WorkPlaceRepository workPlaceRepository;
    private final TechnologySearchService technologySearchService;

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

    /*
     * API глобального поиска.
     *
     * Возвращает JSON-массив SearchResultView. Заголовки групп
     * (category = "..._header") содержат пустые title/subtitle/url,
     * клиент использует их только для рисования разделителей.
     *
     * При пустом запросе отдаёт пустой список — никаких «показать
     * всё» не нужно, результаты отфильтровываются на клиенте.
     */
    @GetMapping("/search")
    public ResponseEntity<List<SearchResultView>> search(
            @RequestParam(name = "q", defaultValue = "") String q
    ) {
        if (q == null || q.isBlank()) {
            return ResponseEntity.ok(List.of());
        }

        return ResponseEntity.ok(
                technologySearchService.search(q)
        );
    }
}