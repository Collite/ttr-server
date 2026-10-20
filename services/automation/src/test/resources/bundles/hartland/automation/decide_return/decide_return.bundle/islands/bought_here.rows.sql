SELECT q.line_id, o.delivered_date,
       CASE WHEN o.delivered_date >= :as_of - :return_window THEN 1 ELSE 0 END AS in_window,
       CASE WHEN s.item IS NULL THEN 0 ELSE 1 END AS item_on_order
FROM   lines q
LEFT JOIN er.entity.order o      ON o.order_number = q.order_number AND o.customer = q.customer
LEFT JOIN er.entity.sales_line s ON s.order_number = q.order_number AND s.item = q.item
