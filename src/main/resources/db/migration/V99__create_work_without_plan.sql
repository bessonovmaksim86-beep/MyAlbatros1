-- V89__create_work_without_plan.sql

CREATE TABLE work_without_plan (
    id           BIGINT AUTO_INCREMENT PRIMARY KEY,
    employee_id  BIGINT       NOT NULL,
    operation_name VARCHAR(255) NOT NULL,
    norm_hours   DECIMAL(10,3) NOT NULL,
    quantity     INT          NOT NULL,
    work_date    DATE         NOT NULL,
    created_at   TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by   BIGINT,

    CONSTRAINT fk_wwp_employee FOREIGN KEY (employee_id) REFERENCES app_users(id),
    CONSTRAINT fk_wwp_created_by FOREIGN KEY (created_by) REFERENCES app_users(id)
);
