package ru.company.production.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public class WorkPlaceForm {

    @NotBlank(message = "Укажите код рабочего места")
    @Size(
            max = 50,
            message = "Код не должен превышать 50 символов"
    )
    private String code;

    @NotBlank(message = "Укажите наименование рабочего места")
    @Size(
            max = 255,
            message = "Наименование не должно превышать 255 символов"
    )
    private String name;

    @NotNull(message = "Выберите тип рабочего места")
    private Long workPlaceTypeId;

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

    public String getCode() {
        return code;
    }

    public void setCode(String code) {
        this.code = code;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public Long getWorkPlaceTypeId() {
        return workPlaceTypeId;
    }

    public void setWorkPlaceTypeId(Long workPlaceTypeId) {
        this.workPlaceTypeId = workPlaceTypeId;
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

    public String getNote() {
        return note;
    }

    public void setNote(String note) {
        this.note = note;
    }
}
