SELECT "p_478041f1"."order_number"
FROM "p_478041f1"
    INNER JOIN "p_255a207c" ON "p_255a207c"."entity" = 'er.entity.return_request_line' AND "p_255a207c"."key" = '{"line_id":9007199254740993}' AND "p_255a207c"."kind" = 'inbound' AND "p_255a207c"."state" IN ('open', 'overdue', 'reopened')
