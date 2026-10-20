SELECT q.line_id,
       COALESCE(SUM(CASE WHEN rc.condition = 'ok' THEN rc.quantity ELSE 0 END), 0) AS qty_ok,
       COALESCE(SUM(CASE WHEN rc.condition <> 'ok' THEN rc.quantity ELSE 0 END), 0) AS qty_bad,
       MAX(rc.received_date) AS last_received
FROM   lines q LEFT JOIN er.entity.return_receipt rc ON rc.line_id = q.line_id
GROUP  BY q.line_id
