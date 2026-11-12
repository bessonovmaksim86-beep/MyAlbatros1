package ru.company.production.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import ru.company.production.dto.StartSerialRowView;
import ru.company.production.service.StartSerialService;

import java.util.List;

/**
 * Раздел администратора «Стартовые номера серий».
 *
 * Серийные номера в пред-БД уже были выданы, и новая база не вправе их
 * задваивать. Ряд привязан к классификатору (у датчиков — к роду, V27),
 * поэтому стартовый номер задают вручную по каждому изделию; без задания
 * ряд начинается с единицы.
 *
 * Страница одна и без предпросмотра: менялось бы одно число, а не состав
 * связанной записи, и откатить его можно той же формой, поставив
 * прежнее значение.
 */
@Controller
@RequestMapping("/admin/start-serials")
@RequiredArgsConstructor
public class AdminStartSerialsController {

    private final StartSerialService startSerialService;

    @GetMapping({"", "/"})
    public String index(Model model) {
        model.addAttribute("activePage", "startSerials");

        List<StartSerialRowView> rows = startSerialService.findRows();

        model.addAttribute("rows", rows);
        model.addAttribute("totalCount", rows.size());

        return "admin/start-serials";
    }

    /**
     * Сохранение стартового номера одного классификатора.
     *
     * Значение принимается строкой: пустое поле формы обязано читаться
     * как «не задан» (ряд снова стартует с единицы), а примитив или
     * {@code Integer} отклонили бы запрос на конвертации до обращения к
     * сервису.
     */
    @PostMapping("/save")
    public String save(
            @RequestParam Long classifierId,
            @RequestParam(required = false) String startSerial,
            RedirectAttributes redirectAttributes
    ) {
        try {
            startSerialService.save(classifierId, parse(startSerial));

            redirectAttributes.addFlashAttribute(
                    "successMessage",
                    "Стартовый номер сохранён"
            );
        } catch (
                IllegalArgumentException
                | IllegalStateException exception
        ) {
            redirectAttributes.addFlashAttribute(
                    "errorMessage",
                    exception.getMessage()
            );
        }

        return "redirect:/admin/start-serials";
    }

    /**
     * Разбор поля формы: пустое значение и ноль означают «не задан».
     *
     * Нечисленное значение отклоняется с текстом, а не молча приводится к
     * нулю: администратор обязан увидеть, что ввод не принят, иначе он
     * посчитал бы старт сохранённым.
     */
    private static Integer parse(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }

        try {
            return Integer.valueOf(value.trim());
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException(
                    "Стартовый номер «" + value + "» не является числом"
            );
        }
    }
}
