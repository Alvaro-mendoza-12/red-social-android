// Crawlers receive the public object's HTML; people keep the existing app route.
// This chooses a presentation, never grants access to private data.
const crawler = /WhatsApp|facebookexternalhit|Facebot|Twitterbot|TelegramBot|LinkedInBot|Discordbot|Slackbot|SkypeUriPreview/i
const uuid = '[0-9a-f]{8}-(?:[0-9a-f]{4}-){3}[0-9a-f]{12}'
export function publicSharePreviewPath(path: string, userAgent: string): string | null {
  if (!crawler.test(userAgent)) return null
  if (/Mozilla\/.*(?:Chrome|Safari|Firefox)/i.test(userAgent) && !/bot|facebookexternalhit|Facebot/i.test(userAgent)) return null
  const profile = /^\/(?:u|profile|app\/profile)\/([a-z0-9_.-]{3,36})\/?$/i.exec(path)
  if (profile) return '/p/' + encodeURIComponent(profile[1])
  const post = new RegExp('^/(?:app/feed/post|post)/(' + uuid + ')/?$', 'i').exec(path)
  if (post) return '/v1/public/posts/' + post[1] + '/share'
  const video = new RegExp('^/(?:app/reels|app/videos|app/video|video)/(' + uuid + ')/?$', 'i').exec(path)
  if (video) return '/v1/public/videos/' + video[1] + '/share'
  return null
}
