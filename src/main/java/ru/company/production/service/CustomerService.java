package ru.company.production.service;

import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.company.production.dto.CustomerForm;
import ru.company.production.entity.Customer;
import ru.company.production.repository.CustomerRepository;

import java.util.ArrayList;
import java.util.List;

/**
 * Справочник заказчиков (покупателей) диспетчерского отдела.
 *
 * Заказчик заводится одним наименованием: код вида ЗК-NNN
 * генерируется автоматически, остальные реквизиты справочника
 * остаются пустыми до их позднего заполнения.
 */
@Service
@RequiredArgsConstructor
public class CustomerService {

    private final CustomerRepository customerRepository;
    private final CustomerCodeGenerator codeGenerator;

    /**
     * Все заказчики справочника — действующие и архивные.
     */
    @Transactional(readOnly = true)
    public List<Customer> findAll() {
        return customerRepository.findAllByOrderByNameAsc();
    }

    @Transactional(readOnly = true)
    public long countActive() {
        return customerRepository.countByActiveTrue();
    }

    /**
     * Ближайший свободный код заказчика вида ЗК-NNN.
     */
    @Transactional(readOnly = true)
    public String nextCode() {
        return codeGenerator.next(customerRepository.findAllCodes());
    }

    /**
     * Добавляет заказчика по одному наименованию.
     *
     * Наименование уникально в справочнике, поэтому повтор
     * отбивается до записи в таблицу — иначе СУБД отвечает
     * нарушением ограничения uk_customer_name.
     */
    @Transactional
    public Customer create(CustomerForm form) {
        String name = form.getName() == null
                ? ""
                : form.getName().trim();

        if (name.isBlank()) {
            throw new IllegalArgumentException(
                    "Укажите наименование заказчика"
            );
        }

        if (customerRepository.existsByNameIgnoreCase(name)) {
            throw new IllegalArgumentException(
                    "Заказчик «" + name + "» уже существует"
            );
        }

        Customer customer = new Customer();

        customer.setName(name);
        customer.setCode(nextFreeCode());
        customer.setActive(true);

        try {
            return customerRepository.saveAndFlush(customer);
        } catch (DataIntegrityViolationException exception) {
            /*
             * Гонка двух одноимённых записей: ограничение уникальности
             * переводится в понятное сообщение вместо служебной ошибки.
             */
            throw new IllegalArgumentException(
                    "Заказчик «" + name + "» уже существует",
                    exception
            );
        }
    }

    /**
     * Свободный код: расчёт повторяется до первого незанятого значения —
     * часть кодов могла быть заведена вручную и не участвовать в генерации.
     * Занятые значения дописываются в локальную копию списка,
     * чтобы повторный расчёт не предложил то же значение.
     */
    private String nextFreeCode() {
        List<String> codes =
                new ArrayList<>(customerRepository.findAllCodes());

        String code = codeGenerator.next(codes);

        while (codes.contains(code)) {
            codes.add(code);
            code = codeGenerator.next(codes);
        }

        return code;
    }
}
