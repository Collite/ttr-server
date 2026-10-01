SELECT "catalog_sales"."cs_item_sk" AS "cs_item_sk", SUM("catalog_sales"."cs_ext_sales_price") AS "cs_ext_sales_price"
FROM "catalog_sales"
GROUP BY "catalog_sales"."cs_item_sk"
