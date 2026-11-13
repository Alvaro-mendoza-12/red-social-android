import { fallbackApiJson } from './auth/fallbackAuth'
import { textOf, type GenericRow } from './utils'

export type UbuntuWalletSnapshot = {
  id: string
  userId: string
  balanceDiamonds: number
  balanceDebtDiamonds: number
  balanceCredits: number
  totalSpent: number
}

export type UbuntuVirtualGift = {
  id: string
  name: string
  iconUrl: string
  imageUrl: string
  animationUrl: string
  priceDiamonds: number
  priceCredits: number
  sortOrder: number
}

export type UbuntuGiftTransaction = {
  id: string
  senderId: string
  senderName: string
  receiverId: string
  giftId: string
  giftName: string
  amountDiamonds: number
  liveStreamId: string
  status: string
  createdAt: string
}

export type UbuntuSendGiftReceipt = {
  transactionId: string
  earningId: string
  senderBalanceDiamonds: number
  giftId: string
  giftName: string
  idempotentReplay: boolean
}

export type UbuntuCreatorApplication = {
  id: string
  status: string
  niche: string
  portfolioUrl: string
  payoutCountry: string
  audienceSize: number
  rejectionReason: string
  createdAt: string
}

export type UbuntuCreatorEarning = {
  id: string
  sourceType: string
  amountDiamonds: number
  withdrawableDiamonds: number
  nonWithdrawableDiamonds: number
  gross: number
  fee: number
  net: number
  status: string
  availableAt: string
  createdAt: string
}

export type UbuntuPayoutAccount = {
  provider: string
  externalAccountId: string
  country: string
  currency: string
  status: string
}

export type UbuntuMonetizationDashboard = {
  wallet: UbuntuWalletSnapshot
  giftsCatalog: UbuntuVirtualGift[]
  giftTransactions: UbuntuGiftTransaction[]
  earnings: UbuntuCreatorEarning[]
  application: UbuntuCreatorApplication | null
  payoutAccount: UbuntuPayoutAccount | null
}

function toFiniteNumber(value: unknown, fallback = 0): number {
  const parsed = Number(value ?? fallback)
  return Number.isFinite(parsed) ? parsed : fallback
}

function asRowList(payload: unknown): GenericRow[] {
  if (Array.isArray(payload)) {
    return payload.filter((item): item is GenericRow => Boolean(item && typeof item === 'object'))
  }
  return []
}

function firstRow(payload: unknown): GenericRow | null {
  if (Array.isArray(payload)) {
    const candidate = payload[0]
    return candidate && typeof candidate === 'object' ? (candidate as GenericRow) : null
  }
  if (payload && typeof payload === 'object') {
    return payload as GenericRow
  }
  return null
}

export function mapUbuntuWallet(payload: unknown): UbuntuWalletSnapshot {
  const row = firstRow(payload) ?? {}
  return {
    id: textOf(row, ['id'], ''),
    userId: textOf(row, ['user_id'], ''),
    balanceDiamonds: Math.max(0, Math.trunc(toFiniteNumber(row.balance_diamonds))),
    balanceDebtDiamonds: Math.max(0, Math.trunc(toFiniteNumber(row.balance_debt_diamonds))),
    balanceCredits: Math.max(0, toFiniteNumber(row.balance_credits)),
    totalSpent: Math.max(0, toFiniteNumber(row.total_spent)),
  }
}

export function mapUbuntuVirtualGift(row: GenericRow): UbuntuVirtualGift {
  const priceDiamonds = Math.max(
    1,
    Math.trunc(toFiniteNumber(row.price_diamonds ?? row.price_credits, 1)),
  )
  return {
    id: textOf(row, ['id'], ''),
    name: textOf(row, ['name'], 'Regalo Novvin'),
    iconUrl: textOf(row, ['icon_url'], ''),
    imageUrl: textOf(row, ['image_url', 'icon_url'], ''),
    animationUrl: textOf(row, ['animation_url'], ''),
    priceDiamonds,
    priceCredits: Math.max(0, toFiniteNumber(row.price_credits, priceDiamonds)),
    sortOrder: Math.trunc(toFiniteNumber(row.sort_order, 0)),
  }
}

let fallbackIdCounter = 0

function fallbackRowId(prefix: string): string {
  if (typeof globalThis.crypto?.randomUUID === 'function') {
    return globalThis.crypto.randomUUID()
  }
  fallbackIdCounter += 1
  return `${prefix}-${Date.now()}-${fallbackIdCounter}`
}

export function mapUbuntuGiftTransaction(row: GenericRow): UbuntuGiftTransaction {
  return {
    id: textOf(row, ['id'], fallbackRowId('gift-tx')),
    senderId: textOf(row, ['sender_id'], ''),
    senderName: textOf(row, ['sender_name'], 'Usuario'),
    receiverId: textOf(row, ['receiver_id'], ''),
    giftId: textOf(row, ['gift_id'], ''),
    giftName: textOf(row, ['gift_name'], 'Regalo Novvin'),
    amountDiamonds: Math.max(0, Math.trunc(toFiniteNumber(row.amount_diamonds ?? row.amount_credits))),
    liveStreamId: textOf(row, ['live_stream_id'], ''),
    status: textOf(row, ['status'], 'committed'),
    createdAt: textOf(row, ['created_at'], ''),
  }
}

export function mapUbuntuApplication(row: GenericRow): UbuntuCreatorApplication {
  return {
    id: textOf(row, ['id'], ''),
    status: textOf(row, ['status'], 'pending'),
    niche: textOf(row, ['niche'], ''),
    portfolioUrl: textOf(row, ['portfolio_url'], ''),
    payoutCountry: textOf(row, ['payout_country'], ''),
    audienceSize: Math.max(0, Math.trunc(toFiniteNumber(row.audience_size))),
    rejectionReason: textOf(row, ['rejection_reason'], ''),
    createdAt: textOf(row, ['created_at'], ''),
  }
}

export function mapUbuntuEarning(row: GenericRow): UbuntuCreatorEarning {
  const amountDiamonds = Math.max(
    0,
    Math.trunc(toFiniteNumber(row.amount_diamonds ?? row.gift_face_value_diamonds)),
  )
  const netCredits = toFiniteNumber(row.net_amount_credits ?? row.amount_credits)
  return {
    id: textOf(row, ['id'], fallbackRowId('earning')),
    sourceType: textOf(row, ['source_type'], 'virtual_gift'),
    amountDiamonds,
    withdrawableDiamonds: Math.max(0, Math.trunc(toFiniteNumber(row.withdrawable_amount_diamonds))),
    nonWithdrawableDiamonds: Math.max(
      0,
      Math.trunc(toFiniteNumber(row.non_withdrawable_amount_diamonds)),
    ),
    gross: toFiniteNumber(row.gross_amount_credits, netCredits),
    fee: toFiniteNumber(row.platform_fee_credits, 0),
    net: netCredits,
    status: textOf(row, ['status'], 'pending'),
    availableAt: textOf(row, ['available_at'], ''),
    createdAt: textOf(row, ['created_at'], ''),
  }
}

export function mapUbuntuPayoutAccount(row: GenericRow): UbuntuPayoutAccount {
  return {
    provider: textOf(row, ['provider'], 'manual'),
    externalAccountId: textOf(row, ['external_account_id'], ''),
    country: textOf(row, ['country'], ''),
    currency: textOf(row, ['currency'], 'USD'),
    status: textOf(row, ['status'], 'pending'),
  }
}

export async function fetchUbuntuWallet(expectedAccountId?: string): Promise<UbuntuWalletSnapshot> {
  const payload = await fallbackApiJson<unknown>('/rest/v1/rpc/wallet_get_or_create', {
    method: 'POST',
    expectedAccountId,
    body: JSON.stringify({}),
  })
  return mapUbuntuWallet(payload)
}

export async function fetchUbuntuVirtualGifts(): Promise<UbuntuVirtualGift[]> {
  const rows = await fallbackApiJson<unknown>('/rest/v1/virtual_gifts?select=*')
  return asRowList(rows)
    .map(mapUbuntuVirtualGift)
    .filter((gift) => Boolean(gift.id))
    .sort((left, right) => left.sortOrder - right.sortOrder || left.priceDiamonds - right.priceDiamonds)
}

export async function fetchUbuntuGiftTransactions(
  expectedAccountId?: string,
): Promise<UbuntuGiftTransaction[]> {
  const rows = await fallbackApiJson<unknown>('/rest/v1/gift_transactions?select=*', {
    expectedAccountId,
  })
  return asRowList(rows)
    .map(mapUbuntuGiftTransaction)
    .sort((left, right) => right.createdAt.localeCompare(left.createdAt))
}

export async function sendUbuntuVirtualGift(params: {
  senderId: string
  receiverId: string
  giftId: string
  liveStreamId?: string
  idempotencyKey: string
  isPublic?: boolean
}): Promise<UbuntuSendGiftReceipt> {
  const payload = await fallbackApiJson<GenericRow>('/rest/v1/rpc/send_virtual_gift', {
    method: 'POST',
    expectedAccountId: params.senderId,
    body: JSON.stringify({
      p_sender_id: params.senderId,
      p_receiver_id: params.receiverId,
      p_gift_id: params.giftId,
      p_live_stream_id: params.liveStreamId?.trim() || null,
      p_idempotency_key: params.idempotencyKey,
      p_is_public: params.isPublic ?? true,
      p_message: null,
    }),
  })
  const receipt = firstRow(payload)
  const transactionId = receipt ? textOf(receipt, ['transaction_id'], '') : ''
  if (!receipt || !transactionId) {
    throw new Error('El servidor no devolvió un comprobante válido del regalo.')
  }
  return {
    transactionId,
    earningId: textOf(receipt, ['earning_id'], ''),
    senderBalanceDiamonds: Math.max(
      0,
      Math.trunc(toFiniteNumber(receipt.sender_balance_diamonds, 0)),
    ),
    giftId: textOf(receipt, ['gift_id'], params.giftId),
    giftName: textOf(receipt, ['gift_name'], 'Regalo Novvin'),
    idempotentReplay: receipt.idempotent_replay === true,
  }
}

export async function fetchUbuntuMonetizationDashboard(
  expectedAccountId?: string,
): Promise<UbuntuMonetizationDashboard> {
  const [wallet, giftsCatalog, giftTransactions, applicationRows, payoutRows, earningRows] =
    await Promise.all([
      fetchUbuntuWallet(expectedAccountId),
      fetchUbuntuVirtualGifts().catch(() => []),
      fetchUbuntuGiftTransactions(expectedAccountId).catch(() => []),
      fallbackApiJson<unknown>('/rest/v1/creator_program_applications?select=*', {
        expectedAccountId,
      }).catch(() => []),
      fallbackApiJson<unknown>('/rest/v1/creator_payout_accounts?select=*', {
        expectedAccountId,
      }).catch(() => []),
      fallbackApiJson<unknown>('/rest/v1/creator_earnings?select=*', {
        expectedAccountId,
      }).catch(() => []),
    ])

  const appRow = asRowList(applicationRows)[0]
  const payoutRow = asRowList(payoutRows)[0]
  const earnings = asRowList(earningRows)
    .map(mapUbuntuEarning)
    .sort((left, right) => right.createdAt.localeCompare(left.createdAt))

  return {
    wallet,
    giftsCatalog,
    giftTransactions,
    earnings,
    application: appRow ? mapUbuntuApplication(appRow) : null,
    payoutAccount: payoutRow ? mapUbuntuPayoutAccount(payoutRow) : null,
  }
}

export async function saveUbuntuCreatorApplication(input: {
  userId: string
  hasExisting: boolean
  niche: string
  portfolioUrl: string
  payoutCountry: string
  audienceSize: number
}): Promise<UbuntuCreatorApplication | null> {
  const cleanAudience = Math.max(0, Math.min(1_000_000_000, Math.trunc(input.audienceSize || 0)))
  const cleanCountry = input.payoutCountry.trim().toUpperCase().slice(0, 2) || null
  const cleanNiche = input.niche.trim().slice(0, 120) || null
  const cleanPortfolio = input.portfolioUrl.trim().slice(0, 2000) || null
  const headers = { Prefer: 'return=representation' }

  const payload = input.hasExisting
    ? await fallbackApiJson<unknown>(
        `/rest/v1/creator_program_applications?user_id=eq.${encodeURIComponent(input.userId)}`,
        {
          method: 'PATCH',
          expectedAccountId: input.userId,
          headers,
          body: JSON.stringify({
            niche: cleanNiche,
            portfolio_url: cleanPortfolio,
            payout_country: cleanCountry,
            audience_size: cleanAudience,
            updated_at: new Date().toISOString(),
          }),
        },
      )
    : await fallbackApiJson<unknown>('/rest/v1/creator_program_applications', {
        method: 'POST',
        expectedAccountId: input.userId,
        headers,
        body: JSON.stringify({
          user_id: input.userId,
          status: 'pending',
          niche: cleanNiche,
          portfolio_url: cleanPortfolio,
          payout_country: cleanCountry,
          audience_size: cleanAudience,
        }),
      })

  const saved = firstRow(payload)
  return saved ? mapUbuntuApplication(saved) : null
}

export async function saveUbuntuPayoutAccount(input: {
  userId: string
  hasExisting: boolean
  provider: string
  externalAccountId: string
  country: string
  currency: string
}): Promise<UbuntuPayoutAccount | null> {
  const cleanProvider = ['manual', 'paypal', 'stripe', 'bank'].includes(input.provider.trim().toLowerCase())
    ? input.provider.trim().toLowerCase()
    : 'manual'
  const cleanCountry = input.country.trim().toUpperCase().slice(0, 2) || null
  const cleanCurrency = /^[A-Z]{3}$/.test(input.currency.trim().toUpperCase())
    ? input.currency.trim().toUpperCase()
    : 'USD'
  const cleanReference = input.externalAccountId.trim().slice(0, 255) || null
  const headers = { Prefer: 'return=representation' }

  const payload = input.hasExisting
    ? await fallbackApiJson<unknown>(
        `/rest/v1/creator_payout_accounts?user_id=eq.${encodeURIComponent(input.userId)}`,
        {
          method: 'PATCH',
          expectedAccountId: input.userId,
          headers,
          body: JSON.stringify({
            provider: cleanProvider,
            external_account_id: cleanReference,
            country: cleanCountry,
            currency: cleanCurrency,
            status: 'pending',
            updated_at: new Date().toISOString(),
          }),
        },
      )
    : await fallbackApiJson<unknown>('/rest/v1/creator_payout_accounts', {
        method: 'POST',
        expectedAccountId: input.userId,
        headers,
        body: JSON.stringify({
          user_id: input.userId,
          provider: cleanProvider,
          external_account_id: cleanReference,
          country: cleanCountry,
          currency: cleanCurrency,
          status: 'pending',
        }),
      })

  const saved = firstRow(payload)
  return saved ? mapUbuntuPayoutAccount(saved) : null
}

export function mapUbuntuGiftError(caught: unknown): string {
  const raw = caught instanceof Error ? caught.message : String(caught ?? '')
  if (/INSUFFICIENT_BALANCE/i.test(raw)) {
    return 'Saldo insuficiente. Recarga monedas en Novvin para Android y vuelve a confirmarlo.'
  }
  if (/LIVE_GIFTS_DISABLED/i.test(raw)) {
    return 'El creador desactivó los regalos para este directo. No se descontaron monedas.'
  }
  if (/GIFTS_DISABLED/i.test(raw)) {
    return 'Los regalos están temporalmente desactivados. No se descontaron monedas.'
  }
  if (/LIVE_NOT_ACTIVE/i.test(raw)) {
    return 'Este directo ya terminó o no está activo. No se descontaron monedas.'
  }
  if (/SELF_GIFT_NOT_ALLOWED/i.test(raw)) {
    return 'No puedes enviarte regalos a ti mismo.'
  }
  if (/GIFT_VALUE_LIMIT|GIFT_DAILY_LIMIT/i.test(raw)) {
    return 'Este regalo supera el límite permitido para tu cuenta.'
  }
  if (/RATE_LIMITED/i.test(raw)) {
    return 'Estás enviando regalos demasiado rápido. Espera unos segundos e inténtalo de nuevo.'
  }
  if (/PAYOUT_REFERENCE_UNSAFE/i.test(raw)) {
    return 'La referencia de cuenta de cobro no es válida.'
  }
  return raw.trim() || 'No se pudo completar la operación.'
}
