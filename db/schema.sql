DROP DATABASE IF EXISTS ecommerce_db;
CREATE DATABASE ecommerce_db
    WITH
    TEMPLATE = template0
    ENCODING = 'UTF8';

SELECT current_database();


	

SELECT current_database();

-- ============================================================================
-- TABLES
-- ============================================================================

-- Categories
CREATE TABLE categories (
    id          BIGINT GENERATED ALWAYS AS IDENTITY,
    name        VARCHAR(100) NOT NULL,
    slug        VARCHAR(100) NOT NULL,
    description TEXT,
    created_at  TIMESTAMP,
    updated_at  TIMESTAMP,

    CONSTRAINT pk_categories PRIMARY KEY (id),
    CONSTRAINT uk_categories_name UNIQUE (name),
    CONSTRAINT uk_categories_slug UNIQUE (slug)
);

-- Products
CREATE TABLE products (
    id             BIGINT GENERATED ALWAYS AS IDENTITY,
    name           VARCHAR(200) NOT NULL,
    description    TEXT,
    price          NUMERIC(10, 2) NOT NULL,
    sku            VARCHAR(100) NOT NULL,
    stock_quantity INTEGER NOT NULL DEFAULT 0,
    active         BOOLEAN NOT NULL DEFAULT TRUE,
    category_id    BIGINT NOT NULL,
    created_at     TIMESTAMP,
    updated_at     TIMESTAMP,

    CONSTRAINT pk_products PRIMARY KEY (id),
    CONSTRAINT uk_products_sku UNIQUE (sku),
    CONSTRAINT fk_products_category
        FOREIGN KEY (category_id)
        REFERENCES categories (id)
);

-- Customers
CREATE TABLE customers (
    id         BIGINT GENERATED ALWAYS AS IDENTITY,
    first_name VARCHAR(100) NOT NULL,
    last_name  VARCHAR(100) NOT NULL,
    email      VARCHAR(255) NOT NULL,
    phone      VARCHAR(20),
    address    TEXT,
    created_at TIMESTAMP,

    CONSTRAINT pk_customers PRIMARY KEY (id),
    CONSTRAINT uk_customers_email UNIQUE (email)
);

-- Orders
CREATE TABLE orders (
    id               BIGINT GENERATED ALWAYS AS IDENTITY,
    order_number     VARCHAR(20) NOT NULL,
    customer_id      BIGINT NOT NULL,
    status           VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    total_amount     NUMERIC(12, 2) NOT NULL DEFAULT 0.00,
    shipping_address TEXT NOT NULL,
    notes            TEXT,
    created_at       TIMESTAMP,
    updated_at       TIMESTAMP,

    CONSTRAINT pk_orders PRIMARY KEY (id),
    CONSTRAINT uk_orders_order_number UNIQUE (order_number),
    CONSTRAINT fk_orders_customer
        FOREIGN KEY (customer_id)
        REFERENCES customers (id)
);

-- Order items
CREATE TABLE order_items (
    id         BIGINT GENERATED ALWAYS AS IDENTITY,
    order_id   BIGINT NOT NULL,
    product_id BIGINT NOT NULL,
    quantity   INTEGER NOT NULL,
    unit_price NUMERIC(10, 2) NOT NULL,
    subtotal   NUMERIC(12, 2) NOT NULL,

    CONSTRAINT pk_order_items PRIMARY KEY (id),
    CONSTRAINT fk_order_items_order
        FOREIGN KEY (order_id)
        REFERENCES orders (id),
    CONSTRAINT fk_order_items_product
        FOREIGN KEY (product_id)
        REFERENCES products (id),

    CONSTRAINT chk_order_items_quantity
        CHECK (quantity > 0),
    CONSTRAINT chk_order_items_unit_price
        CHECK (unit_price >= 0),
    CONSTRAINT chk_order_items_subtotal
        CHECK (subtotal >= 0)
);

-- Payments
CREATE TABLE payments (
    id             BIGINT GENERATED ALWAYS AS IDENTITY,
    order_id       BIGINT NOT NULL,
    payment_method VARCHAR(30) NOT NULL,
    payment_status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    amount         NUMERIC(12, 2) NOT NULL,
    created_at     TIMESTAMP,

    CONSTRAINT pk_payments PRIMARY KEY (id),
    CONSTRAINT uk_payments_order_id UNIQUE (order_id),
    CONSTRAINT fk_payments_order
        FOREIGN KEY (order_id)
        REFERENCES orders (id),

    CONSTRAINT chk_payments_amount
        CHECK (amount >= 0)
);

-- Reviews
CREATE TABLE reviews (
    id          BIGINT GENERATED ALWAYS AS IDENTITY,
    product_id  BIGINT NOT NULL,
    customer_id BIGINT NOT NULL,
    rating      INTEGER NOT NULL,
    comment     TEXT,
    created_at  TIMESTAMP,

    CONSTRAINT pk_reviews PRIMARY KEY (id),
    CONSTRAINT fk_reviews_product
        FOREIGN KEY (product_id)
        REFERENCES products (id),
    CONSTRAINT fk_reviews_customer
        FOREIGN KEY (customer_id)
        REFERENCES customers (id),

    CONSTRAINT chk_reviews_rating
        CHECK (rating BETWEEN 1 AND 5)
);

-- ============================================================================
-- INDEXES
-- ============================================================================

CREATE INDEX idx_products_category
    ON products (category_id);

CREATE INDEX idx_products_active
    ON products (active);

CREATE INDEX idx_orders_customer
    ON orders (customer_id);

CREATE INDEX idx_orders_status
    ON orders (status);

CREATE INDEX idx_order_items_order
    ON order_items (order_id);

CREATE INDEX idx_order_items_product
    ON order_items (product_id);

CREATE INDEX idx_reviews_product
    ON reviews (product_id);

-- ============================================================================
-- SEED DATA
--
-- Orders, order_items and payments are intentionally left empty.
-- They can be created through the API.
-- ============================================================================

-- Categories
INSERT INTO categories (
    name,
    slug,
    description,
    created_at,
    updated_at
)
VALUES
    (
        'Electronics',
        'electronics',
        'Gadgets, accessories and devices',
        CURRENT_TIMESTAMP,
        CURRENT_TIMESTAMP
    ),
    (
        'Books',
        'books',
        'Printed and reference books',
        CURRENT_TIMESTAMP,
        CURRENT_TIMESTAMP
    ),
    (
        'Clothing',
        'clothing',
        'Apparel and everyday wear',
        CURRENT_TIMESTAMP,
        CURRENT_TIMESTAMP
    ),
    (
        'Home & Kitchen',
        'home-kitchen',
        'Homeware and kitchen essentials',
        CURRENT_TIMESTAMP,
        CURRENT_TIMESTAMP
    );

-- Products
INSERT INTO products (
    name,
    description,
    price,
    sku,
    stock_quantity,
    active,
    category_id,
    created_at,
    updated_at
)
VALUES
    (
        'Wireless Mouse',
        'Ergonomic 2.4GHz wireless mouse',
        24.99,
        'ELEC-MOU-001',
        150,
        TRUE,
        1,
        CURRENT_TIMESTAMP,
        CURRENT_TIMESTAMP
    ),
    (
        'Mechanical Keyboard',
        'Tactile mechanical keyboard, RGB backlit',
        79.99,
        'ELEC-KEY-002',
        80,
        TRUE,
        1,
        CURRENT_TIMESTAMP,
        CURRENT_TIMESTAMP
    ),
    (
        'USB-C Hub',
        '7-in-1 USB-C multiport adapter',
        39.99,
        'ELEC-HUB-003',
        60,
        TRUE,
        1,
        CURRENT_TIMESTAMP,
        CURRENT_TIMESTAMP
    ),
    (
        'Noise-Cancelling Headphones',
        'Over-ear ANC wireless headphones',
        199.99,
        'ELEC-HDP-004',
        40,
        TRUE,
        1,
        CURRENT_TIMESTAMP,
        CURRENT_TIMESTAMP
    ),
    (
        'Clean Code',
        'A Handbook of Agile Software Craftsmanship',
        32.50,
        'BOOK-CLN-001',
        200,
        TRUE,
        2,
        CURRENT_TIMESTAMP,
        CURRENT_TIMESTAMP
    ),
    (
        'The Pragmatic Programmer',
        'Your Journey to Mastery',
        41.00,
        'BOOK-PRG-002',
        120,
        TRUE,
        2,
        CURRENT_TIMESTAMP,
        CURRENT_TIMESTAMP
    ),
    (
        'Cotton T-Shirt',
        'Soft 100% cotton crew-neck tee',
        15.00,
        'CLTH-TSH-001',
        300,
        TRUE,
        3,
        CURRENT_TIMESTAMP,
        CURRENT_TIMESTAMP
    ),
    (
        'Hooded Sweatshirt',
        'Fleece-lined pullover hoodie',
        45.00,
        'CLTH-HOD-002',
        90,
        TRUE,
        3,
        CURRENT_TIMESTAMP,
        CURRENT_TIMESTAMP
    ),
    (
        'Stainless Steel Bottle',
        'Insulated 750ml water bottle',
        18.99,
        'HOME-BTL-001',
        250,
        TRUE,
        4,
        CURRENT_TIMESTAMP,
        CURRENT_TIMESTAMP
    ),
    (
        'Ceramic Coffee Mug',
        '350ml glazed ceramic mug',
        12.50,
        'HOME-MUG-002',
        180,
        TRUE,
        4,
        CURRENT_TIMESTAMP,
        CURRENT_TIMESTAMP
    );

-- Customers
INSERT INTO customers (
    first_name,
    last_name,
    email,
    phone,
    address,
    created_at
)
VALUES
    (
        'John',
        'Doe',
        'john.doe@example.com',
        '+1-202-555-0101',
        '123 Maple Street, Springfield',
        CURRENT_TIMESTAMP
    ),
    (
        'Jane',
        'Smith',
        'jane.smith@example.com',
        '+1-202-555-0142',
        '88 Oak Avenue, Riverdale',
        CURRENT_TIMESTAMP
    ),
    (
        'Ravi',
        'Kumar',
        'ravi.kumar@example.com',
        '+91-90000-12345',
        '12 MG Road, Bengaluru',
        CURRENT_TIMESTAMP
    );

-- ============================================================================
-- VERIFICATION
-- ============================================================================

SELECT current_database();

SELECT * FROM categories ORDER BY id;
SELECT * FROM products ORDER BY id;
SELECT * FROM customers ORDER BY id;
SELECT * FROM orders ORDER BY id;
SELECT * FROM order_items ORDER BY id;
SELECT * FROM payments ORDER BY id;