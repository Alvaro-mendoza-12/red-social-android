/** Uploaded/catalog media can send while a large local attachment is verified. */
export function outboxDeliveryLane(item: { id: string; payload: { mediaUrl?: string | null } }) {
  return String(item.payload.mediaUrl || '').startsWith('novvin-pending-file:') ? 'upload/' + item.id : 'ready'
}
