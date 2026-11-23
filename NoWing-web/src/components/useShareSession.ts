import { useEffect, useState, useSyncExternalStore } from 'react'
import { getStorageReadIdentity, subscribeStorageReadIdentity } from '../core/media/storageReadSession'

/** Bind a share dialog and its entire batch to the session that opened it. */
export function useShareSession(onClose: () => void) {
  const [owner] = useState(getStorageReadIdentity)
  const current = useSyncExternalStore(subscribeStorageReadIdentity, getStorageReadIdentity, getStorageReadIdentity)
  const sessionActive = owner === current
  useEffect(() => {
    if (!sessionActive) onClose()
  }, [onClose, sessionActive])
  return { owner, sessionActive }
}
