SELECT "order"."order_number", "date_dim"."cal_date" AS "placed"
FROM "order"
    INNER JOIN "date_dim" ON "date_dim"."cal_date" = CAST(:as_of_minus_5_days AS DATE)
