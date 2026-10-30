-- The hartland-mini world-DB tables (P3 S3.3's Testcontainers `world_db` applies this file). `item` and `customer` are
-- the two dimensions a review's Ref attributes resolve through, seeded with the pack's four items and its customers;
-- `review` is the extraction table record-etl writes. Column names are the er attributes' (model/er.ttrm), so the
-- er → table binding is TTR-M's convention, with no binding file.
CREATE TABLE item (
    sk           integer PRIMARY KEY,
    item_id      text NOT NULL UNIQUE,
    product_name text NOT NULL,
    brand        text,
    category     text
);

CREATE TABLE customer (
    sk   integer PRIMARY KEY,
    name text NOT NULL UNIQUE
);

CREATE TABLE review (
    review_id         integer GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    item              integer NOT NULL REFERENCES item (sk),
    customer          integer REFERENCES customer (sk),
    rating            integer NOT NULL,
    title             varchar(120),
    body              text NOT NULL,
    sentiment         text NOT NULL CHECK (sentiment IN ('positive', 'neutral', 'negative')),
    verification      text NOT NULL CHECK (verification IN ('verified_purchase', 'unverified', 'employee')),
    temporal_context  text NOT NULL CHECK (temporal_context IN ('current', 'past', 'intended')),
    price_paid        numeric(10, 2),
    helpful_votes     integer,
    recommended       boolean,
    reviewed_on       date NOT NULL,
    recorded_at       timestamptz NOT NULL,
    annotation_refs   text NOT NULL,
    consolidation_run text NOT NULL
);

INSERT INTO item (sk, item_id, product_name, brand, category) VALUES
    (1, 'HL-1001', 'Aurora Desk Lamp', 'Hartland Home', 'Home'),
    (2, 'HL-2040', 'Breeze Tower Fan', 'Hartland Home', 'Home'),
    (3, 'HL-3310', 'Pulse Wireless Headphones', 'Hartland Sound', 'Electronics'),
    (4, 'HL-4205', 'Summit Kettle', 'Hartland Home', 'Home');

INSERT INTO customer (sk, name) VALUES
    (101, 'C-20417'),
    (102, 'C-20988'),
    (103, 'C-31102'),
    (104, 'C-31560'),
    (105, 'C-40311'),
    (106, 'C-52007');
