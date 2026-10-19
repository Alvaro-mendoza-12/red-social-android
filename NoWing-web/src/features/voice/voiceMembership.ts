/** A connected transport is not proof that the server accepted room membership. */
export function roomSocketReady(socket: { connected: boolean; id?: string } | null, acceptedSocketId: string) {
  return Boolean(socket?.connected && socket.id && socket.id === acceptedSocketId)
}
