-- =============================================================================
-- BC-Manager — обновление паролей учётных записей на защищённые (Flyway V3)
-- BCrypt, cost 10.
-- =============================================================================

UPDATE users
SET password_hash = '$2a$10$Q7mw1bAKHmolTUvU5itPR.0y1PY9dugZ48K/3s5vPfpKskxyvHQs6'
WHERE username = 'admin';

UPDATE users
SET password_hash = '$2a$10$oju8QF4S9aCvFsuyJIny8ePlfCM/Ye9VtugycbWaWWnhSbgTqVfqS'
WHERE username = 'manager';
