SELECT q.line_id, ob.id AS obligation_id, ob.kind, ob.due
FROM   lines q JOIN er.entity.obligation ob ON ob.entity = 'return_request_line' AND ob.key = q.line_id
WHERE  ob.kind IN ('credit_note', 'payment', 'replacement') AND ob.state IN ('open', 'overdue', 'reopened')
