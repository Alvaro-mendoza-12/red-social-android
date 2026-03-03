import { useMemo } from 'react'
import { Link, useSearchParams } from 'react-router-dom'
import { ChallengesPage } from '../challenges/ChallengesPage'
import { FeedPage } from '../feed/FeedPage'
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
      <header className="section-header">
        <h2>Social</h2>
        <p>Publicaciones y momentos en un solo módulo, compartido con la app móvil.</p>
      </header>

      <div className="row-between">
        <div className="chip-row">
          <button
            type="button"
            className={activeTab === 'posts' ? 'chip-btn active' : 'chip-btn'}
            onClick={() => changeTab('posts')}
          >
            Publicaciones
          </button>
          <button
            type="button"
            className={activeTab === 'moments' ? 'chip-btn active' : 'chip-btn'}
            onClick={() => changeTab('moments')}
          >
            Momentos
          </button>
          <button
            type="button"
            className={activeTab === 'challenges' ? 'chip-btn active' : 'chip-btn'}
            onClick={() => changeTab('challenges')}
          >
            Retos
          </button>
        </div>
        <div className="row-actions">
          <Link className="ghost-btn" to="/app/search">
            Buscar
          </Link>
          <Link className="ghost-btn" to="/app/notifications">
            Alerts
          </Link>
          <Link className="ghost-btn" to="/app/follows">
            Seguidores
          </Link>
          <Link className="ghost-btn" to="/app/friend-requests">
            Solicitudes
          </Link>
        </div>
      </div>

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
