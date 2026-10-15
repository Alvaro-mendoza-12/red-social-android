import { useParams } from 'react-router-dom'
import { RoomChatPage } from './RoomChatPage'

export function CommunityChatDetailPage() {
  const { communityId = '' } = useParams()
  return <RoomChatPage scope="community" roomId={communityId} />
}
