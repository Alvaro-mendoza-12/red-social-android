import { useParams } from 'react-router-dom'
import { RoomChatPage } from './RoomChatPage'

export function GroupChatDetailPage() {
  const { groupId = '' } = useParams()
  return <RoomChatPage scope="group" roomId={groupId} />
}
