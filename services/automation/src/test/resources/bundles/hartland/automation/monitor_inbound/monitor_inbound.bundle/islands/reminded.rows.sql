SELECT q.line_id, COUNT(v.run) AS reminders
FROM   lines q LEFT JOIN er.entity.monitor_inbound_verdict v ON v.line_id = q.line_id AND v.route = 'remind'
GROUP  BY q.line_id
