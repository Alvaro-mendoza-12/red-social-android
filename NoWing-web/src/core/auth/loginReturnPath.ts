export function loginReturnPath(pathname: string, search = '', hash = ''): string {
  const next = `${pathname}${search}${hash}`
  return `/login?next=${encodeURIComponent(next)}`
}
