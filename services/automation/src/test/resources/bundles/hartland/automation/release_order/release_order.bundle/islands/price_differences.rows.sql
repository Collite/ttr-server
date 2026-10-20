WITH l AS (
    SELECT s.order_number, s.line_no, s.item, s.unit_price, o.customer, o.channel, o.placed_date
    FROM   orders o JOIN er.entity.sales_line s ON s.order_number = o.order_number
),
applicable AS (
    SELECT l.order_number, l.line_no, COALESCE(cp.price, pl.price) AS price
    FROM   l
    LEFT JOIN er.entity.customer_price cp ON cp.item = l.item AND cp.customer = l.customer
                                         AND cp.valid_from <= l.placed_date AND (cp.valid_to IS NULL OR cp.valid_to >= l.placed_date)
    LEFT JOIN er.entity.price_list     pl ON pl.item = l.item AND pl.channel = l.channel
                                         AND pl.valid_from <= l.placed_date AND (pl.valid_to IS NULL OR pl.valid_to >= l.placed_date)
)
SELECT l.order_number, l.line_no, l.item, l.unit_price, a.price AS list_price
FROM   l JOIN applicable a ON a.order_number = l.order_number AND a.line_no = l.line_no
WHERE  a.price IS NULL OR ABS(l.unit_price - a.price) > :tolerance
