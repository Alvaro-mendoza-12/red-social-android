import { useEffect, useState } from 'react'
import QRCode from 'qrcode'

type QrCodeTileProps = {
  value: string
  title?: string
  description?: string
  compact?: boolean
}

export function QrCodeTile({ value, title, description, compact = false }: QrCodeTileProps) {
  const [dataUrl, setDataUrl] = useState<string>('')
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    let cancelled = false

    async function renderQr() {
      if (!value.trim()) {
        setDataUrl('')
        return
      }
      try {
        const url = await QRCode.toDataURL(value, {
          width: compact ? 180 : 240,
          margin: 1,
          color: {
            dark: '#18181b',
            light: '#ffffff',
          },
        })
        if (!cancelled) {
          setError(null)
          setDataUrl(url)
        }
      } catch (caught) {
        if (!cancelled) {
          setError(caught instanceof Error ? caught.message : 'No se pudo dibujar el QR.')
        }
      }
    }

    void renderQr()
    return () => {
      cancelled = true
    }
  }, [compact, value])

  return (
    <div className={compact ? 'qr-tile compact' : 'qr-tile'}>
      {title ? <strong>{title}</strong> : null}
      {description ? <p>{description}</p> : null}
      {dataUrl ? <img src={dataUrl} alt={title || 'Código QR'} className="qr-image" /> : null}
      {error ? <small className="error-text">{error}</small> : null}
    </div>
  )
}
