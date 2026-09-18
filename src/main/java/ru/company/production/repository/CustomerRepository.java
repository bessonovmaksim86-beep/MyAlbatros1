package ru.company.production.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;
import ru.company.production.entity.Customer;

import java.util.List;
import java.util.Optional;

@Repository
public interface CustomerRepository
        extends JpaRepository<Customer, Long> {

    List<Customer> findByActiveTrueOrderByNameAsc();

    List<Customer> findAllByOrderByCodeAsc();

    List<Customer> findAllByOrderByNameAsc();

    /* Все коды: префикс отбирается на стороне. */
    @Query("select customer.code from Customer customer")
    List<String> findAllCodes();

    Optional<Customer> findByCode(String code);

    Optional<Customer> findByNameIgnoreCase(String name);

    boolean existsByCode(String code);

    boolean existsByCodeAndIdNot(String code, Long id);

    boolean existsByNameIgnoreCase(String name);

    boolean existsByNameIgnoreCaseAndIdNot(String name, Long id);

    long countByActiveTrue();
}
