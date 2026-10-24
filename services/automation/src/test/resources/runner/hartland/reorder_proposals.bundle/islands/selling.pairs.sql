SELECT "item", "warehouse", COUNT("line_no") AS "lines"
FROM "sales_line"
WHERE "sold_date" >= :as_of_minus_demand_period_weeks AND "sold_date" <= :as_of
GROUP BY "item", "warehouse"
