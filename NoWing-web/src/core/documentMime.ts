/** Browser/Windows file associations are metadata, not a reliable format detector.
 * The server still verifies the actual signature and scans the original bytes. */
export const messageDocumentTypes: Readonly<Record<string, string>> = {
  pdf: 'application/pdf', zip: 'application/zip',
  doc: 'application/msword', docx: 'application/vnd.openxmlformats-officedocument.wordprocessingml.document',
  docm: 'application/vnd.ms-word.document.macroenabled.12',
  xls: 'application/vnd.ms-excel', xlsx: 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet',
  xlsm: 'application/vnd.ms-excel.sheet.macroenabled.12', xlsb: 'application/vnd.ms-excel.sheet.binary.macroenabled.12',
  ppt: 'application/vnd.ms-powerpoint', pptx: 'application/vnd.openxmlformats-officedocument.presentationml.presentation',
  pptm: 'application/vnd.ms-powerpoint.presentation.macroenabled.12',
  odt: 'application/vnd.oasis.opendocument.text', ods: 'application/vnd.oasis.opendocument.spreadsheet',
  odp: 'application/vnd.oasis.opendocument.presentation', rtf: 'application/rtf', epub: 'application/epub+zip',
  txt: 'text/plain', csv: 'text/csv', tsv: 'text/tab-separated-values',
}
export function documentContentType(name: string): string | undefined {
  const extension = name.split('.').at(-1)?.toLowerCase() || ''
  return Object.hasOwn(messageDocumentTypes, extension) ? messageDocumentTypes[extension] : undefined
}
/** Also used when re-sending blobs saved by older versions in IndexedDB. */
export function normalizeDocumentBlob(blob: Blob, name: string): Blob {
  const type = documentContentType(name)
  return type && blob.type !== type ? blob.slice(0, blob.size, type) : blob
}
export function normalizeDocumentFile(file: File): File {
  const blob = normalizeDocumentBlob(file, file.name)
  return blob === file ? file : new File([blob], file.name, { type: blob.type, lastModified: file.lastModified })
}
