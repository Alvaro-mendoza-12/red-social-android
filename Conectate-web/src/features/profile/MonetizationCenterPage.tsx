import { useCallback, useEffect, useMemo, useState, type FormEvent } from 'react'
import { Link } from 'react-router-dom'
import { StatusView } from '../../components/StatusView'
import { reportSupabaseServiceRestrictionIfPresent, supabase } from '../../core/supabase'
import { formatDateTime, friendlyError, isMissingTableError, textOf, type GenericRow } from '../../core/utils'
import { useAuth } from '../../core/auth/AuthProvider'

type CreatorApplication = {
  id: string
  status: string
  niche: string
  portfolioUrl: string
  payoutCountry: string
  audienceSize: number
  rejectionReason: string
  createdAt: string
}

type CreatorEarning = {
  id: string
  sourceType: string
  gross: number
  fee: number
  net: number
  status: string
  availableAt: string
  createdAt: string
}

type PayoutAccount = {
  provider: string
  country: string
  currency: string
  status: string
}

type MonetizationSummary = {
  premiumActive: boolean
  walletBalance: number
  giftsReceived: number
  adsImpressions: number
}

function toNumber(value: unknown): number {
  const parsed = Number(value ?? 0)
  return Number.isFinite(parsed) ? parsed : 0
}

function mapApplication(row: GenericRow): CreatorApplication {
  return {
    id: textOf(row, ['id'], ''),
    status: textOf(row, ['status'], 'pending'),
    niche: textOf(row, ['niche'], ''),
    portfolioUrl: textOf(row, ['portfolio_url'], ''),
    payoutCountry: textOf(row, ['payout_country'], ''),
    audienceSize: toNumber(row.audience_size),
    rejectionReason: textOf(row, ['rejection_reason'], ''),
    createdAt: textOf(row, ['created_at'], ''),
  }
}

function mapEarning(row: GenericRow): CreatorEarning {
  return {
    id: textOf(row, ['id'], crypto.randomUUID()),
    sourceType: textOf(row, ['source_type'], 'bonus'),
    gross: toNumber(row.gross_amount_credits),
    fee: toNumber(row.platform_fee_credits),
    net: toNumber(row.net_amount_credits),
    status: textOf(row, ['status'], 'pending'),
    availableAt: textOf(row, ['available_at'], ''),
    createdAt: textOf(row, ['created_at'], ''),
  }
}

export function MonetizationCenterPage() {
  const { user } = useAuth()
  const [application, setApplication] = useState<CreatorApplication | null>(null)
  const [earnings, setEarnings] = useState<CreatorEarning[]>([])
  const [payoutAccount, setPayoutAccount] = useState<PayoutAccount | null>(null)
  const [summary, setSummary] = useState<MonetizationSummary>({
    premiumActive: false,
    walletBalance: 0,
    giftsReceived: 0,
    adsImpressions: 0,
  })
  const [niche, setNiche] = useState('')
  const [portfolioUrl, setPortfolioUrl] = useState('')
  const [payoutCountry, setPayoutCountry] = useState('')
  const [audienceSize, setAudienceSize] = useState('')
  const [loading, setLoading] = useState(true)
  const [saving, setSaving] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [info, setInfo] = useState<string | null>(null)

  const totals = useMemo(() => {
    return earnings.reduce(
      (acc, earning) => ({
        gross: acc.gross + earning.gross,
        fee: acc.fee + earning.fee,
        net: acc.net + earning.net,
        available: acc.available + (earning.status === 'available' ? earning.net : 0),
      }),
      { gross: 0, fee: 0, net: 0, available: 0 },
    )
  }, [earnings])

  const loadCenter = useCallback(async () => {
    if (!supabase || !user) {
      setLoading(false)
      return
    }
    setLoading(true)
    setError(null)
    try {
      const [applicationResult, payoutResult, earningsResult] = await Promise.all([
        supabase
          .from('creator_program_applications')
          .select('*')
          .eq('user_id', user.id)
          .limit(1),
        supabase
          .from('creator_payout_accounts')
          .select('*')
          .eq('user_id', user.id)
          .limit(1),
        supabase
          .from('creator_earnings')
          .select('*')
          .eq('creator_id', user.id)
          .order('created_at', { ascending: false })
          .limit(80),
      ])

      if (applicationResult.error && !isMissingTableError(applicationResult.error)) throw applicationResult.error
      if (payoutResult.error && !isMissingTableError(payoutResult.error)) throw payoutResult.error
      if (earningsResult.error && !isMissingTableError(earningsResult.error)) throw earningsResult.error

      const applicationRow = ((applicationResult.data ?? []) as GenericRow[])[0]
      const nextApplication = applicationRow ? mapApplication(applicationRow) : null
      setApplication(nextApplication)
      setNiche(nextApplication?.niche ?? '')
      setPortfolioUrl(nextApplication?.portfolioUrl ?? '')
      setPayoutCountry(nextApplication?.payoutCountry ?? '')
      setAudienceSize(nextApplication?.audienceSize ? String(nextApplication.audienceSize) : '')

      const payoutRow = ((payoutResult.data ?? []) as GenericRow[])[0]
      setPayoutAccount(
        payoutRow
          ? {
              provider: textOf(payoutRow, ['provider'], 'manual'),
              country: textOf(payoutRow, ['country'], ''),
              currency: textOf(payoutRow, ['currency'], 'USD'),
              status: textOf(payoutRow, ['status'], 'pending'),
            }
          : null,
      )
      setEarnings(((earningsResult.data ?? []) as GenericRow[]).map(mapEarning))

      const [walletResult, giftResult, premiumResult, adResult] = await Promise.all([
        supabase.from('wallets').select('*').eq('user_id', user.id).limit(1),
        supabase.from('gift_transactions').select('id').eq('receiver_id', user.id).limit(1000),
        supabase.from('premium_subscriptions').select('*').eq('user_id', user.id).eq('status', 'active').limit(1),
        supabase.from('ad_impressions').select('id').eq('user_id', user.id).limit(1000),
      ])

      setSummary({
        premiumActive: !premiumResult.error && (premiumResult.data ?? []).length > 0,
        walletBalance: walletResult.error
          ? 0
          : toNumber(((walletResult.data ?? []) as GenericRow[])[0]?.balance_credits),
        giftsReceived: giftResult.error ? 0 : (giftResult.data ?? []).length,
        adsImpressions: adResult.error ? 0 : (adResult.data ?? []).length,
      })
    } catch (caught) {
      reportSupabaseServiceRestrictionIfPresent('monetization-center', caught)
      setError(friendlyError(caught, 'No se pudo cargar monetización'))
    } finally {
      setLoading(false)
    }
  }, [user])

  useEffect(() => {
    void loadCenter()
  }, [loadCenter])

  async function submitApplication(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    if (!supabase || !user || saving) return
    setSaving(true)
    setError(null)
    setInfo(null)
    try {
      const payload = {
        user_id: user.id,
        status: 'pending',
        niche: niche.trim() || null,
        portfolio_url: portfolioUrl.trim() || null,
        payout_country: payoutCountry.trim() || null,
        audience_size: Math.max(0, Math.round(toNumber(audienceSize))),
        updated_at: new Date().toISOString(),
      }
      const { error: upsertError } = await supabase
        .from('creator_program_applications')
        .upsert(payload, { onConflict: 'user_id' })
      if (upsertError) throw upsertError
      setInfo('Solicitud de creador guardada y lista para revisión.')
      await loadCenter()
    } catch (caught) {
      reportSupabaseServiceRestrictionIfPresent('creator-application-save', caught)
      setError(friendlyError(caught, 'No se pudo enviar la solicitud'))
    } finally {
      setSaving(false)
    }
  }

  if (!supabase) return <StatusView title="Supabase no configurado" description="Configura .env para monetización." />
  if (loading) return <StatusView title="Cargando monetización" description="Sincronizando programa de creadores, gifts y premium." />

  return (
    <section className="section-stack">
      <article className="surface-card monetization-hero">
        <div>
          <Link className="inline-link" to="/app/profile">
            ← Volver a perfil
          </Link>
          <h2>Centro de monetización</h2>
          <p>Panel web para premium, gifts, ads, earnings y programa de creadores.</p>
        </div>
        <span className="folder-tag">{application?.status ?? 'sin solicitud'}</span>
      </article>

      {error ? <StatusView title="Monetización no disponible" description={error} /> : null}
      {info ? <article className="surface-card"><p>{info}</p></article> : null}

      <div className="stats-grid stats-grid-wide">
        <article className="surface-card stat-box">
          <strong>{summary.walletBalance.toFixed(0)}</strong>
          <span>Créditos wallet</span>
        </article>
        <article className="surface-card stat-box">
          <strong>{summary.giftsReceived}</strong>
          <span>Gifts recibidos</span>
        </article>
        <article className="surface-card stat-box">
          <strong>{summary.premiumActive ? 'Activo' : 'Libre'}</strong>
          <span>Premium</span>
        </article>
        <article className="surface-card stat-box">
          <strong>{summary.adsImpressions}</strong>
          <span>Ads vistos</span>
        </article>
        <article className="surface-card stat-box">
          <strong>{totals.net.toFixed(2)}</strong>
          <span>Net earnings</span>
        </article>
        <article className="surface-card stat-box">
          <strong>{totals.available.toFixed(2)}</strong>
          <span>Disponible</span>
        </article>
      </div>

      <div className="settings-grid">
        <article className="surface-card section-stack">
          <div>
            <h3>Programa de creadores</h3>
            <p>Solicitud sincronizada con `creator_program_applications` del SQL final.</p>
          </div>
          <form className="composer settings-stack" onSubmit={submitApplication}>
            <input className="text-input" placeholder="Nicho: gaming, música, comedia..." value={niche} onChange={(event) => setNiche(event.target.value)} />
            <input className="text-input" placeholder="Portfolio / enlace principal" value={portfolioUrl} onChange={(event) => setPortfolioUrl(event.target.value)} />
            <input className="text-input" placeholder="País de payout" value={payoutCountry} onChange={(event) => setPayoutCountry(event.target.value)} />
            <input className="text-input" inputMode="numeric" placeholder="Audiencia estimada" value={audienceSize} onChange={(event) => setAudienceSize(event.target.value)} />
            <button className="primary-btn" type="submit" disabled={saving}>
              {saving ? 'Guardando...' : application ? 'Actualizar solicitud' : 'Enviar solicitud'}
            </button>
          </form>
          {application?.rejectionReason ? <small className="error-text">{application.rejectionReason}</small> : null}
        </article>

        <article className="surface-card section-stack">
          <div>
            <h3>Payout</h3>
            <p>Cuenta de pago asociada al creador.</p>
          </div>
          {payoutAccount ? (
            <div className="profile-facts-grid">
              <div className="surface-subcard"><strong>Proveedor</strong><p>{payoutAccount.provider}</p></div>
              <div className="surface-subcard"><strong>Estado</strong><p>{payoutAccount.status}</p></div>
              <div className="surface-subcard"><strong>País</strong><p>{payoutAccount.country || 'No definido'}</p></div>
              <div className="surface-subcard"><strong>Moneda</strong><p>{payoutAccount.currency}</p></div>
            </div>
          ) : (
            <p>No hay payout verificado todavía. Configúralo cuando el backend de pagos esté listo.</p>
          )}
        </article>
      </div>

      <article className="surface-card section-stack">
        <div>
          <h3>Earnings recientes</h3>
          <p>Gifts, ads, premium y bonus acumulados.</p>
        </div>
        {earnings.length === 0 ? (
          <p>Aún no hay movimientos de monetización.</p>
        ) : (
          <div className="list-stack">
            {earnings.map((earning) => (
              <div key={earning.id} className="surface-subcard row-between">
                <div>
                  <strong>{earning.sourceType}</strong>
                  <p>Gross {earning.gross.toFixed(2)} · fee {earning.fee.toFixed(2)} · net {earning.net.toFixed(2)}</p>
                  <small>{formatDateTime(earning.createdAt)}</small>
                </div>
                <span className="folder-tag">{earning.status}</span>
              </div>
            ))}
          </div>
        )}
      </article>
    </section>
  )
}
