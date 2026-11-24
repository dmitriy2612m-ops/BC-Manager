package ru.bcmanager.controller;

import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.notNullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import ru.bcmanager.domain.Tenant;
import ru.bcmanager.domain.User;
import ru.bcmanager.domain.enums.PremisesStatus;
import ru.bcmanager.domain.enums.Role;
import ru.bcmanager.domain.premises.OfficeSpace;
import ru.bcmanager.repository.LeaseContractRepository;
import ru.bcmanager.repository.MaintenanceRequestRepository;
import ru.bcmanager.repository.PaymentRepository;
import ru.bcmanager.repository.PremisesRepository;
import ru.bcmanager.repository.TenantRepository;
import ru.bcmanager.repository.UserRepository;

/**
 * Интеграционные тесты регистрации по логину и паролю и изоляции персональных
 * рабочих пространств пользователей (без {@code @Transactional}, чтобы проверить
 * работу с {@code open-in-view: false}).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class UserRegistrationWorkspaceTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private TenantRepository tenantRepository;

    @Autowired
    private PremisesRepository premisesRepository;

    @Autowired
    private LeaseContractRepository contractRepository;

    @Autowired
    private PaymentRepository paymentRepository;

    @Autowired
    private MaintenanceRequestRepository maintenanceRepository;

    private Tenant sharedTenant;

    private OfficeSpace sharedPremises;

    @BeforeEach
    void setUp() {
        cleanUp();
        userRepository.save(new User(
                "admin", passwordEncoder.encode("secret123"), "Администратор", Role.ADMIN));
        sharedTenant = tenantRepository.save(new Tenant(
                "ООО Демо Компания", "7701234567", "Смирнов А.П.", "+7 (495) 111-22-33", "demo@example.com"));
        sharedPremises = premisesRepository.save(new OfficeSpace(
                "A-101", "Корпус А", 1, new BigDecimal("50.00"), new BigDecimal("1200.00"),
                PremisesStatus.AVAILABLE, true));
    }

    @AfterEach
    void cleanUp() {
        paymentRepository.deleteAll();
        maintenanceRepository.deleteAll();
        contractRepository.deleteAll();
        premisesRepository.deleteAll();
        tenantRepository.deleteAll();
        userRepository.deleteAll();
    }

    @Test
    @DisplayName("POST /auth/register: создаёт пустой аккаунт, изолированный от демо-данных")
    void registeredUserStartsWithEmptyWorkspace() throws Exception {
        String token = registerAndGetToken("user_one", "pass1234");

        mockMvc.perform(get("/api/v1/premises").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(0)));

        mockMvc.perform(get("/api/v1/tenants").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(0)));

        mockMvc.perform(get("/api/v1/contracts").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(0)));

        mockMvc.perform(get("/api/v1/payments").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(0)));

        mockMvc.perform(get("/api/v1/maintenance").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(0)));

        mockMvc.perform(get("/api/v1/analytics/summary").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalAreaSqm").value(0))
                .andExpect(jsonPath("$.mrr").value(0));
    }

    @Test
    @DisplayName("Персональные помещения, арендаторы и договоры сохраняются при повторном входе")
    void registeredUserDataPersistsAcrossLogins() throws Exception {
        String registerToken = registerAndGetToken("owner_alpha", "alpha123");

        // Создаём помещение с тем же кодом A-101, что и в общем демо-пространстве.
        MvcResult premisesRes = mockMvc.perform(post("/api/v1/premises")
                        .header("Authorization", "Bearer " + registerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "code":"A-101",
                                  "building":"Мой БЦ",
                                  "floor":2,
                                  "premisesType":"OFFICE",
                                  "areaSqm":60.00,
                                  "baseRatePerSqm":1500.00,
                                  "status":"AVAILABLE",
                                  "hasFurniture":true,
                                  "hasShowcase":false,
                                  "covered":true,
                                  "description":"Личный офис"
                                }
                                """))
                .andExpect(status().isCreated())
                .andReturn();
        long premisesId = objectMapper.readTree(premisesRes.getResponse().getContentAsString())
                .get("id").asLong();

        // Создаём арендатора / компанию с тем же ИНН, что и в общем демо-пространстве.
        MvcResult tenantRes = mockMvc.perform(post("/api/v1/tenants")
                        .header("Authorization", "Bearer " + registerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "companyName":"ООО Моя Компания",
                                  "inn":"7701234567",
                                  "contactPerson":"Иванова Ольга",
                                  "phone":"+7 (999) 123-45-67",
                                  "email":"olga@mycompany.ru"
                                }
                                """))
                .andExpect(status().isCreated())
                .andReturn();
        long tenantId = objectMapper.readTree(tenantRes.getResponse().getContentAsString())
                .get("id").asLong();

        // Оформляем договор аренды.
        mockMvc.perform(post("/api/v1/contracts")
                        .header("Authorization", "Bearer " + registerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "contractNumber":"MY-2026-01",
                                  "tenantId":%d,
                                  "premisesId":%d,
                                  "startDate":"2026-05-01",
                                  "endDate":"2027-04-30",
                                  "activate":true
                                }
                                """.formatted(tenantId, premisesId)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.contractNumber").value("MY-2026-01"));

        // Повторный вход с теми же логином и паролем.
        String reloginToken = loginAndGetToken("owner_alpha", "alpha123");

        mockMvc.perform(get("/api/v1/premises").header("Authorization", "Bearer " + reloginToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].building").value("Мой БЦ"));

        mockMvc.perform(get("/api/v1/tenants").header("Authorization", "Bearer " + reloginToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].companyName").value("ООО Моя Компания"));

        mockMvc.perform(get("/api/v1/tenants/" + tenantId).header("Authorization", "Bearer " + reloginToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tenant.companyName").value("ООО Моя Компания"))
                .andExpect(jsonPath("$.contracts", hasSize(1)))
                .andExpect(jsonPath("$.contracts[0].contractNumber").value("MY-2026-01"));

        mockMvc.perform(get("/api/v1/contracts").header("Authorization", "Bearer " + reloginToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)));

        mockMvc.perform(get("/api/v1/payments").header("Authorization", "Bearer " + reloginToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(12)));

        // Проверяем, что демо-пользователь admin по-прежнему видит только свои данные.
        String adminToken = loginAndGetToken("admin", "secret123");
        mockMvc.perform(get("/api/v1/tenants").header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].companyName").value("ООО Демо Компания"));
    }

    @Test
    @DisplayName("Изоляция между пользователями и запрет повторной регистрации логина")
    void usersAreIsolatedFromEachOtherAndDuplicateLoginFails() throws Exception {
        String firstToken = registerAndGetToken("first_user", "pass1234");
        MvcResult createdTenant = mockMvc.perform(post("/api/v1/tenants")
                        .header("Authorization", "Bearer " + firstToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "companyName":"ООО Первый",
                                  "inn":"7709998887",
                                  "contactPerson":"Петров И.",
                                  "phone":"+7 (495) 222-33-44",
                                  "email":"first@example.com"
                                }
                                """))
                .andExpect(status().isCreated())
                .andReturn();
        long firstTenantId = objectMapper.readTree(createdTenant.getResponse().getContentAsString())
                .get("id").asLong();

        // Повторная регистрация с тем же логином возвращает 409 Conflict.
        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"FIRST_USER","password":"otherpassword"}
                                """))
                .andExpect(status().isConflict());

        // Второй пользователь не видит данные первого и демо-данные.
        String secondToken = registerAndGetToken("second_user", "pass5678");
        mockMvc.perform(get("/api/v1/tenants").header("Authorization", "Bearer " + secondToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(0)));

        mockMvc.perform(get("/api/v1/tenants/" + firstTenantId).header("Authorization", "Bearer " + secondToken))
                .andExpect(status().isNotFound());

        mockMvc.perform(get("/api/v1/tenants/" + sharedTenant.getId()).header("Authorization", "Bearer " + secondToken))
                .andExpect(status().isNotFound());

        mockMvc.perform(get("/api/v1/premises/" + sharedPremises.getId()).header("Authorization", "Bearer " + secondToken))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("POST /auth/change-password: меняет пароль, отклоняет старый и валидирует ошибки")
    void changePasswordUpdatesCredentialsAndValidatesErrors() throws Exception {
        String token = registerAndGetToken("pwd_user", "oldPass123");

        // Неверный текущий пароль → 400 Bad Request.
        mockMvc.perform(post("/api/v1/auth/change-password")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"currentPassword":"wrongPassword","newPassword":"newPass456"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Неверный текущий пароль"));

        // Новый пароль совпадает со старым → 400 Bad Request.
        mockMvc.perform(post("/api/v1/auth/change-password")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"currentPassword":"oldPass123","newPassword":"oldPass123"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Новый пароль должен отличаться от текущего"));

        // Без авторизации → 401 Unauthorized.
        mockMvc.perform(post("/api/v1/auth/change-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"currentPassword":"oldPass123","newPassword":"newPass456"}
                                """))
                .andExpect(status().isUnauthorized());

        // Успешная смена пароля → 200 OK + новый JWT.
        MvcResult changed = mockMvc.perform(post("/api/v1/auth/change-password")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"currentPassword":"oldPass123","newPassword":"newPass456"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token", notNullValue()))
                .andExpect(jsonPath("$.username").value("pwd_user"))
                .andReturn();
        String refreshedToken = objectMapper.readTree(changed.getResponse().getContentAsString())
                .get("token").asText();

        mockMvc.perform(get("/api/v1/auth/me")
                        .header("Authorization", "Bearer " + refreshedToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value("pwd_user"));

        // Прежний токен, выданный до смены пароля, больше не принимается → 401.
        mockMvc.perform(get("/api/v1/auth/me")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized());

        // Старый пароль больше не принимается при входе.
        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"pwd_user","password":"oldPass123"}
                                """))
                .andExpect(status().isUnauthorized());

        // Новый пароль успешно проходит аутентификацию.
        String reloginToken = loginAndGetToken("pwd_user", "newPass456");
        mockMvc.perform(get("/api/v1/auth/me")
                        .header("Authorization", "Bearer " + reloginToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value("pwd_user"));
    }

    @Test
    @DisplayName("Публичные страницы справки доступны без авторизации, а Swagger закрыт")
    void publicDocumentationPagesAreAccessibleAndSwaggerIsClosed() throws Exception {
        mockMvc.perform(get("/")).andExpect(status().isOk());
        mockMvc.perform(get("/guide.html")).andExpect(status().isOk());
        mockMvc.perform(get("/architecture.html")).andExpect(status().isOk());

        mockMvc.perform(get("/swagger-ui.html")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/v3/api-docs")).andExpect(status().isUnauthorized());

        String token = loginAndGetToken("admin", "secret123");
        mockMvc.perform(get("/swagger-ui.html").header("Authorization", "Bearer " + token))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/v3/api-docs").header("Authorization", "Bearer " + token))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("Регистрация с ФИО и обновление ФИО в личном кабинете")
    void registrationStoresFullNameAndProfileUpdateChangesIt() throws Exception {
        MvcResult regResult = mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"fio_user","password":"pass1234","fullName":"Сидорова Анна Сергеевна"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.username").value("fio_user"))
                .andExpect(jsonPath("$.fullName").value("Сидорова Анна Сергеевна"))
                .andReturn();
        String token = objectMapper.readTree(regResult.getResponse().getContentAsString())
                .get("token").asText();

        mockMvc.perform(get("/api/v1/auth/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.fullName").value("Сидорова Анна Сергеевна"));

        // Пустое / слишком короткое ФИО → 400 Bad Request.
        mockMvc.perform(put("/api/v1/auth/profile")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"fullName":" "}
                                """))
                .andExpect(status().isBadRequest());

        // Без авторизации → 401 Unauthorized.
        mockMvc.perform(put("/api/v1/auth/profile")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"fullName":"Иванов Иван"}
                                """))
                .andExpect(status().isUnauthorized());

        // Успешное изменение ФИО → 200 OK.
        MvcResult updated = mockMvc.perform(put("/api/v1/auth/profile")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"fullName":"Петрова Анна Сергеевна"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.fullName").value("Петрова Анна Сергеевна"))
                .andExpect(jsonPath("$.token", notNullValue()))
                .andReturn();
        String updatedToken = objectMapper.readTree(updated.getResponse().getContentAsString())
                .get("token").asText();

        mockMvc.perform(get("/api/v1/auth/me").header("Authorization", "Bearer " + updatedToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.fullName").value("Петрова Анна Сергеевна"));
    }

    private String registerAndGetToken(String username, String password) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"%s","password":"%s"}
                                """.formatted(username, password)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.token", notNullValue()))
                .andExpect(jsonPath("$.role").value("ADMIN"))
                .andReturn();
        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
        return body.get("token").asText();
    }

    private String loginAndGetToken(String username, String password) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"%s","password":"%s"}
                                """.formatted(username, password)))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString())
                .get("token").asText();
    }
}
