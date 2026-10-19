import { ContactRound, Mail, Phone } from 'lucide-react'
export function ContactMessageCard({ content }: { content: string }) {
  let name = 'Contacto', phone = '', email = ''
  try {
    const data = JSON.parse(content) as Record<string, unknown>
    name = typeof data.name === 'string' ? data.name.slice(0, 120) : name
    phone = typeof data.phone === 'string' ? data.phone : Array.isArray(data.phones) && typeof data.phones[0] === 'string' ? data.phones[0] : ''
    email = typeof data.email === 'string' ? data.email : Array.isArray(data.emails) && typeof data.emails[0] === 'string' ? data.emails[0] : ''
  } catch { return <p>{content}</p> }
  const dial = phone.replace(/[^+\d]/g, '').slice(0, 30)
  const mail = email.trim().slice(0, 254)
  return <section className="message-contact-card" aria-label={`Contacto ${name}`}>
    <strong><ContactRound size={18} /> {name}</strong>
    {dial ? <a className="inline-link" href={`tel:${dial}`}><Phone size={15} /> {phone.slice(0, 50)}</a> : null}
    {/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(mail) ? <a className="inline-link" href={`mailto:${encodeURIComponent(mail)}`}><Mail size={15} /> {mail}</a> : null}
  </section>
}
