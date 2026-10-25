# release_order

## What this recipe decides

- `release`: approved writer fixture

## What it reads

- `er.entity.order`

## What it does

- `verdict.release` → `release`: `order` = `order_number`. would release order {order}
- `verdict.release` → `audit_state`: `state` = `"open"`

## Parameters

- Price tolerance (`tolerance`) = `0.01` (decimal)
- Confirmation threshold (`confirmation_threshold`) = `50000` (decimal)

## Mode

`per-candidate`
