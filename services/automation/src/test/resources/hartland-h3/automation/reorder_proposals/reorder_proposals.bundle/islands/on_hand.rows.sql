SELECT "t1"."item", "t1"."warehouse", COALESCE("t1"."quantity_on_hand", 0) AS "on_hand"
FROM (SELECT "t0"."item", "t0"."warehouse", "t0"."lines", "inventory"."as_of_date", "inventory"."quantity_on_hand", CASE WHEN "t0"."item" IS NULL THEN 'n' ELSE 'v' || CAST("t0"."item" AS VARCHAR) END AS "_ttrp_g_n7_0", CASE WHEN "t0"."warehouse" IS NULL THEN 'n' ELSE 'v' || CAST("t0"."warehouse" AS VARCHAR) END AS "_ttrp_g_n7_1"
        FROM (SELECT "item", "warehouse", COUNT("line_no") AS "lines"
                FROM "sales_line"
                WHERE "sold_date" >= :as_of_minus_demand_period_weeks AND "sold_date" <= :as_of
                GROUP BY "item", "warehouse") AS "t0"
            LEFT JOIN "inventory" ON "t0"."item" = "inventory"."item" AND "t0"."warehouse" = "inventory"."warehouse") AS "t1"
    INNER JOIN (SELECT CASE WHEN "t3"."item" IS NULL THEN 'n' ELSE 'v' || CAST("t3"."item" AS VARCHAR) END AS "_ttrp_g_n7_0", CASE WHEN "t3"."warehouse" IS NULL THEN 'n' ELSE 'v' || CAST("t3"."warehouse" AS VARCHAR) END AS "_ttrp_g_n7_1", MAX("inventory0"."as_of_date") AS "_ttrp_latest_n7"
        FROM (SELECT "item", "warehouse", COUNT("line_no") AS "lines"
                FROM "sales_line"
                WHERE "sold_date" >= :as_of_minus_demand_period_weeks AND "sold_date" <= :as_of
                GROUP BY "item", "warehouse") AS "t3"
            LEFT JOIN "inventory" AS "inventory0" ON "t3"."item" = "inventory0"."item" AND "t3"."warehouse" = "inventory0"."warehouse"
        GROUP BY CASE WHEN "t3"."item" IS NULL THEN 'n' ELSE 'v' || CAST("t3"."item" AS VARCHAR) END, CASE WHEN "t3"."warehouse" IS NULL THEN 'n' ELSE 'v' || CAST("t3"."warehouse" AS VARCHAR) END) AS "t5" ON "t1"."_ttrp_g_n7_0" = "t5"."_ttrp_g_n7_0" AND "t1"."_ttrp_g_n7_1" = "t5"."_ttrp_g_n7_1" AND "t1"."as_of_date" = "t5"."_ttrp_latest_n7"
