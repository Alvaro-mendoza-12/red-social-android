export function isHttpRetrySafeMethod(method?: string): boolean {
  return ['GET', 'HEAD', 'OPTIONS'].includes(String(method || 'GET').trim().toUpperCase())
}
