package ru.bcmanager.service;

import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.bcmanager.domain.Tenant;
import ru.bcmanager.repository.TenantRepository;

/**
 * CRUD-сервис арендаторов (Plan.md, раздел 5.3).
 *
 * <p>ИНН валидируется через {@link Tenant#isValidInn(String)}; деактивация —
 * мягкое удаление ({@code is_active = false}), чтобы сохранить историю
 * договоров.
 */
@Service
public class TenantService {

    private final TenantRepository tenantRepository;

    /**
     * Создать сервис арендаторов.
     *
     * @param tenantRepository репозиторий арендаторов
     */
    public TenantService(TenantRepository tenantRepository) {
        this.tenantRepository = tenantRepository;
    }

    /**
     * Список арендаторов или поиск по подстроке названия/ИНН.
     *
     * @param query строка поиска; пустая — вернуть всех
     * @return список арендаторов по возрастанию названия
     */
    @Transactional(readOnly = true)
    public List<Tenant> findAll(String query) {
        List<Tenant> tenants = query == null || query.isBlank()
                ? tenantRepository.findAll()
                : tenantRepository.search(query.trim());
        tenants.sort((left, right) -> left.getCompanyName().compareToIgnoreCase(right.getCompanyName()));
        return tenants;
    }

    /**
     * Найти арендатора по идентификатору.
     *
     * @param id идентификатор
     * @return арендатор
     * @throws java.util.NoSuchElementException если арендатор не найден
     */
    @Transactional(readOnly = true)
    public Tenant findById(Long id) {
        return tenantRepository.findById(id).orElseThrow(() -> new java.util.NoSuchElementException(
                "Арендатор с id=" + id + " не найден"));
    }

    /**
     * Зарегистрировать арендатора.
     *
     * @param tenant новый арендатор
     * @return сохранённая сущность
     */
    @Transactional
    public Tenant create(Tenant tenant) {
        if (!Tenant.isValidInn(tenant.getInn())) {
            throw new IllegalArgumentException("ИНН должен состоять из 10 или 12 цифр");
        }
        if (tenantRepository.existsByInn(tenant.getInn())) {
            throw new IllegalArgumentException(
                    "Арендатор с ИНН " + tenant.getInn() + " уже существует");
        }
        return tenantRepository.save(tenant);
    }

    /**
     * Обновить данные арендатора.
     *
     * @param id     идентификатор
     * @param tenant новые значения
     * @return сохранённая сущность
     */
    @Transactional
    public Tenant update(Long id, Tenant tenant) {
        Tenant existing = findById(id);
        if (!Tenant.isValidInn(tenant.getInn())) {
            throw new IllegalArgumentException("ИНН должен состоять из 10 или 12 цифр");
        }
        existing.setCompanyName(tenant.getCompanyName());
        existing.setInn(tenant.getInn());
        existing.setContactPerson(tenant.getContactPerson());
        existing.setPhone(tenant.getPhone());
        existing.setEmail(tenant.getEmail());
        existing.setActive(tenant.isActive());
        return tenantRepository.save(existing);
    }

    /**
     * Деактивировать арендатора.
     *
     * @param id идентификатор
     * @return деактивированная сущность
     */
    @Transactional
    public Tenant deactivate(Long id) {
        Tenant tenant = findById(id);
        tenant.deactivate();
        return tenantRepository.save(tenant);
    }
}
