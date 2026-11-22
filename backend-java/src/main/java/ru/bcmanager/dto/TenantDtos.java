package ru.bcmanager.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.LocalDateTime;

/**
 * DTO арендаторов (Plan.md, раздел 5.3).
 */
public final class TenantDtos {

    private TenantDtos() {
    }

    /**
     * Запрос регистрации/редактирования арендатора.
     *
     * @param companyName   наименование организации / ИП
     * @param inn           ИНН: 10 или 12 цифр
     * @param contactPerson контактное лицо
     * @param phone         телефон
     * @param email         электронная почта
     */
    public record TenantRequest(
            @NotBlank(message = "Наименование обязательно")
            @Size(max = 150, message = "Наименование не длиннее 150 символов")
            String companyName,

            @NotBlank(message = "ИНН обязателен")
            @Pattern(regexp = "\\d{10}|\\d{12}", message = "ИНН должен состоять из 10 или 12 цифр")
            String inn,

            @NotBlank(message = "Контактное лицо обязательно")
            @Size(max = 120, message = "Контактное лицо не длиннее 120 символов")
            String contactPerson,

            @NotBlank(message = "Телефон обязателен")
            @Size(max = 30, message = "Телефон не длиннее 30 символов")
            String phone,

            @NotBlank(message = "Email обязателен")
            @Email(message = "Некорректный формат email")
            @Size(max = 120, message = "Email не длиннее 120 символов")
            String email) {
    }

    /**
     * Ответ с данными арендатора.
     *
     * @param id            идентификатор
     * @param companyName   наименование
     * @param inn           ИНН
     * @param contactPerson контактное лицо
     * @param phone         телефон
     * @param email         почта
     * @param active        активен ли контрагент
     * @param createdAt     дата регистрации
     */
    public record TenantResponse(
            Long id,
            String companyName,
            String inn,
            String contactPerson,
            String phone,
            String email,
            boolean active,
            LocalDateTime createdAt) {
    }

    /**
     * Карточка арендатора вместе с его договорами.
     *
     * @param tenant    данные арендатора
     * @param contracts договоры арендатора
     */
    public record TenantDetailsResponse(TenantResponse tenant, java.util.List<ContractDtos.ContractSummary> contracts) {
    }
}
