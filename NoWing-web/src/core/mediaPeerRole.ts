/** Match Kotlin String.compareTo; locale collation is not a protocol tie-breaker. */
export function mediaPeerIsInitiator(ownUser: string, peerUser: string, ownSocket: string, peerSocket: string) {
  return ownUser === peerUser ? ownSocket > peerSocket : ownUser > peerUser
}
