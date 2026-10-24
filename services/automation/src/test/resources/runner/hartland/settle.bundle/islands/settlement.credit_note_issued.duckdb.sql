SELECT "p_77af0c22"."line_id", "p_77af0c22"."request_number", "p_77af0c22"."item", "p_77af0c22"."quantity", "p_77af0c22"."way", "p_77af0c22"."customer", "p_77af0c22"."credit_note_id", "p_77af0c22"."issued_date", "p_77af0c22"."payment_id", "p_77af0c22"."paid_date", "p_77af0c22"."shipment_id", "p_77af0c22"."shipped_date", "t5"."max_late", "t5"."kinds_open", 'credit_note_issued' AS "route", 'credit note ' || CASE WHEN "p_77af0c22"."credit_note_id" IS NULL THEN '' ELSE CAST("p_77af0c22"."credit_note_id" AS VARCHAR(65536)) END || ' issued' AS "verdict_reason"
FROM "p_77af0c22"
    LEFT JOIN (SELECT "t"."line_id", MAX(CASE WHEN "t"."due" IS NULL THEN NULL ELSE COALESCE("t3"."_ttrp_n_n20_0", 0) END) AS "max_late", COUNT("t"."kind") AS "kinds_open"
        FROM (SELECT "p_927a867d"."line_id", "p_8124de4c"."id" AS "obligation_id", "p_8124de4c"."kind", "p_8124de4c"."due"
                FROM "p_927a867d"
                    INNER JOIN "p_8124de4c" ON "p_8124de4c"."entity" = 'return_request_line' AND "p_8124de4c"."key" = CAST("p_927a867d"."line_id" AS VARCHAR(65536))) AS "t"
            LEFT JOIN (SELECT "t1"."due" AS "_ttrp_k_n20_0_due", COUNT("p_7e21ab69"."_ttrp_d_n20_0") AS "_ttrp_n_n20_0"
                FROM (SELECT "p_8124de4c0"."due"
                        FROM "p_927a867d" AS "p_927a867d0"
                            INNER JOIN "p_8124de4c" AS "p_8124de4c0" ON "p_8124de4c0"."entity" = 'return_request_line' AND "p_8124de4c0"."key" = CAST("p_927a867d0"."line_id" AS VARCHAR(65536))
                        GROUP BY "p_8124de4c0"."due") AS "t1"
                    INNER JOIN "p_7e21ab69" ON "t1"."due" < "p_7e21ab69"."_ttrp_d_n20_0" AND "p_7e21ab69"."_ttrp_d_n20_0" <= :as_of
                GROUP BY "t1"."due") AS "t3" ON "t"."due" = "t3"."_ttrp_k_n20_0_due"
        GROUP BY "t"."line_id") AS "t5" ON "p_77af0c22"."line_id" = "t5"."line_id"
