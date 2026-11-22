# Примеры запросов/ответов REST API (черновик)

Базовый префикс: `/api/v1`. Формат: `application/json; charset=UTF-8`.
Все даты — ISO-8601. Денежные суммы — числа с 2 знаками после запятой.

## Аутентификация

### POST /api/v1/auth/login

```json
{ "username": "manager", "password": "manager123" }
```

```json
{
  "token": "eyJhbGciOiJIUzI1NiJ9...",
  "username": "manager",
  "fullName": "Иванов Иван Иванович",
  "role": "MANAGER"
}
```

## Умный подбор помещений

### POST /api/v1/premises/match

```json
{
  "type": "OFFICE",
  "targetArea": 85.0,
  "minArea": 60.0,
  "maxArea": 110.0,
  "maxBudget": 200000.00,
  "startDate": "2026-01-01",
  "endDate": "2026-12-31"
}
```

```json
[
  {
    "premisesId": 3,
    "code": "A-301",
    "areaSqm": 82.50,
    "estimatedMonthlyCost": 173250.00,
    "score": 81.5
  }
]
```

## Предпросмотр договора

### POST /api/v1/contracts/preview

```json
{
  "premisesId": 3,
  "tenantId": 2,
  "startDate": "2026-01-01",
  "endDate": "2026-12-31"
}
```

```json
{
  "hasConflict": false,
  "conflictingContract": null,
  "baseMonthly": 74250.00,
  "typedMonthly": 74250.00,
  "opexMonthly": 12375.00,
  "discountPercent": 10.00,
  "netMonthly": 77962.50,
  "vatAmount": 15592.50,
  "totalMonthly": 93555.00,
  "months": 12,
  "schedule": [
    { "periodNumber": 1, "dueDate": "2026-01-01", "amountDue": 93555.00 }
  ]
}
```

## Единый формат ошибок

```json
{
  "timestamp": "2026-10-15T14:30:00",
  "status": 409,
  "error": "Conflict",
  "message": "Помещение A-301 уже занято в указанный период (договор BC-2026-0003)",
  "details": []
}
```
