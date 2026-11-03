import { useEffect } from 'react'
import { useLocation } from 'react-router-dom'
import { stopGuestBrowsing } from './guestAccess'

/** Keep the guest identity above route boundaries that remount on navigation. */
export function GuestBrowsingSession() {
  const { pathname } = useLocation()
  const exploring = /^\/explore(?:\/|$)/.test(pathname)
  useEffect(() => {
    if (!exploring) return
    return () => stopGuestBrowsing()
  }, [exploring])
  return null
}
