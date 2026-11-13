import { useCallback, useEffect, useMemo, useState, type FormEvent } from 'react'
import { Link } from 'react-router-dom'
import { StatusView } from '../../components/StatusView'
import { reportSupabaseServiceRestrictionIfPresent, supabase } from '../../core/supabase'
import { formatDateTime, friendlyError, isMissingTableError, textOf, type GenericRow } from '../../core/utils'
import { useAuth } from '../../core/auth/AuthProvider'
import {
  fetchUbuntuMonetizationDashboard,
  mapUbuntuGiftError,
  saveUbuntuCreatorApplication,
  saveUbuntuPayoutAccount,
  type UbuntuCreatorApplication,
  type UbuntuCreatorEarning,
  type UbuntuGiftTransaction,
  type UbuntuPayoutAccount,
  type UbuntuVirtualGift,
  type UbuntuWalletSnapshot,
} from '../../core/ubuntuMonetization'

type MonetizationSummary = {
  premiumActive: boolean
  walletBalance: number
  walletDiamonds: number
  walletDebtDiamonds: number
  giftsReceived: number
  giftsSent: number
  adsImpressions: number
}

function toNumber(value: unknown): number {
  const parsed = Number(value ?? 0)
  return Number.isFinite(parsed) ? parsed : 0
}

function mapSupabaseApplication(row: GenericRow): UbuntuCreatorApplication {
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

function mapSupabaseEarning(row: GenericRow): UbuntuCreatorEarning {
  return {
    id: textOf(row, ['id'], crypto.randomUUID()),
    sourceType: textOf(row, ['source_type'], 'bonus'),
    amountDiamonds: Math.max(0, Math.trunc(toNumber(row.amount_diamonds ?? row.gift_face_value_diamonds))),
    withdrawableDiamonds: Math.max(0, Math.trunc(toNumber(row.withdrawable_amount_diamonds))),
    nonWithdrawableDiamonds: Math.max(0, Math.trunc(toNumber(row.non_withdrawable_amount_diamonds))),
    gross: toNumber(row.gross_amount_credits),
    fee: toNumber(row.platform_fee_credits),
    net: toNumber(row.net_amount_credits),
    status: textOf(row, ['status'], 'pending'),
    availableAt: textOf(row, ['available_at'], ''),
    createdAt: textOf(row, ['created_at'], ''),
  }
}

export function MonetizationCenterPage() {
  const { user, isFallbackSession } = useAuth()
  const userId = user?.id?.trim() ?? ''
  const useUbuntuBackend = !supabase && Boolean(isFallbackSession)
  const localFallbackId =
    typeof user?.user_metadata?.local_fallback_id === 'string'
      ? user.user_metadata.local_fallback_id.trim()
      : ''

  const [application, setApplication] = useState<UbuntuCreatorApplication | null>(null)
  const [earnings, setEarnings] = useState<UbuntuCreatorEarning[]>([])
  const [payoutAccount, setPayoutAccount] = useState<UbuntuPayoutAccount | null>(null)
  const [giftsCatalog, setGiftsCatalog] = useState<UbuntuVirtualGift[]>([])
  const [giftHistory, setGiftHistory] = useState<UbuntuGiftTransaction[]>([])
  const [summary, setSummary] = useState<MonetizationSummary>({
    premiumActive: false,
    walletBalance: 0,
    walletDiamonds: 0,
    walletDebtDiamonds: 0,
    giftsReceived: 0,
    giftsSent: 0,
    adsImpressions: 0,
  })
  const [niche, setNiche] = useState('')
  const [portfolioUrl, setPortfolioUrl] = useState('')
  const [payoutCountry, setPayoutCountry] = useState('')
  const [audienceSize, setAudienceSize] = useState('')

  const [payoutProvider, setPayoutProvider] = useState('manual')
  const [payoutExternalId, setPayoutExternalId] = useState('')
  const [payoutAccountCountry, setPayoutAccountCountry] = useState('')
  const [payoutCurrency, setPayoutCurrency] = useState('USD')

  const [loading, setLoading] = useState(true)
  const [saving, setSaving] = useState(false)
  const [savingPayout, setSavingPayout] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [info, setInfo] = useState<string | null>(null)

  const ownsAccountId = useCallback(
    (candidate: string) => {
      const normalized = candidate.trim().toLowerCase()
      if (!normalized) return false
      return (
        normalized === (user?.id ?? '').trim().toLowerCase() ||
        (Boolean(localFallbackId) && normalized === localFallbackId.toLowerCase())
      )
    },
    [localFallbackId, user?.id],
  )

  const totals = useMemo(() => {
    return earnings.reduce(
      (acc, earning) => ({
        gross: acc.gross + earning.gross,
        fee: acc.fee + earning.fee,
        net: acc.net + earning.net,
        available: acc.available + (earning.status === 'available' ? earning.net : 0),
        diamonds: acc.diamonds + earning.amountDiamonds,
        withdrawableDiamonds: acc.withdrawableDiamonds + earning.withdrawableDiamonds,
      }),
      { gross: 0, fee: 0, net: 0, available: 0, diamonds: 0, withdrawableDiamonds: 0 },
    )
  }, [earnings])

  const loadCenter = useCallback(async () => {
    if (!userId || (!supabase && !useUbuntuBackend)) {
      setLoading(false)
      return
    }
    setLoading(true)
    setError(null)
    try {
      if (useUbuntuBackend) {
        const dashboard = await fetchUbuntuMonetizationDashboard(userId)
        const nextApplication = dashboard.application
        setApplication(nextApplication)
        setNiche(nextApplication?.niche ?? '')
        setPortfolioUrl(nextApplication?.portfolioUrl ?? '')
        setPayoutCountry(nextApplication?.payoutCountry ?? '')
        setAudienceSize(nextApplication?.audienceSize ? String(nextApplication.audienceSize) : '')

        const nextPayout = dashboard.payoutAccount
        setPayoutAccount(nextPayout)
        if (nextPayout) {
          setPayoutProvider(nextPayout.provider || 'manual')
          setPayoutExternalId(nextPayout.externalAccountId || '')
          setPayoutAccountCountry(nextPayout.country || '')
          setPayoutCurrency(nextPayout.currency || 'USD')
        }

        setEarnings(dashboard.earnings)
        setGiftsCatalog(dashboard.giftsCatalog)
        setGiftHistory(dashboard.giftTransactions)

        const receivedCount = dashboard.giftTransactions.filter((tx) => ownsAccountId(tx.receiverId)).length
        const sentCount = dashboard.giftTransactions.filter((tx) => ownsAccountId(tx.senderId)).length
        const wallet: UbuntuWalletSnapshot = dashboard.wallet
        setSummary({
          premiumActive: false,
          walletBalance: wallet.balanceCredits,
          walletDiamonds: wallet.balanceDiamonds,
          walletDebtDiamonds: wallet.balanceDebtDiamonds,
          giftsReceived: receivedCount,
          giftsSent: sentCount,
          adsImpressions: 0,
        })
        return
      }

      const client = supabase!
      const [applicationResult, payoutResult, earningsResult] = await Promise.all([
        client.from('creator_program_applications').select('*').eq('user_id', userId).limit(1),
        client.from('creator_payout_accounts').select('*').eq('user_id', userId).limit(1),
        client
          .from('creator_earnings')
          .select('*')
          .eq('creator_id', userId)
          .order('created_at', { ascending: false })
          .limit(80),
      ])

      if (applicationResult.error && !isMissingTableError(applicationResult.error)) throw applicationResult.error
      if (payoutResult.error && !isMissingTableError(payoutResult.error)) throw payoutResult.error
      if (earningsResult.error && !isMissingTableError(earningsResult.error)) throw earningsResult.error

      const applicationRow = ((applicationResult.data ?? []) as GenericRow[])[0]
      const nextApplication = applicationRow ? mapSupabaseApplication(applicationRow) : null
      setApplication(nextApplication)
      setNiche(nextApplication?.niche ?? '')
      setPortfolioUrl(nextApplication?.portfolioUrl ?? '')
      setPayoutCountry(nextApplication?.payoutCountry ?? '')
      setAudienceSize(nextApplication?.audienceSize ? String(nextApplication.audienceSize) : '')

      const payoutRow = ((payoutResult.data ?? []) as GenericRow[])[0]
      const nextPayout = payoutRow
        ? {
            provider: textOf(payoutRow, ['provider'], 'manual'),
            externalAccountId: textOf(payoutRow, ['external_account_id'], ''),
            country: textOf(payoutRow, ['country'], ''),
            currency: textOf(payoutRow, ['currency'], 'USD'),
            status: textOf(payoutRow, ['status'], 'pending'),
          }
        : null
      setPayoutAccount(nextPayout)
      if (nextPayout) {
        setPayoutProvider(nextPayout.provider || 'manual')
        setPayoutExternalId(nextPayout.externalAccountId || '')
        setPayoutAccountCountry(nextPayout.country || '')
        setPayoutCurrency(nextPayout.currency || 'USD')
      }
      setEarnings(((earningsResult.data ?? []) as GenericRow[]).map(mapSupabaseEarning))

      const [walletResult, giftResult, premiumResult, adResult] = await Promise.all([
        client.from('wallets').select('*').eq('user_id', userId).limit(1),
        client.from('gift_transactions').select('id').eq('receiver_id', userId).limit(1000),
        client.from('premium_subscriptions').select('*').eq('user_id', userId).eq('status', 'active').limit(1),
        client.from('ad_impressions').select('id').eq('user_id', userId).limit(1000),
      ])

      const walletRow = ((walletResult.data ?? []) as GenericRow[])[0]
      setSummary({
        premiumActive: !premiumResult.error && (premiumResult.data ?? []).length > 0,
        walletBalance: walletResult.error ? 0 : toNumber(walletRow?.balance_credits),
        walletDiamonds: walletResult.error ? 0 : Math.max(0, Math.trunc(toNumber(walletRow?.balance_diamonds))),
        walletDebtDiamonds: walletResult.error
          ? 0
          : Math.max(0, Math.trunc(toNumber(walletRow?.balance_debt_diamonds))),
        giftsReceived: giftResult.error ? 0 : (giftResult.data ?? []).length,
        giftsSent: 0,
        adsImpressions: adResult.error ? 0 : (adResult.data ?? []).length,
      })
    } catch (caught) {
      reportSupabaseServiceRestrictionIfPresent('monetization-center', caught)
      setError(friendlyError(caught, 'No se pudo cargar monetización'))
    } finally {
      setLoading(false)
    }
  }, [ownsAccountId, useUbuntuBackend, userId])

  useEffect(() => {
    void loadCenter()
  }, [loadCenter])

  async function submitApplication(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    if (!user || saving || (!supabase && !useUbuntuBackend)) return
    const cleanCountry = payoutCountry.trim().toUpperCase()
    if (cleanCountry && !/^[A-Z]{2}$/.test(cleanCountry)) {
      setError('El país de cobro debe tener exactamente 2 letras ISO (por ejemplo, PE, ES o US).')
      return
    }
    setSaving(true)
    setError(null)
    setInfo(null)
    try {
      if (useUbuntuBackend) {
        const saved = await saveUbuntuCreatorApplication({
          userId: user.id,
          hasExisting: Boolean(application),
          niche,
          portfolioUrl,
          payoutCountry: cleanCountry,
          audienceSize: Math.max(0, Math.round(toNumber(audienceSize))),
        })
        if (saved) setApplication(saved)
        setInfo('Solicitud de creador guardada y lista para revisión.')
        await loadCenter()
        return
      }

      const payload = {
        user_id: user.id,
        status: 'pending',
        niche: niche.trim() || null,
        portfolio_url: portfolioUrl.trim() || null,
        payout_country: cleanCountry || null,
        audience_size: Math.max(0, Math.round(toNumber(audienceSize))),
        updated_at: new Date().toISOString(),
      }
      const { error: upsertError } = await supabase!
        .from('creator_program_applications')
        .upsert(payload, { onConflict: 'user_id' })
      if (upsertError) throw upsertError
      setInfo('Solicitud de creador guardada y lista para revisión.')
      await loadCenter()
    } catch (caught) {
      reportSupabaseServiceRestrictionIfPresent('creator-application-save', caught)
      setError(mapUbuntuGiftError(caught) || friendlyError(caught, 'No se pudo enviar la solicitud'))
    } finally {
      setSaving(false)
    }
  }

  async function submitPayoutAccount(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    if (!user || savingPayout || !useUbuntuBackend) return
    const cleanCountry = payoutAccountCountry.trim().toUpperCase()
    const cleanCurrency = payoutCurrency.trim().toUpperCase()
    if (cleanCountry && !/^[A-Z]{2}$/.test(cleanCountry)) {
      setError('El país de la cuenta debe usar código ISO de 2 letras (ej. PE, MX, US).')
      return
    }
    if (!/^[A-Z]{3}$/.test(cleanCurrency)) {
      setError('La moneda debe usar código ISO de 3 letras (por ejemplo, USD).')
      return
    }
    setSavingPayout(true)
    setError(null)
    setInfo(null)
    try {
      const saved = await saveUbuntuPayoutAccount({
        userId: user.id,
        hasExisting: Boolean(payoutAccount),
        provider: payoutProvider,
        externalAccountId: payoutExternalId,
        country: cleanCountry,
        currency: cleanCurrency,
      })
      if (saved) setPayoutAccount(saved)
      setInfo('Cuenta de cobro guardada en revisión (los retiros siguen pendientes de habilitación).')
      await loadCenter()
    } catch (caught) {
      setError(mapUbuntuGiftError(caught) || friendlyError(caught, 'No se pudo guardar la cuenta de cobro'))
    } finally {
      setSavingPayout(false)
    }
  }

  if (!supabase && !useUbuntuBackend) {
    return (
      <StatusView
        title="Monetización no disponible"
        description="Inicia sesión con el servidor de producción para consultar tus monedas, regalos recibidos y el programa de creadores. Puedes volver a tu perfil o revisar la ayuda de Novvin."
        action={
          <div className="row-actions">
            <Link className="primary-btn" to="/app/profile">
              Volver a mi perfil
            </Link>
            <Link className="ghost-btn" to="/app/help">
              Consultar ayuda
            </Link>
          </div>
        }
      />
    )
  }
  if (loading) {
    return (
      <StatusView
        title="Cargando monetización"
        description="Sincronizando billetera de monedas, regalos y programa de creadores."
      />
    )
  }

  const applicationLocked = application?.status === 'approved'

  return (
    <section className="section-stack">
      <article className="surface-card monetization-hero">
        <div>
          <Link className="inline-link" to="/app/profile">
            ← Volver a perfil
          </Link>
          <h2>Centro de monetización y regalos</h2>
          <p>Consulta tu saldo de monedas, historial de regalos, ganancias y solicitud de creador. Las recargas de monedas se realizan mediante Google Play en Novvin para Android.</p>
        </div>
        <span className="folder-tag">{application?.status ?? 'sin solicitud'}</span>
      </article>

      {error ? <StatusView title="Aviso de monetización" description={error} /> : null}
      {info ? (
        <article className="surface-card" role="status">
          <p>{info}</p>
        </article>
      ) : null}

      <div className="stats-grid stats-grid-wide">
        <article className="surface-card stat-box">
          <strong>{summary.walletDiamonds.toLocaleString('es')}</strong>
          <span>Monedas disponibles</span>
        </article>
        <article className="surface-card stat-box">
          <strong>{summary.giftsReceived}</strong>
          <span>Regalos recibidos</span>
        </article>
        <article className="surface-card stat-box">
          <strong>{summary.giftsSent}</strong>
          <span>Regalos enviados</span>
        </article>
        <article className="surface-card stat-box">
          <strong>{totals.diamonds.toLocaleString('es')}</strong>
          <span>Monedas generadas</span>
        </article>
        <article className="surface-card stat-box">
          <strong>{totals.withdrawableDiamonds.toLocaleString('es')}</strong>
          <span>Elegibles (pendientes de retiro)</span>
        </article>
        <article className="surface-card stat-box">
          <strong>{summary.walletDebtDiamonds > 0 ? summary.walletDebtDiamonds : summary.walletBalance.toFixed(0)}</strong>
          <span>{summary.walletDebtDiamonds > 0 ? 'Deuda por reembolso' : 'Créditos'}</span>
        </article>
      </div>

      <div className="settings-grid">
        <article className="surface-card section-stack">
          <div>
            <h3>Programa de creadores</h3>
            <p>Estado y datos de tu solicitud en Novvin.</p>
          </div>
          <form className="composer settings-stack" onSubmit={submitApplication}>
            <label className="settings-field">
              <span>Nicho o categoría</span>
              <input
                className="text-input"
                placeholder="Ej. gaming, música, educación..."
                value={niche}
                disabled={saving || applicationLocked}
                onChange={(event) => setNiche(event.target.value)}
              />
            </label>
            <label className="settings-field">
              <span>Enlace o portafolio</span>
              <input
                className="text-input"
                placeholder="https://..."
                value={portfolioUrl}
                disabled={saving || applicationLocked}
                onChange={(event) => setPortfolioUrl(event.target.value)}
              />
            </label>
            <label className="settings-field">
              <span>País (código ISO de 2 letras)</span>
              <input
                className="text-input"
                maxLength={2}
                placeholder="PE"
                value={payoutCountry}
                disabled={saving || applicationLocked}
                onChange={(event) => setPayoutCountry(event.target.value.toUpperCase())}
              />
            </label>
            <label className="settings-field">
              <span>Audiencia estimada</span>
              <input
                className="text-input"
                inputMode="numeric"
                placeholder="1000"
                value={audienceSize}
                disabled={saving || applicationLocked}
                onChange={(event) => setAudienceSize(event.target.value)}
              />
            </label>
            <button className="primary-btn" type="submit" disabled={saving || applicationLocked}>
              {applicationLocked
                ? 'Solicitud aprobada'
                : saving
                  ? 'Guardando...'
                  : application
                    ? 'Actualizar solicitud'
                    : 'Enviar solicitud'}
            </button>
          </form>
          {application?.rejectionReason ? <small className="error-text">{application.rejectionReason}</small> : null}
        </article>

        <article className="surface-card section-stack">
          <div>
            <h3>Cuenta y retiros de creador</h3>
            <p>La ejecución de retiros permanece pendiente de habilitación mientras continúan las validaciones de pagos.</p>
          </div>
          {payoutAccount ? (
            <div className="profile-facts-grid">
              <div className="surface-subcard"><strong>Método</strong><p>{payoutAccount.provider}</p></div>
              <div className="surface-subcard"><strong>Estado</strong><p>{payoutAccount.status}</p></div>
              <div className="surface-subcard"><strong>País</strong><p>{payoutAccount.country || 'No definido'}</p></div>
              <div className="surface-subcard"><strong>Moneda</strong><p>{payoutAccount.currency}</p></div>
            </div>
          ) : (
            <p>Aún no has guardado una cuenta de referencia.</p>
          )}
          {useUbuntuBackend ? (
            <form className="composer settings-stack" onSubmit={submitPayoutAccount}>
              <label className="settings-field">
                <span>Proveedor</span>
                <select
                  className="text-input"
                  value={payoutProvider}
                  disabled={savingPayout}
                  onChange={(event) => setPayoutProvider(event.target.value)}
                >
                  <option value="manual">Revisión manual</option>
                  <option value="paypal">PayPal</option>
                  <option value="stripe">Stripe</option>
                  <option value="bank">Banco</option>
                </select>
              </label>
              <label className="settings-field">
                <span>Referencia o correo de cobro</span>
                <input
                  className="text-input"
                  placeholder="creador@ejemplo.com"
                  value={payoutExternalId}
                  disabled={savingPayout}
                  onChange={(event) => setPayoutExternalId(event.target.value)}
                />
              </label>
              <label className="settings-field">
                <span>País (2 letras)</span>
                <input
                  className="text-input"
                  maxLength={2}
                  placeholder="PE"
                  value={payoutAccountCountry}
                  disabled={savingPayout}
                  onChange={(event) => setPayoutAccountCountry(event.target.value.toUpperCase())}
                />
              </label>
              <label className="settings-field">
                <span>Moneda (3 letras)</span>
                <input
                  className="text-input"
                  maxLength={3}
                  placeholder="USD"
                  value={payoutCurrency}
                  disabled={savingPayout}
                  onChange={(event) => setPayoutCurrency(event.target.value.toUpperCase())}
                />
              </label>
              <button className="ghost-btn" type="submit" disabled={savingPayout}>
                {savingPayout ? 'Guardando cuenta...' : payoutAccount ? 'Actualizar referencia de cobro' : 'Guardar referencia de cobro'}
              </button>
            </form>
          ) : null}
          <div className="surface-subcard section-stack" role="status">
            <strong>Retiros de ganancias: pendiente</strong>
            <p>Las solicitudes y desembolsos de retiro para creadores aún no están habilitados.</p>
            <button className="ghost-btn" type="button" disabled>
              Retiro pendiente de habilitación
            </button>
          </div>
        </article>
      </div>

      {giftsCatalog.length > 0 ? (
        <article className="surface-card section-stack">
          <div>
            <h3>Catálogo de regalos en directos</h3>
            <p>Estos regalos pueden enviarse durante cualquier transmisión en vivo con tu saldo de monedas.</p>
          </div>
          <div className="profile-facts-grid">
            {giftsCatalog.map((gift) => (
              <div key={gift.id} className="surface-subcard">
                <strong>{gift.name}</strong>
                <p>{gift.priceDiamonds.toLocaleString('es')} monedas</p>
              </div>
            ))}
          </div>
        </article>
      ) : null}

      <article className="surface-card section-stack">
        <div>
          <h3>Historial de regalos</h3>
          <p>Regalos enviados y recibidos en directos.</p>
        </div>
        {giftHistory.length === 0 ? (
          <p>Aún no hay regalos registrados.</p>
        ) : (
          <div className="list-stack">
            {giftHistory.map((item) => (
              <div key={item.id} className="surface-subcard row-between">
                <div>
                  <strong>{item.giftName}</strong>
                  <p>
                    {ownsAccountId(item.senderId) ? 'Enviado' : `De ${item.senderName}`} · {item.amountDiamonds} monedas
                  </p>
                  {item.createdAt ? <small>{formatDateTime(item.createdAt)}</small> : null}
                </div>
                <span className="folder-tag">{item.status}</span>
              </div>
            ))}
          </div>
        )}
      </article>

      <article className="surface-card section-stack">
        <div>
          <h3>Ganancias recientes</h3>
          <p>Movimientos registrados para tu cuenta.</p>
        </div>
        {earnings.length === 0 ? (
          <p>Aún no hay movimientos de monetización.</p>
        ) : (
          <div className="list-stack">
            {earnings.map((earning) => (
              <div key={earning.id} className="surface-subcard row-between">
                <div>
                  <strong>{earning.sourceType}</strong>
                  <p>
                    {earning.amountDiamonds > 0
                      ? `${earning.amountDiamonds} monedas (${earning.withdrawableDiamonds} elegibles)`
                      : `Bruto ${earning.gross.toFixed(2)} · tarifa ${earning.fee.toFixed(2)} · neto ${earning.net.toFixed(2)}`}
                  </p>
                  {earning.createdAt ? <small>{formatDateTime(earning.createdAt)}</small> : null}
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
