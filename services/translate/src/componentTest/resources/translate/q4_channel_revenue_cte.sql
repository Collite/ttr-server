SELECT "t3"."channel", SUM("t3"."net_paid") AS "revenue"
FROM (SELECT *
            FROM (SELECT "ss_sold_date_sk" AS "sold_date_sk", "ss_net_paid" AS "net_paid", 'store  ' AS "channel"
                        FROM "store_sales"
                        UNION ALL
                        SELECT "cs_sold_date_sk" AS "sold_date_sk", "cs_net_paid" AS "net_paid", 'catalog' AS "channel"
                        FROM "catalog_sales") AS "t"
            UNION ALL
            SELECT "ws_sold_date_sk" AS "sold_date_sk", "ws_net_paid" AS "net_paid", 'web    ' AS "channel"
            FROM "web_sales") AS "t3"
    INNER JOIN "date_dim" ON "t3"."sold_date_sk" = "date_dim"."d_date_sk"
WHERE "date_dim"."d_year" = 2002
GROUP BY "t3"."channel"
ORDER BY SUM("t3"."net_paid") DESC NULLS LAST
