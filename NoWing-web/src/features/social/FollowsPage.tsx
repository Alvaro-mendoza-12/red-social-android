import { useCallback, useEffect, useMemo, useState } from 'react'
import { Link, useSearchParams } from 'react-router-dom'
import { StatusView } from '../../components/StatusView'
import { supabase } from '../../core/supabase'
import { friendlyError, textOf, type GenericRow } from '../../core/utils'
import { useAuth } from '../../core/auth/AuthProvider'
import { loadFallbackFollows, setFallbackProfileFollow } from '../../core/fallbackData'
import { shouldUseUbuntuBackend } from '../../core/ubuntuBackend'

type FollowTab = 'following' | 'followers'

type ProfileItem = {
  id: string
  name: string
  username: string
  following: boolean
}

function mapProfile(row: GenericRow): ProfileItem {
  return {
    id: textOf(row, ['id'], ''),
    name: textOf(row, ['displayName', 'display_name', 'full_name'], 'Usuario'),
    username: textOf(row, ['username'], ''),
    following: row.is_following === true,
  }
}

export function FollowsPage() {
  const { user, isFallbackSession } = useAuth()
  const [params, setParams] = useSearchParams()
  const activeTab: FollowTab = params.get('tab') === 'followers' ? 'followers' : 'following'
  const setActiveTab = (tab: FollowTab) => setParams({ tab }, { replace: true })
  return <FollowTabContent key={`${user?.id}/${activeTab}/${isFallbackSession}`} activeTab={activeTab} setActiveTab={setActiveTab}/>
}

function FollowTabContent({ activeTab, setActiveTab }: { activeTab: FollowTab; setActiveTab: (tab: FollowTab) => void }) {
  const { user, isFallbackSession } = useAuth()
  const [items, setItems] = useState<ProfileItem[]>([])
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)
  const [search, setSearch] = useState('')
  const useFallback = shouldUseUbuntuBackend(isFallbackSession)

  const filtered = useMemo(() => {
    const q = search.trim().toLowerCase()
    if (!q) return items
    return items.filter((item) => {
      return item.name.toLowerCase().includes(q) || item.username.toLowerCase().includes(q)
    })
  }, [items, search])

  const load = useCallback(async () => {
    if (useFallback) {
      setLoading(true)
      setError(null)
      try {
        const response = await loadFallbackFollows(activeTab)
        setItems(response.items.map(mapProfile))
      } catch (caught) {
        setError(friendlyError(caught, 'No se pudo cargar la lista.'))
      } finally {
        setLoading(false)
      }
      return
    }

    if (!supabase || !user) {
      setLoading(false)
      return
    }
    setLoading(true)
    setError(null)
    try {
      const idField = activeTab === 'following' ? 'following_id' : 'follower_id'
      const filterField = activeTab === 'following' ? 'follower_id' : 'following_id'
      const { data: followRows, error: followError } = await supabase
        .from('follows')
        .select('*')
        .eq(filterField, user.id)
      if (followError) throw followError

      const ids = ((followRows ?? []) as GenericRow[])
        .map((row) => textOf(row, [idField], ''))
        .filter((id) => id.length > 0)
      if (ids.length === 0) {
        setItems([])
        setLoading(false)
        return
      }
      const { data: profileRows, error: profileError } = await supabase
        .from('profiles')
        .select('*')
        .in('id', ids)
      if (profileError) throw profileError
      setItems(((profileRows ?? []) as GenericRow[]).map(mapProfile))
    } catch (caught) {
      setError(friendlyError(caught, 'No se pudo cargar la lista.'))
    } finally {
      setLoading(false)
    }
  }, [activeTab, user, useFallback])

  useEffect(() => {
    void load()
  }, [load])

  async function toggleFollow(targetUserId: string, following: boolean) {
    if (!user) return
    if (useFallback) {
      try {
        await setFallbackProfileFollow(targetUserId, !following)
        await load()
      } catch (caught) {
        setError(friendlyError(caught, 'No se pudo actualizar el seguimiento.'))
      }
      return
    }

    if (!supabase) return
    if (activeTab === 'following') {
      const { error: deleteError } = await supabase
        .from('follows')
        .delete()
        .eq('follower_id', user.id)
        .eq('following_id', targetUserId)
      if (deleteError) {
        setError(friendlyError(deleteError, 'No se pudo dejar de seguir'))
        return
      }
    } else {
      const { error: insertError } = await supabase.from('follows').insert({
        follower_id: user.id,
        following_id: targetUserId,
      })
      if (insertError) {
        setError(friendlyError(insertError, 'No se pudo seguir al usuario'))
        return
      }
    }
    await load()
  }

  if (!supabase && !useFallback) {
    return <StatusView title="Lista no disponible" description="Inténtalo de nuevo más tarde." />
  }
  if (loading) return <StatusView title="Cargando red social" description="Espera un momento..." />
  if (error) return <StatusView title="Lista no disponible" description={error} action={<button className="ghost-btn" type="button" onClick={() => void load()}>Reintentar</button>} />

  return (
    <section className="section-stack">
      <header className="section-header">
        <h2>Seguidores y siguiendo</h2>
      </header>
      <div className="chip-row">
        <button
          type="button"
          className={activeTab === 'following' ? 'chip-btn active' : 'chip-btn'}
          onClick={() => setActiveTab('following')}
        >
          Siguiendo
        </button>
        <button
          type="button"
          className={activeTab === 'followers' ? 'chip-btn active' : 'chip-btn'}
          onClick={() => setActiveTab('followers')}
        >
          Seguidores
        </button>
      </div>
      <input
        className="text-input"
        placeholder="Filtrar por nombre o usuario"
        value={search}
        onChange={(event) => setSearch(event.target.value)}
      />

      {filtered.length === 0 ? (
        <StatusView title="Sin resultados" description="No hay usuarios en esta vista." />
      ) : (
        <div className="list-stack">
          {filtered.map((profile) => (
            <article key={profile.id} className="surface-card list-item">
              <div>
                <h3>{profile.name}</h3>
                <p>{profile.username ? `@${profile.username}` : '@usuario'}</p>
              </div>
              <div className="row-actions">
                <Link className="ghost-btn" to={`/app/profile/${profile.id}`}>
                  Ver perfil
                </Link>
                <button className="ghost-btn" type="button" onClick={() => void toggleFollow(profile.id, activeTab === 'following' || profile.following)}>
                  {activeTab === 'following' || profile.following ? 'Dejar de seguir' : 'Seguir'}
                </button>
              </div>
            </article>
          ))}
        </div>
      )}
    </section>
  )
}
