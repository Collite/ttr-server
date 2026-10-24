-- SPDX-License-Identifier: Apache-2.0
-- Invented data; names/FQNs match the published northline export fixture.
CREATE SCHEMA retail;
CREATE TABLE retail.buyers (id bigint PRIMARY KEY, name text NOT NULL);
CREATE TABLE retail.products (id bigint PRIMARY KEY, name text NOT NULL);
CREATE TABLE retail.sales (
    id bigint PRIMARY KEY, buyer_id bigint REFERENCES retail.buyers(id),
    amount numeric(12,2), sold_at timestamp
);
CREATE VIEW retail.recent_sales AS SELECT id FROM retail.sales;
INSERT INTO retail.buyers VALUES (1, 'Sample Buyer');
INSERT INTO retail.products VALUES (1, 'Sample Product');
INSERT INTO retail.sales VALUES (1, 1, 10.00, '2026-01-01 12:00:00');
