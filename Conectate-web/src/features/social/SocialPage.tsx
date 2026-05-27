import { useMemo } from 'react'
import { Link, useSearchParams } from 'react-router-dom'
import { ChallengesPage } from '../../pages/challenges/ChallengesPage'
import { FeedPage } from '../../pages/feed/FeedPage'
import { MomentsPage } from '../moments/MomentsPage'

type SocialTab = 'posts' | 'moments' | 'challenges'

function normalizeTab(value: string | null): SocialTab {
  if (value === 'challenges') return 'challenges'
  return value === 'moments' ? 'moments' : 'posts'
}

export function SocialPage() {
  const [searchParams, setSearchParams] = useSearchParams()
  const activeTab = useMemo<SocialTab>(() => normalizeTab(searchParams.get('tab')), [searchParams])

  function changeTab(nextTab: SocialTab) {
    setSearchParams({ tab: nextTab }, { replace: true })
  }

  return (
    <section className="section-stack">
      <article className="surface-card social-overview-card">
        <div className="social-overview-head">
          <div className="social-overview-copy">
            <span className="brand-tag">Social</span>
            <h2>Conecta con lo que está pasando</h2>
            <p>
              Publica, mira momentos, participa en retos y encuentra personas sin salir de la misma
              experiencia.
            </p>
          </div>
          <div className="row-actions social-overview-actions wrap">
            <Link className="ghost-btn" to="/app/search">
              Buscar
            </Link>
            <Link className="ghost-btn" to="/app/notifications">
              Alertas
            </Link>
            <Link className="ghost-btn" to="/app/follows">
              Seguidores
            </Link>
            <Link className="ghost-btn" to="/app/friend-requests">
              Solicitudes
            </Link>
          </div>
        </div>

        <div className="chip-row social-chip-row">
          <button
            type="button"
            className={activeTab === 'posts' ? 'chip-btn active' : 'chip-btn'}
            aria-pressed={activeTab === 'posts'}
            onClick={() => changeTab('posts')}
          >
            Publicaciones
          </button>
          <button
            type="button"
            className={activeTab === 'moments' ? 'chip-btn active' : 'chip-btn'}
            aria-pressed={activeTab === 'moments'}
            onClick={() => changeTab('moments')}
          >
            Momentos
          </button>
          <button
            type="button"
            className={activeTab === 'challenges' ? 'chip-btn active' : 'chip-btn'}
            aria-pressed={activeTab === 'challenges'}
            onClick={() => changeTab('challenges')}
          >
            Retos
          </button>
        </div>
      </article>

      <div className="social-shell">
        {activeTab === 'posts'
          ? <FeedPage />
          : activeTab === 'moments'
            ? <MomentsPage />
            : <ChallengesPage />}
      </div>
    </section>
  )
}
