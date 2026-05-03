export type InboxTab = 'chats' | 'groups' | 'communities' | 'voice'

export type ConversationItem = {
    id: string
    title: string
    subtitle: string
    updatedAt: string
    kind: InboxTab
    avatarUrl: string
    profileId: string | null
    unreadCount: number
    isPinned: boolean
    isMuted: boolean
    folders: string[]
    openPath?: string
}

export type SelectedConversation = {
    id: string
    kind: 'chat' | 'group' | 'community'
}

export type ChatFolder = {
    id: string
    name: string
    colorHex: string
}

export type LocalReadByChat = Record<string, string>

export type PollState = {
    pollId: string
    optionId: string
    optionText: string
    displayOrder: number
    voteCount: number
    votedByMe: boolean
    totalVotes: number
    allowsMultiple: boolean
    isClosed: boolean
    question: string
}

export type OtherParticipantState = {
    id: string
    name: string
    avatarUrl: string
    lastReadAt: string
}

export type StickerItem = {
    stickerId: string
    title: string
    mediaUrl: string
    isFavorite: boolean
}

export type ScheduledJob = {
    id: string
    content: string
    scheduledFor: string
    status: string
}
