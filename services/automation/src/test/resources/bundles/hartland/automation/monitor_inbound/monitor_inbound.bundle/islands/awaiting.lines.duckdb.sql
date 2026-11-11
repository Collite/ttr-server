SELECT "p_da15c438"."line_id", "p_da15c438"."request_number", "p_da15c438"."item", "p_da15c438"."quantity", "p_da15c438"."way", "p_da15c438"."customer0" AS "customer", "p_da15c438"."email" AS "customer_email", "p_7d39f2a5"."id" AS "obligation_id", "p_7d39f2a5"."due", "p_7d39f2a5"."state" AS "obligation_state"
FROM "p_da15c438"
    INNER JOIN "p_7d39f2a5" ON "p_7d39f2a5"."entity" = 'return_request_line' AND "p_7d39f2a5"."key" = CAST("p_da15c438"."line_id" AS VARCHAR(65536)) AND "p_7d39f2a5"."kind" = 'inbound' AND "p_7d39f2a5"."state" IN ('open', 'overdue', 'reopened')
