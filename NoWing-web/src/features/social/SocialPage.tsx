import { useMemo } from 'react'
import { useSearchParams } from 'react-router-dom'
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

  function closeComposer() {
    setSearchParams({ tab: 'posts' }, { replace: true })
  }

  return (
    <section className="social-page">
      <header className="social-intro">
        <div><p className="social-eyebrow">CONVERSA · COMPARTE · DESCUBRE</p><h2>Tu mundo, en común.</h2><p>Publicaciones, momentos y rondas de tu comunidad.</p></div>
        <span className="social-intro-mark" aria-hidden="true">✳</span>
      </header>
      <div className="social-tab-bar" role="group" aria-label="Contenido de Social">
        <button
          type="button"
          className={activeTab === 'posts' ? 'chip-btn active' : 'chip-btn'}
          aria-pressed={activeTab === 'posts'}
          onClick={() => changeTab('posts')}
        >
          Social
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
          Rondas
        </button>
      </div>

      <div className="social-shell">
        {activeTab === 'posts' ? (
          <FeedPage
            composerOpen={searchParams.get('compose') === '1'}
            promotionsOpen={searchParams.get('advertising') === '1'}
            onClosePromotions={() => setSearchParams({ tab: 'posts' }, { replace: true })}
            onCloseComposer={closeComposer}
          />
        ) : activeTab === 'moments' ? (
          <MomentsPage />
        ) : (
          <ChallengesPage />
        )}
      </div>
    </section>
  )
}
