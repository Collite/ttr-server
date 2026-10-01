SELECT "item"."i_item_id", SUM("store_sales"."ss_net_paid") AS "revenue"
FROM "store_sales"
    INNER JOIN "item" ON "store_sales"."ss_item_sk" = "item"."i_item_sk"
    INNER JOIN "date_dim" ON "store_sales"."ss_sold_date_sk" = "date_dim"."d_date_sk"
WHERE "date_dim"."d_year" = 2002
GROUP BY "item"."i_item_id"
ORDER BY SUM("store_sales"."ss_net_paid") DESC NULLS LAST
FETCH NEXT 100 ROWS ONLY
