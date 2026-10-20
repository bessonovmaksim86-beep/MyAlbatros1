package ru.company.production.controller;

import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import ru.company.production.dto.SearchSummaryView;
import ru.company.production.service.SearchSummaryService;
import ru.company.production.service.TechProcessService;

/**
 * Общая сводка по найденному поиском объекту.
 *
 * Отдельный контроллер, а не раздел: сводка надмодульная — она
 * показывает заказ, изделие, операцию или рабочее место в едином
 * вне-ролевом интерфейсе независимо от того, кто и откуда открыл
 * поиск. Доступ — любой аутентифицированный (общее правило
 * anyRequest().authenticated() в SecurityConfig): показывать
 * нейтральную сводку по уже найденному объекту безопаснее, чем
 * забрасывать пользователя в чужой раздел.
 */
@Controller
@RequiredArgsConstructor
public class SearchSummaryController {

    private final SearchSummaryService searchSummaryService;
    private final TechProcessService techProcessService;

    /*
     * Страница поиска в общем интерфейсе.
     *
     * Сюда ведут кнопки «Искать ещё» и «Перейти к поиску» со сводки:
     * поле то же, что в шапке отдела, но выдача остаётся в едином
     * интерфейсе и никуда не уводит — строка ведёт обратно в сводку.
     */
    @GetMapping("/search")
    public String search() {
        return "search/page";
    }

    @GetMapping("/search/object/{category}/{id}")
    public String object(
            @PathVariable String category,
            @PathVariable Long id,
            Model model
    ) {
        SearchSummaryView summary = searchSummaryService.build(category, id);

        model.addAttribute("summary", summary);

        return "search/object";
    }

    /*
     * Визуализация графа маршрута в нейтральном интерфейсе сводки.
     *
     * Кнопка «Граф зависимостей» из сводки вела бы в раздел технологов,
     * и пользователь увидел бы чужое боковое меню — переход между
     * ролями. Здесь тот же граф рисуется во вне-ролевом каркасе
     * поиска, доступном любому аутентифицированному.
     */
    @GetMapping("/search/graph/{id}")
    public String graph(
            @PathVariable Long id,
            Model model,
            RedirectAttributes redirectAttributes
    ) {
        try {
            model.addAttribute(
                    "graph",
                    techProcessService.buildGraph(id)
            );

            return "search/graph";
        } catch (
                EntityNotFoundException |
                IllegalArgumentException |
                IllegalStateException exception
        ) {
            redirectAttributes.addFlashAttribute(
                    "errorMessage",
                    exception.getMessage()
            );

            return "redirect:/search";
        }
    }
}
