-- =============================================================================
-- Flash Sale Platform — inventory_db — V1__init.sql
-- Owner      : InventoryService
-- Aggregates : Product (root) + StockLevel (owned entity)
-- Scope      : Approved Week 3 domain and JPA persistence model only
-- =============================================================================

-- ---------------------------------------------------------------------------
-- products
-- Maps exactly to ProductJpaEntity.
-- UUID values are assigned by the domain, so the database does not generate IDs.
-- ---------------------------------------------------------------------------
CREATE TABLE products (
    id          UUID    NOT NULL,
    total_stock INTEGER NOT NULL,
    version     BIGINT  NOT NULL,

    CONSTRAINT products_pkey
        PRIMARY KEY (id),
    CONSTRAINT products_total_stock_ck
        CHECK (total_stock >= 0),
    CONSTRAINT products_version_ck
        CHECK (version >= 0)
);

-- ProductRepository.findById uses products_pkey's automatically-created index.

-- ---------------------------------------------------------------------------
-- stock_levels
-- Maps exactly to StockLevelJpaEntity and is owned by products.
-- sale_id is an opaque SaleService reference; no cross-database FK is possible.
-- ---------------------------------------------------------------------------
CREATE TABLE stock_levels (
    id              UUID    NOT NULL,
    product_id      UUID    NOT NULL,
    sale_id         UUID    NOT NULL,
    total_allocated INTEGER NOT NULL,
    current_stock   INTEGER NOT NULL,
    version         BIGINT  NOT NULL,

    CONSTRAINT stock_levels_pkey
        PRIMARY KEY (id),
    CONSTRAINT stock_levels_product_id_fk
        FOREIGN KEY (product_id)
        REFERENCES products (id)
        ON DELETE RESTRICT,
    CONSTRAINT stock_levels_product_sale_unique
        UNIQUE (product_id, sale_id),
    CONSTRAINT stock_levels_total_allocated_ck
        CHECK (total_allocated > 0),
    CONSTRAINT stock_levels_current_stock_ck
        CHECK (current_stock >= 0),
    CONSTRAINT stock_levels_stock_ceiling_ck
        CHECK (current_stock <= total_allocated),
    CONSTRAINT stock_levels_version_ck
        CHECK (version >= 0)
);

-- The unique constraint creates a B-tree index beginning with product_id. It both
-- enforces one allocation per Product + Sale and supports the entity-graph join used
-- when ProductRepository loads a Product with its owned StockLevels.
