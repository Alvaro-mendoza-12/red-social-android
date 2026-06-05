import { useCallback, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { StatusView } from '../../components/StatusView'
import { useAuth } from '../../core/auth/AuthProvider'
import { CallInterface } from './CallInterface'

export function CallsPage() {
  const { user } = useAuth()
  const navigate = useNavigate()
  const [activeCall, setActiveCall] = useState(false)

  const handleNewCall = useCallback(() => {
    setActiveCall(true)
  }, [])

  
  if (!user) {
    return <StatusView title="Autenticación requerida" description="Debes iniciar sesión para hacer llamadas." />
  }

  return (
    <div className="calls-page-container">
      <header className="calls-header">
        <h1>Llamadas</h1>
        <p>Comunicación en tiempo real con audio y vídeo</p>
      </header>

      <main className="calls-main">
        {activeCall ? (
          <CallInterface />
        ) : (
          <div className="calls-dashboard">
            <div className="call-options">
              <div className="option-card">
                <div className="option-icon">📞</div>
                <h3>Nueva Llamada</h3>
                <p>Inicia una llamada con otro usuario</p>
                <button 
                  className="option-btn"
                  onClick={handleNewCall}
                >
                  Iniciar Llamada
                </button>
              </div>

              <div className="option-card">
                <div className="option-icon">📋</div>
                <h3>Historial</h3>
                <p>Revisa tus llamadas recientes</p>
                <button 
                  className="option-btn secondary"
                  onClick={() => navigate('/app/messages')}
                >
                  Ver Mensajes
                </button>
              </div>

              <div className="option-card">
                <div className="option-icon">⚙️</div>
                <h3>Configuración</h3>
                <p>Ajusta tu dispositivo de audio y vídeo</p>
                <button 
                  className="option-btn secondary"
                  onClick={() => navigate('/app/settings')}
                >
                  Configurar
                </button>
              </div>
            </div>

            <div className="call-tips">
              <h3>Consejos para llamadas:</h3>
              <ul>
                <li>Asegúrate de tener una conexión estable a internet</li>
                <li>Usa auriculares para mejor calidad de audio</li>
                <li>Permite el acceso a tu cámara y micrófono cuando el navegador lo solicite</li>
                <li>Para compartir pantalla, haz clic en el botón correspondiente durante la llamada</li>
              </ul>
            </div>
          </div>
        )}
      </main>

      <style>{`
        .calls-page-container {
          min-height: 100vh;
          background: linear-gradient(135deg, #667eea 0%, #764ba2 100%);
          color: white;
        }

        .calls-header {
          padding: 2rem;
          text-align: center;
          background: rgba(0, 0, 0, 0.1);
        }

        .calls-header h1 {
          margin: 0;
          font-size: 2.5rem;
          font-weight: 700;
        }

        .calls-header p {
          margin: 0.5rem 0 0;
          opacity: 0.8;
          font-size: 1.1rem;
        }

        .calls-main {
          flex: 1;
          padding: 2rem;
        }

        .calls-dashboard {
          max-width: 1200px;
          margin: 0 auto;
          display: grid;
          grid-template-columns: 2fr 1fr;
          gap: 2rem;
        }

        .call-options {
          display: grid;
          grid-template-columns: repeat(auto-fit, minmax(250px, 1fr));
          gap: 1.5rem;
        }

        .option-card {
          background: rgba(255, 255, 255, 0.1);
          border: 1px solid rgba(255, 255, 255, 0.2);
          border-radius: 1rem;
          padding: 2rem;
          text-align: center;
          backdrop-filter: blur(10px);
          transition: all 0.3s ease;
        }

        .option-card:hover {
          background: rgba(255, 255, 255, 0.15);
          transform: translateY(-4px);
        }

        .option-icon {
          font-size: 3rem;
          margin-bottom: 1rem;
        }

        .option-card h3 {
          margin: 0 0 0.5rem 0;
          font-size: 1.5rem;
          font-weight: 600;
        }

        .option-card p {
          margin: 0 0 1.5rem 0;
          opacity: 0.8;
          line-height: 1.5;
        }

        .option-btn {
          background: #4CAF50;
          color: white;
          border: none;
          padding: 0.75rem 1.5rem;
          border-radius: 0.5rem;
          font-size: 1rem;
          font-weight: 600;
          cursor: pointer;
          transition: all 0.2s ease;
        }

        .option-btn:hover {
          background: #45a049;
          transform: scale(1.05);
        }

        .option-btn.secondary {
          background: rgba(255, 255, 255, 0.2);
          border: 1px solid rgba(255, 255, 255, 0.3);
        }

        .option-btn.secondary:hover {
          background: rgba(255, 255, 255, 0.3);
        }

        .call-tips {
          background: rgba(0, 0, 0, 0.2);
          border-radius: 1rem;
          padding: 2rem;
          backdrop-filter: blur(10px);
        }

        .call-tips h3 {
          margin: 0 0 1rem 0;
          font-size: 1.3rem;
          font-weight: 600;
        }

        .call-tips ul {
          margin: 0;
          padding-left: 1.5rem;
        }

        .call-tips li {
          margin-bottom: 0.5rem;
          line-height: 1.6;
          opacity: 0.9;
        }

        @media (max-width: 768px) {
          .calls-dashboard {
            grid-template-columns: 1fr;
            gap: 1rem;
          }

          .call-options {
            grid-template-columns: 1fr;
            gap: 1rem;
          }

          .option-card {
            padding: 1.5rem;
          }

          .calls-header {
            padding: 1rem;
          }

          .calls-main {
            padding: 1rem;
          }

          .call-tips {
            padding: 1.5rem;
          }
        }
      `}</style>
    </div>
  )
}
