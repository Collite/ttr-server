SELECT "order"."order_number", "date_dim"."cal_date" AS "placed", CAST('release' AS CHAR(7)) AS "route", 'calendar fixture' AS "verdict_reason"
FROM "order"
    INNER JOIN "date_dim" ON "date_dim"."cal_date" = CAST(:as_of_minus_5_days AS DATE)
