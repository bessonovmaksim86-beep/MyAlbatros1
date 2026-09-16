package ru.company.production.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public class OperationForm {

    @NotBlank(message = "Укажите наименование операции")
    @Size(
            max = 255,
            message = "Наименование не должно превышать 255 символов"
    )
    private String operationName;

    @NotNull(message = "Выберите тип операции")
    private Long operationTypeId;

    @NotNull(message = "Выберите тип исполнителя")
    private Long executorRoleId;

    @NotNull(message = "Выберите службу")
    private Long serviceId;

    private Long organizationUnitId;

    @Size(
            max = 2000,
            message = "Примечание не должно превышать 2000 символов"
    )
    private String note;

    public String getNote() {
        return note;
    }

    public void setNote(String note) {
        this.note = note;
    }

    public String getOperationName() {
        return operationName;
    }

    public void setOperationName(String operationName) {
        this.operationName = operationName;
    }

    public Long getOperationTypeId() {
        return operationTypeId;
    }

    public void setOperationTypeId(Long operationTypeId) {
        this.operationTypeId = operationTypeId;
    }

    public Long getExecutorRoleId() {
        return executorRoleId;
    }

    public void setExecutorRoleId(Long executorRoleId) {
        this.executorRoleId = executorRoleId;
    }

    public Long getServiceId() {
        return serviceId;
    }

    public void setServiceId(Long serviceId) {
        this.serviceId = serviceId;
    }

    public Long getOrganizationUnitId() {
        return organizationUnitId;
    }

    public void setOrganizationUnitId(
            Long organizationUnitId
    ) {
        this.organizationUnitId = organizationUnitId;
    }
}