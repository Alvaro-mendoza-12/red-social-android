jest.mock('../../core/auth/fallbackAuth', () => ({ fallbackApiJson: jest.fn() }))
import { activeAnnouncement, announcementSchedule, officialUpdateUrl, type SystemAnnouncement } from '../../core/systemAnnouncements'

const notice: SystemAnnouncement = { id: 'fixture', kind: 'maintenance', title: 'Mantenimiento', body: 'Aviso',
  startsAt: '2026-10-02T10:00:00Z', endsAt: '2026-10-02T11:00:00Z', expiresAt: '2026-10-03T11:00:00Z' }
describe('system announcements', () => {
  test('expired and withdrawn notices are excluded', () => {
    expect(activeAnnouncement(notice, Date.parse('2026-10-02T09:00:00Z'))).toBe(true)
    expect(activeAnnouncement({ ...notice, revokedAt: '2026-10-01T10:00:00Z' }, Date.parse('2026-10-02T09:00:00Z'))).toBe(false)
    expect(activeAnnouncement(notice, Date.parse('2026-10-04T09:00:00Z'))).toBe(false)
  })
  test('invalid schedule is not formatted and the update URL is fixed', () => {
    expect(announcementSchedule({ ...notice, startsAt: 'invalid' })).toBe('')
    expect(announcementSchedule(notice)).toContain(Intl.DateTimeFormat().resolvedOptions().timeZone)
    expect(officialUpdateUrl).toBe('https://play.google.com/store/apps/details?id=conectate.com')
  })
})
