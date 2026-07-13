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

  return (
    <section className="section-stack">
      <div className="social-tab-bar">
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
