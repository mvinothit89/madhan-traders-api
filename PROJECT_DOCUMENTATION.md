# Madhan Traders Sales Application - Complete Project Documentation

This document provides a comprehensive overview of the **Madhan Traders Sales Application** (`sales-api`), detailing its architecture, database models, REST APIs, security configuration, and frontend static assets.

---

## 1. Project Overview & Technology Stack

- **Application Name:** Madhan Traders Sales Application (`sales-api`)
- **Backend Framework:** Spring Boot 3.2.0 (`spring-boot-starter-web`, `spring-boot-starter-data-jpa`, `spring-boot-starter-security`)
- **Database:** PostgreSQL (Hosted on Supabase cloud: `jdbc:postgresql://aws-0-ap-south-1.pooler.supabase.com:6543/postgres`)
- **ORM / Persistence:** Hibernate / Spring Data JPA (`spring.jpa.hibernate.ddl-auto=update`)
- **Utilities / Boilerplate Reduction:** Project Lombok (`@Data`, `@RequiredArgsConstructor`, etc.)
- **API Documentation:** SpringDoc OpenAPI 2.3.0 (Swagger UI available at `/swagger-ui/index.html`)
- **Frontend Assets:** HTML5 / CSS / JavaScript single-page applications and management dashboards hosted in `src/main/resources/static/`.

---

## 2. Project Directory Structure

```text
api_project/
├── Dockerfile
├── pom.xml
├── src/
│   ├── main/
│   │   ├── java/com/mt/salesapp/
│   │   │   ├── MadhanTradersApplication.java
│   │   │   ├── config/
│   │   │   │   ├── SecurityConfig.java
│   │   │   │   └── SwaggerConfig.java
│   │   │   ├── controller/
│   │   │   │   ├── AppUserController.java
│   │   │   │   ├── AuthController.java
│   │   │   │   ├── CashFlowController.java
│   │   │   │   ├── DailySalesController.java
│   │   │   │   ├── ExpenseLedgerController.java
│   │   │   │   └── MadhanTradersController.java
│   │   │   ├── dto/
│   │   │   │   ├── CustomUserDetails.java
│   │   │   │   ├── DashboardSummaryDto.java
│   │   │   │   ├── LoginRequest.java
│   │   │   │   ├── UserRequestDto.java
│   │   │   │   └── UserResponseDto.java
│   │   │   ├── exception/
│   │   │   │   ├── GlobalExceptionHandler.java
│   │   │   │   └── ResourceNotFoundException.java
│   │   │   ├── model/
│   │   │   │   ├── AppUser.java
│   │   │   │   ├── AuditLog.java
│   │   │   │   ├── CashCollectedEntry.java
│   │   │   │   ├── CashFlowEntry.java
│   │   │   │   ├── Customer.java
│   │   │   │   ├── DailyExpenseSum.java
│   │   │   │   ├── DailySales.java
│   │   │   │   ├── ExpenseLedger.java
│   │   │   │   ├── MaterialSalesEntry.java
│   │   │   │   ├── Payment.java
│   │   │   │   ├── Product.java
│   │   │   │   ├── UserMapper.java
│   │   │   │   └── WaterSalesEntry.java
│   │   │   ├── repository/
│   │   │   │   ├── AppUserRepository.java
│   │   │   │   ├── CashCollectedRepository.java
│   │   │   │   ├── CashFlowRepository.java
│   │   │   │   ├── CustomerRepository.java
│   │   │   │   ├── DailySalesRepository.java
│   │   │   │   ├── ExpenseLedgerRepository.java
│   │   │   │   ├── MaterialSalesRepository.java
│   │   │   │   ├── PaymentRepository.java
│   │   │   │   ├── ProductRepository.java
│   │   │   │   └── WaterSalesRepository.java
│   │   │   └── service/
│   │   │       ├── AppUserService.java
│   │   │       ├── BusinessService.java
│   │   │       ├── CashFlowService.java
│   │   │       ├── CustomUserDetailsService.java
│   │   │       ├── DailySalesService.java
│   │   │       ├── DailySalesSyncService.java
│   │   │       ├── ExpenseLedgerService.java
│   │   │       ├── MadhanTradersService.java
│   │   │       └── SchedulerService.java
│   │   └── resources/
│       │   ├── application.properties
│       │   └── static/
│       │       ├── adminUI.html
│       │       ├── index.html
│       │       ├── index1.html
│       │       ├── index_prod.html
│       │       ├── login.html
│       │       └── mt_icon.png
└── target/
```

---

## 3. Frontend Static Assets Overview

Located in `src/main/resources/static/`:
1. **`index.html` / `index_prod.html`:** The primary production-ready sales and dashboard management interface (identical content with 161KB feature-rich dashboard logic, forms for water sales, material sales, cash collected, customers, payments, and reporting).
2. **`index1.html`:** Alternative frontend layout/version.
3. **`adminUI.html`:** Administrative interface for user management, system settings, and audit log monitoring.
4. **`login.html`:** Authentication login portal interfacing with `/api/auth/login`.
5. **`mt_icon.png`:** Application branding icon.

---

## 4. Database Models & Entities

- **`AppUser`:** System users with UUID primary key, username, password hash (`passcode`), full name, phone number, and role (e.g. `ADMIN`, `USER`).
- **`Customer`:** Customer records for tracking sales, credit, and outstanding balances.
- **`Product`:** Product catalog (Water, Materials, etc.) with pricing and inventory details.
- **`WaterSalesEntry`:** Recorded water sales transactions with date, customer reference, quantities, and amounts.
- **`MaterialSalesEntry`:** Recorded construction/building material sales transactions.
- **`CashCollectedEntry`:** Tracking daily cash collections from customers.
- **`CashFlowEntry`:** Income and expense cash flow ledger entries.
- **`Payment`:** Customer payment transactions applied against outstanding balances.
- **`ExpenseLedger`:** Detailed business expense records with date, category, amount, and description.
- **`DailySales` & `DailyExpenseSum`:** Aggregated daily summaries for financial reporting and dashboard metrics.
- **`AuditLog`:** System audit trail tracking critical actions.

---

## 5. REST API Endpoints Reference

### Authentication (`/api/auth`)
- `POST /api/auth/login` - Authenticates user credentials (username/password) and establishes an HTTP session (`JSESSIONID`). Returns `UserResponseDto`.
- `POST /api/auth/logout` - Terminates the active session and clears cookies.

### Users Management (`/api/users`)
- `GET /api/users` - Retrieve all system users.
- `GET /api/users/{id}` - Retrieve user by UUID.
- `POST /api/users` - Create a new user (with BCrypt password hashing).
- `PUT /api/users/{id}` - Update existing user details.
- `DELETE /api/users/{id}` - Remove a user.

### Madhan Traders Core APIs (`/api`)
- **Dashboard:**
  - `GET /api/dashboard/summary` - Returns `DashboardSummaryDto` containing financial summaries, sales totals, and key metrics.
- **Products:**
  - `GET /api/products` - List all products.
  - `POST /api/products` - Create a product.
  - `PUT /api/products/{id}` - Update a product.
  - `DELETE /api/products/{id}` - Delete a product.
- **Water Sales:**
  - `GET /api/water-sales` - List all water sales entries.
  - `POST /api/water-sales` - Record a new water sale.
  - `PUT /api/water-sales/{id}` - Update water sale entry.
  - `DELETE /api/water-sales/{id}` - Delete water sale entry.
- **Material Sales:**
  - `GET /api/material-sales` - List all material sales entries.
  - `POST /api/material-sales` - Record a new material sale.
  - `PUT /api/material-sales/{id}` - Update material sale entry.
  - `DELETE /api/material-sales/{id}` - Delete material sale entry.
- **Cash Collected:**
  - `GET /api/cash-collected` - List cash collected entries.
  - `POST /api/cash-collected` - Record cash collection.
  - `DELETE /api/cash-collected/{id}` - Delete cash collection record.
- **Customers & Payments:**
  - `GET /api/customers` - List all customers.
  - `POST /api/customers` - Create a customer.
  - `PUT /api/customers/{id}` - Update customer details.
  - `POST /api/payments` - Process a customer payment.

### Cash Flow (`/api/cash-flow`)
- `GET /api/cash-flow` - Get all cash flow entries ordered by date descending.
- `POST /api/cash-flow` - Create a cash flow entry.
- `PUT /api/cash-flow/{id}` - Update a cash flow entry.
- `DELETE /api/cash-flow/{id}` - Delete a cash flow entry.

### Expenses (`/api/expenses`)
- `GET /api/expenses` - List all expense records.
- `POST /api/expenses` - Create an expense.
- `PUT /api/expenses/{id}` - Update an expense.
- `DELETE /api/expenses/{id}` - Delete an expense.
- `GET /api/expenses/search` - Search expenses by date range (`startDate`, `endDate`).
- `GET /api/expenses/sum-per-day` - Get aggregated daily expense summaries for a date range.

### Daily Sales & Synchronization (`/api/daily-sales`)
- `GET /api/daily-sales` - Get all daily sales summaries.
- `GET /api/daily-sales/{date}` - Get sales summary for a specific date (`YYYY-MM-DD`).
- `POST /api/daily-sales` - Save or update daily sales entry.
- `POST /api/daily-sales/sync` - Synchronize daily sales summaries from underlying source tables.

---

## 6. Security Configuration (`SecurityConfig.java`)

- **CSRF:** Disabled for REST API ease of use.
- **Session Management:** `IF_REQUIRED` with single session maximum per user.
- **CORS:** Configured to accept requests from origins `http://localhost:5173` and `http://localhost:8080` with credentials enabled (`allowCredentials(true)`).
- **Authorization:** Public access permitted to static HTML files, assets, authentication endpoints, and `/api/**` routes during development.
- **Password Encoding:** BCrypt hashing (`PasswordEncoder` bean).

---

## 7. Build & Execution

- **Build Project:** `mvn clean install`
- **Run Application:** `mvn spring-boot:run`
- **Access URLs:**
  - Web UI: `http://localhost:8080/index.html` (or `index_prod.html`)
  - Admin UI: `http://localhost:8080/adminUI.html`
  - Swagger API Docs: `http://localhost:8080/swagger-ui/index.html`
