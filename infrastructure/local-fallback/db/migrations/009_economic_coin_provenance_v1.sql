-- Proveniencia económica de monedas y asignación exacta por regalo.
-- Idempotente: las filas históricas se insertan una sola vez y nunca se
-- sobrescribe el estado vivo de un lote ya consumido o revertido.

CREATE UNIQUE INDEX IF NOT EXISTS idx_fallback_wallet_coin_lot_source
ON fallback_compat_rows (
  (lower(row->>'user_id')),
  (upper(row->>'source_type')),
  (lower(row->>'source_reference'))
)
WHERE table_name = 'wallet_coin_lots'
  AND row ? 'user_id'
  AND row ? 'source_type'
  AND row ? 'source_reference';

CREATE UNIQUE INDEX IF NOT EXISTS idx_fallback_gift_coin_allocation
ON fallback_compat_rows (
  (lower(row->>'gift_transaction_id')),
  (lower(row->>'lot_id'))
)
WHERE table_name = 'gift_coin_allocations'
  AND row ? 'gift_transaction_id'
  AND row ? 'lot_id';

CREATE UNIQUE INDEX IF NOT EXISTS idx_fallback_economic_reversal_reference
ON fallback_compat_rows ((lower(row->>'reference_id')))
WHERE table_name = 'economic_reversals'
  AND row ? 'reference_id';

CREATE OR REPLACE FUNCTION fallback_jsonb_nonnegative_bigint(document jsonb, field_name text)
RETURNS bigint
LANGUAGE sql
IMMUTABLE
AS $$
  SELECT CASE
    WHEN coalesce(document->>field_name, '') ~ '^[0-9]+$'
      THEN greatest((document->>field_name)::bigint, 0)
    ELSE 0
  END
$$;

CREATE OR REPLACE FUNCTION fallback_validate_economic_compat_row()
RETURNS trigger
LANGUAGE plpgsql
AS $$
DECLARE
  source_type_value text;
  original_amount bigint;
  available_amount bigint;
  spent_amount bigint;
  reversed_amount bigint;
BEGIN
  IF NEW.table_name = 'wallet_coin_lots' THEN
    source_type_value := upper(coalesce(NEW.row->>'source_type', ''));
    IF source_type_value NOT IN ('PURCHASED', 'PROMOTIONAL', 'ADJUSTMENT') THEN
      RAISE EXCEPTION 'invalid wallet coin source type';
    END IF;
    IF coalesce(NEW.row->>'user_id', '') = '' OR coalesce(NEW.row->>'source_reference', '') = '' THEN
      RAISE EXCEPTION 'wallet coin lot requires user and source reference';
    END IF;
    original_amount := coalesce((NEW.row->>'original_amount_diamonds')::bigint, 0);
    available_amount := coalesce((NEW.row->>'available_amount_diamonds')::bigint, 0);
    spent_amount := coalesce((NEW.row->>'spent_amount_diamonds')::bigint, 0);
    reversed_amount := coalesce((NEW.row->>'reversed_amount_diamonds')::bigint, 0);
    IF original_amount < 0 OR available_amount < 0 OR spent_amount < 0 OR reversed_amount < 0
       OR available_amount > original_amount OR spent_amount > original_amount
       OR reversed_amount > original_amount THEN
      RAISE EXCEPTION 'invalid wallet coin lot amounts';
    END IF;
  ELSIF NEW.table_name = 'gift_coin_allocations' THEN
    source_type_value := upper(coalesce(NEW.row->>'source_type', ''));
    IF source_type_value NOT IN ('PURCHASED', 'PROMOTIONAL', 'ADJUSTMENT')
       OR coalesce(NEW.row->>'gift_transaction_id', '') = ''
       OR coalesce(NEW.row->>'lot_id', '') = ''
       OR coalesce((NEW.row->>'amount_diamonds')::bigint, 0) <= 0
       OR coalesce((NEW.row->>'reversed_amount_diamonds')::bigint, 0) < 0
       OR coalesce((NEW.row->>'reversed_amount_diamonds')::bigint, 0)
          > coalesce((NEW.row->>'amount_diamonds')::bigint, 0) THEN
      RAISE EXCEPTION 'invalid gift coin allocation';
    END IF;
  ELSIF NEW.table_name = 'economic_reversals'
        AND coalesce(NEW.row->>'reference_id', '') = '' THEN
    RAISE EXCEPTION 'economic reversal requires reference';
  END IF;
  RETURN NEW;
END;
$$;

DROP TRIGGER IF EXISTS trg_fallback_validate_economic_compat_row ON fallback_compat_rows;
CREATE TRIGGER trg_fallback_validate_economic_compat_row
BEFORE INSERT OR UPDATE ON fallback_compat_rows
FOR EACH ROW
WHEN (NEW.table_name IN ('wallet_coin_lots', 'gift_coin_allocations', 'economic_reversals'))
EXECUTE FUNCTION fallback_validate_economic_compat_row();

-- Compras históricas verificadas: cada token acreditado se convierte en un
-- lote PURCHASED. La clave privada/token nunca se persiste aquí; se reutiliza
-- exclusivamente el hash ya existente y el id interno de la compra.
INSERT INTO fallback_compat_rows(table_name, row_id, row, created_at, updated_at)
SELECT
  'wallet_coin_lots',
  'purchase:' || purchase.row_id,
  jsonb_build_object(
    'id', 'purchase:' || purchase.row_id,
    'user_id', purchase.row->>'user_id',
    'source_type', 'PURCHASED',
    'source_reference', purchase.row_id,
    'purchase_id', purchase.row_id,
    'product_id', purchase.row->>'product_id',
    'original_amount_diamonds', CASE
      WHEN purchase.row ? 'credited_coins' THEN fallback_jsonb_nonnegative_bigint(purchase.row, 'credited_coins')
      ELSE greatest(
        fallback_jsonb_nonnegative_bigint(purchase.row, 'coins')
          - fallback_jsonb_nonnegative_bigint(purchase.row, 'debt_repaid'),
        0
      ) END,
    'available_amount_diamonds', CASE
      WHEN lower(coalesce(purchase.row->>'status', '')) = 'credited' THEN CASE
        WHEN purchase.row ? 'credited_coins' THEN fallback_jsonb_nonnegative_bigint(purchase.row, 'credited_coins')
        ELSE greatest(
          fallback_jsonb_nonnegative_bigint(purchase.row, 'coins')
            - fallback_jsonb_nonnegative_bigint(purchase.row, 'debt_repaid'),
          0
        ) END
      ELSE 0 END,
    'spent_amount_diamonds', 0,
    'reversed_amount_diamonds', CASE
      WHEN lower(coalesce(purchase.row->>'status', '')) IN ('refunded', 'reversed') THEN CASE
        WHEN purchase.row ? 'credited_coins' THEN fallback_jsonb_nonnegative_bigint(purchase.row, 'credited_coins')
        ELSE greatest(
          fallback_jsonb_nonnegative_bigint(purchase.row, 'coins')
            - fallback_jsonb_nonnegative_bigint(purchase.row, 'debt_repaid'),
          0
        ) END
      ELSE 0 END,
    'status', CASE
      WHEN lower(coalesce(purchase.row->>'status', '')) IN ('refunded', 'reversed') THEN 'reversed'
      ELSE 'active' END,
    'created_at', coalesce(purchase.row->>'purchased_at', purchase.created_at::text),
    'updated_at', purchase.updated_at::text
  ),
  purchase.created_at,
  purchase.updated_at
FROM fallback_compat_rows purchase
WHERE purchase.table_name = 'billing_purchases'
  AND lower(coalesce(purchase.row->>'status', '')) IN ('credited', 'refunded', 'reversed')
  AND coalesce(purchase.row->>'user_id', '') <> ''
ON CONFLICT (table_name, row_id) DO NOTHING;

-- Cualquier saldo histórico que no pueda atribuirse a una compra verificada
-- se clasifica conservadoramente como ADJUSTMENT y nunca genera retiro.
WITH wallets AS (
  SELECT
    wallet.row_id,
    wallet.row,
    wallet.created_at,
    wallet.updated_at,
    lower(coalesce(wallet.row->>'user_id', wallet.row_id)) AS user_key,
    fallback_jsonb_nonnegative_bigint(wallet.row, 'balance_diamonds') AS balance
  FROM fallback_compat_rows wallet
  WHERE wallet.table_name = 'wallets'
), attributed AS (
  SELECT
    lower(lot.row->>'user_id') AS user_key,
    sum(fallback_jsonb_nonnegative_bigint(lot.row, 'available_amount_diamonds')) AS available
  FROM fallback_compat_rows lot
  WHERE lot.table_name = 'wallet_coin_lots'
  GROUP BY lower(lot.row->>'user_id')
)
INSERT INTO fallback_compat_rows(table_name, row_id, row, created_at, updated_at)
SELECT
  'wallet_coin_lots',
  'adjustment:' || wallets.row_id,
  jsonb_build_object(
    'id', 'adjustment:' || wallets.row_id,
    'user_id', coalesce(wallets.row->>'user_id', wallets.row_id),
    'source_type', 'ADJUSTMENT',
    'source_reference', 'legacy-wallet:' || coalesce(wallets.row->>'user_id', wallets.row_id),
    'original_amount_diamonds', wallets.balance - coalesce(attributed.available, 0),
    'available_amount_diamonds', wallets.balance - coalesce(attributed.available, 0),
    'spent_amount_diamonds', 0,
    'reversed_amount_diamonds', 0,
    'status', 'active',
    'created_at', wallets.created_at::text,
    'updated_at', wallets.updated_at::text
  ),
  wallets.created_at,
  wallets.updated_at
FROM wallets
LEFT JOIN attributed USING (user_key)
WHERE wallets.balance > coalesce(attributed.available, 0)
ON CONFLICT (table_name, row_id) DO NOTHING;
