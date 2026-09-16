package ru.company.production.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import ru.company.production.entity.UserRole;

import java.util.List;

public interface UserRoleRepository
        extends JpaRepository<UserRole, Long> {

    List<UserRole> findAllByOrderByDisplayNameAsc();
}