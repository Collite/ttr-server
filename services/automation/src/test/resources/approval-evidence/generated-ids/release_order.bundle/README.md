# release_order

## What this recipe decides

- `release`: approved writer fixture

## What it reads

- `er.entity.order`

## What it does

- `verdict.release` → `release`: `order` = `cast(order_number as string)`. would propose {id} for {order} as {state}
- `verdict.release` → `release`: `order` = `cast(order_number as string)`. would propose {id} for {order} as {state}

## Parameters

- Price tolerance (`tolerance`) = `0.01` (decimal)
- Confirmation threshold (`confirmation_threshold`) = `50000` (decimal)

## Mode

`per-candidate`
