const REFERRALS = {
  health: 'Por seguridad, Novvin no da diagnosticos ni indica tratamientos. Consulta a un profesional sanitario; si existe peligro inmediato, contacta a los servicios de emergencia de tu zona.',
  legal: 'Por seguridad, Novvin no ofrece orientacion legal. Verifica el caso con una fuente oficial de tu jurisdiccion o con un profesional cualificado.',
  finance: 'Por seguridad, Novvin no decide inversiones, creditos ni operaciones financieras. Consulta una entidad oficial o un profesional cualificado antes de actuar.',
  security: 'Novvin no entrega instrucciones para vulnerar sistemas ni solicita credenciales. Usa los canales oficiales y nunca compartas contrasenas, codigos o tokens.',
  personal_data: 'No necesito datos personales para responder. Retira correos, telefonos, direcciones, documentos, enlaces privados y credenciales antes de volver a preguntar.',
  harm: 'Novvin no ofrece instrucciones que puedan facilitar dano. Si existe un riesgo inmediato, contacta a los servicios de emergencia de tu zona.',
  current_info: 'La IA propia de Conectate no consulta Internet ni verifica informacion en tiempo real. Usa una fuente oficial y reciente para noticias, clima, precios, resultados o cargos publicos.',
}

function normalizeForPolicy(value) {
  return String(value || '')
    .toLowerCase()
    .normalize('NFD')
    .replace(/[\u0300-\u036f]/g, '')
    .replace(/[^a-z0-9\s]/g, ' ')
    .replace(/\s+/g, ' ')
    .trim()
}

function classifyGeneralRisk(value) {
  const raw = String(value || '').slice(0, 1200)
  const normalized = normalizeForPolicy(raw)
  const containsPersonalData =
    /\b[A-Z0-9._%+-]+@[A-Z0-9.-]+\.[A-Z]{2,}\b/i.test(raw) ||
    /(?:https?:\/\/|www\.)\S+/i.test(raw) ||
    /(?:Bearer\s+)?(?:eyJ|sk-|pk_)[A-Za-z0-9._-]{20,}/i.test(raw) ||
    /\b[0-9a-f]{32,}\b/i.test(raw) ||
    /(^|[^\w])(\+?\d[\d\s().-]{7,}\d)\b/.test(raw) ||
    /\b(?:dni|cedula|pasaporte|documento de identidad)(?:\s+(?:es|numero|nro|n))?\s+(?=[a-z0-9]*\d)[a-z0-9]{6,20}\b/.test(normalized) ||
    /\b(?:mi direccion|direccion particular|direccion de casa|domicilio)\s+(?:es\s+)?(?:calle|avenida|av|jiron|jr|carrera|cra)\b/.test(normalized)
  if (containsPersonalData) return 'personal_data'
  if (/\b(salud|medicos?|medicas?|medicina|medicamentos?|pastillas?|remedios?|dosis|sintomas?|diagnosticos?|enfermedades?|dolor|fiebre|tratamientos?|terapia|psicologos?|psicologas?|psiquiatras?|suicidio|autolesion|embarazo|urgencias?)\b/.test(normalized)) return 'health'
  const scientificLaw = /\bley(?:es)? de (?:gravedad|movimiento|termodinamica|conservacion|newton|ohm)\b/.test(normalized)
  const explicitLegal = /\b(legal|abogados?|abogadas?|demandas?|contratos?|delitos?|tribunal|juez|divorcio|herencia|derecho penal|asesoria juridica|impuestos?)\b/.test(normalized)
  const genericLaw = /\bley(?:es)?\b/.test(normalized) && !scientificLaw
  if (explicitLegal || genericLaw) return 'legal'
  const explicitFinance = /\b(finanzas|financieros?|financieras?|invertir|inversion|inversiones|acciones|bolsa|cripto|bitcoin|prestamos?|creditos?|rentabilidad|trading)\b/.test(normalized)
  const financialChoice = /\b(dinero|bancos?|ahorros?)\b.*\b(elegir|recomendar|conviene|ganar|guardar)\b|\b(elegir|recomendar|conviene)\b.*\b(dinero|bancos?|ahorros?)\b/.test(normalized)
  if (explicitFinance || financialChoice) return 'finance'
  if (
    /\b(contrasenas?|passwords?|claves?|credenciales?|tokens?|pins?|otp|2fa|codigos? de acceso|codigos? de verificacion|hack|hackear|piratear|vulnerar|vulnerabilidad|malware|phishing|ransomware|ciberseguridad|jailbreak|prompt del sistema)\b/.test(normalized) ||
    /\bignora\b.*\binstrucciones\b/.test(normalized) ||
    /\b(revela|muestra)\b.*\b(prompt|reglas|instrucciones)\b/.test(normalized) ||
    /\bactua como\b/.test(normalized)
  ) return 'security'
  if (/\b(armas?|bombas?|explosivos?|matar|asesinar|violencia|drogas?|veneno|porno|pornografia|sexo|sexual|abusar)\b/.test(normalized)) return 'harm'
  if (/\b(noticias?|actualmente|hoy|ultima hora|clima|pronostico|marcador|resultados? deportivos?|precios? actuales?|elecciones|presidente actual)\b/.test(normalized)) return 'current_info'
  return null
}

function safeReferral(category) {
  return REFERRALS[category] || null
}

function redactSensitiveText(value, maximum = 1500) {
  return String(value || '')
    .slice(0, maximum)
    .replace(/\b[A-Z0-9._%+-]+@[A-Z0-9.-]+\.[A-Z]{2,}\b/gi, '[correo omitido]')
    .replace(/\b(?:https?:\/\/|www\.)\S+/gi, '[enlace omitido]')
    .replace(/\b(?:Bearer\s+)?(?:eyJ|sk-|pk_)[A-Za-z0-9._-]{20,}\b/gi, '[secreto omitido]')
    .replace(/\b[0-9a-f]{32,}\b/gi, '[secreto omitido]')
    .replace(/(^|[^\w])(\+?\d[\d\s().-]{7,}\d)\b/g, '$1[numero omitido]')
    .replace(/\b(dni|c[eé]dula|pasaporte|documento de identidad)(?:\s+(?:es|n[uú]mero|nro|n))?\s+(?=[a-z0-9]*\d)[a-z0-9]{6,20}\b/gi, '$1 [documento omitido]')
    .trim()
}

module.exports = {
  REFERRALS,
  classifyGeneralRisk,
  normalizeForPolicy,
  redactSensitiveText,
  safeReferral,
}
