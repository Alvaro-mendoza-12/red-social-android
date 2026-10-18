import { useEffect } from 'react'
import { Link, useLocation } from 'react-router-dom'
import { useAuth } from '../core/auth/AuthProvider'
import { attachmentJobsFor, dismissAttachmentJob, retainAttachmentOwner, useAttachmentJobRevision } from '../core/attachmentJobs'

export function AttachmentJobsStatus() {
  const { user } = useAuth(), location = useLocation()
  useAttachmentJobRevision()
  useEffect(() => { retainAttachmentOwner(user?.id || '') }, [user?.id])
  const jobs = attachmentJobsFor(user?.id || '').filter(job => job.route !== location.pathname)
  if (!jobs.length) return null
  return <aside className="attachment-jobs-status" aria-label="Archivos en preparación">
    {jobs.map(job => <section key={job.key}>
      <span role="status">{job.message}</span>
      <Link to={job.route}>Volver al chat</Link>
      {job.status === 'failed' ? <button type="button" onClick={() => dismissAttachmentJob(job.key)} aria-label="Cerrar aviso">×</button> : null}
    </section>)}
  </aside>
}
