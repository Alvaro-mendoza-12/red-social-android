import { StreamingImage } from '../components/StreamingMedia'
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
      setDataUrl('')
      setError(null)
      if (!value.trim()) {
        setDataUrl('')
        return
      }
      try {
        const url = await QRCode.toDataURL(value, {
          width: compact ? 220 : 320,
          margin: 4,
          errorCorrectionLevel: 'M',
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
          setError('No se pudo preparar el código. Inténtalo de nuevo.')
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
      {dataUrl ? <StreamingImage src={dataUrl} alt={title || 'Código QR'} className="qr-image" /> : null}
      {error ? <small className="error-text">{error}</small> : null}
    </div>
  )
}
