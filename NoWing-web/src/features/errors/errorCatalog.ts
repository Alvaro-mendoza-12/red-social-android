export type ErrorCharacter = 'confused' | 'guardian' | 'protector' | 'detective' | 'overloaded' | 'sleeper'
export type ErrorLevel = 'notice' | 'connection' | 'critical'

export const characters: Record<ErrorCharacter, { name: string; primaryCode: number; symbol: string }> = {
  confused: { name: 'La curiosa', primaryCode: 400, symbol: '?' },
  guardian: { name: 'La guardiana', primaryCode: 401, symbol: '✦' },
  protector: { name: 'La protectora', primaryCode: 403, symbol: '!' },
  detective: { name: 'La detective', primaryCode: 404, symbol: '✧' },
  overloaded: { name: 'El técnico', primaryCode: 500, symbol: '✷' },
  sleeper: { name: 'El dormilón', primaryCode: 503, symbol: 'z' },
}

export type NovvinProblem = {
  code: number | null
  character: ErrorCharacter
  level: ErrorLevel
  title: string
  description: string
  spokenCode?: string
}

export const errorCatalog: readonly NovvinProblem[] = [
  { code: 400, character: 'confused', level: 'notice', title: "Solicitud incorrecta",
    description: "Algo no encaja. Revisa lo que ingresaste e inténtalo de nuevo.", spokenCode: "cuatrocientos" },
  { code: 401, character: 'guardian', level: 'notice', title: "Vuelve a iniciar sesión",
    description: "Necesitas una sesión válida para continuar.", spokenCode: "cuatrocientos uno" },
  { code: 403, character: 'protector', level: 'notice', title: "Acceso restringido",
    description: "Este espacio está protegido. Tu cuenta no tiene permiso para entrar.", spokenCode: "cuatrocientos tres" },
  { code: 404, character: 'detective', level: 'notice', title: "No encontramos esta página",
    description: "Seguí la pista, pero el enlace pudo cambiar o el contenido ya no está disponible.", spokenCode: "cuatrocientos cuatro" },
  { code: 408, character: 'sleeper', level: 'connection', title: "Se agotó el tiempo de espera",
    description: "La solicitud tardó más de lo esperado. Revisa la conexión y vuelve a intentarlo.", spokenCode: "cuatrocientos ocho" },
  { code: 409, character: 'confused', level: 'notice', title: "El contenido cambió",
    description: "Parece que alguien hizo un cambio. Actualiza y revisa los datos antes de continuar.", spokenCode: "cuatrocientos nueve" },
  { code: 422, character: 'confused', level: 'notice', title: "Revisa los datos",
    description: "La solicitud llegó, pero falta corregir un dato antes de continuar.", spokenCode: "cuatrocientos veintidós" },
  { code: 429, character: 'protector', level: 'notice', title: "Demasiadas solicitudes",
    description: "Hagamos una pausa. Espera un momento antes de volver a intentarlo.", spokenCode: "cuatrocientos veintinueve" },
  { code: 500, character: 'overloaded', level: 'critical', title: "Algo salió mal",
    description: "Un problema en NOVVIN impidió completar la acción. Puedes volver a intentarlo.", spokenCode: "quinientos" },
  { code: 502, character: 'overloaded', level: 'connection', title: "No pudimos conectar con el servicio",
    description: "Un servicio no respondió como esperaba. Inténtalo en unos momentos.", spokenCode: "quinientos dos" },
  { code: 503, character: 'sleeper', level: 'connection', title: "Servicio no disponible",
    description: "El servicio se está tomando un descanso. Inténtalo más tarde.", spokenCode: "quinientos tres" },
  { code: 504, character: 'sleeper', level: 'connection', title: "El servicio tardó demasiado",
    description: "La respuesta no llegó a tiempo. Inténtalo en unos momentos.", spokenCode: "quinientos cuatro" },
]

const byCode = new Map(errorCatalog.map(problem => [problem.code, problem]))

export const offlineProblem: NovvinProblem = {
  code: null, character: 'sleeper', level: 'connection', title: 'No tienes conexión a internet',
  description: 'Revisa la conexión y vuelve a intentarlo.',
}

export const unexpectedProblem: NovvinProblem = {
  code: null, character: 'overloaded', level: 'critical', title: 'Algo salió mal',
  description: 'Un problema en NOVVIN impidió completar la acción. Puedes volver a intentarlo.',
}

export const interruptedProblem: NovvinProblem = {
  code: null, character: 'sleeper', level: 'connection', title: 'No pudimos abrir esta sección',
  description: 'Puedes ir a otra sección o recargar la aplicación.',
}

export function problemForStatus(status: number): NovvinProblem | undefined {
  return byCode.get(status)
}

export function problemForError(error: unknown): NovvinProblem | undefined {
  const seen = new Set<object>()
  let current = error
  while (current && typeof current === 'object' && !seen.has(current) && seen.size < 8) {
    seen.add(current)
    const { status, statusCode, name, message, cause } = current as {
      status?: unknown; statusCode?: unknown; name?: unknown; message?: unknown; cause?: unknown
    }
    const numeric = typeof status === 'number' ? status : statusCode
    if (typeof numeric === 'number') {
      const problem = problemForStatus(numeric)
      if (problem) return problem
    }
    if (name === 'TimeoutError') return problemForStatus(408)
    if (name === 'TypeError' && typeof message === 'string' &&
        /^(failed to fetch|load failed|networkerror when attempting to fetch resource\.?)$/i.test(message.trim())) {
      return typeof navigator !== 'undefined' && navigator.onLine === false ? offlineProblem : interruptedProblem
    }
    current = cause
  }
  return undefined
}

export function narrationFor(problem: NovvinProblem) {
  const prefix = problem.spokenCode ? `Error ${problem.spokenCode}. ` : ''
  return `${prefix}${problem.title}. ${problem.description}`
}
