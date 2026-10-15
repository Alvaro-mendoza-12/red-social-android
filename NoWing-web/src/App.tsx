import { PageMetadata } from './ui/novvin/PageMetadata'
import { PublicHome } from './ui/novvin/PublicHome'
import { WebOutboxHost } from './components/WebOutboxHost'
import { lazy, Suspense, type ReactNode } from 'react'
import { BrowserRouter, Navigate, Route, Routes } from 'react-router-dom'
import { StatusView } from './components/StatusView'
import { AuthProvider, GuestOnlyRoute, RequireAuth, useAuth } from './core/auth/AuthProvider'
import { LoginPage } from './pages/auth/LoginPage'
import { RegisterPage } from './pages/auth/RegisterPage'
import { ForgotPasswordPage } from './pages/auth/ForgotPasswordPage'
import { SystemAnnouncementHost } from './components/SystemAnnouncementHost'
import { AppLayout } from './layout/AppLayout'

const PostDetailPage = lazy(() =>
  import('./pages/feed/PostDetailPage').then((module) => ({
    default: module.PostDetailPage,
  })),
)
const ExplorePage = lazy(() =>
  import('./pages/ExplorePage').then((module) => ({
    default: module.ExplorePage,
  })),
)
const MomentsViewerPage = lazy(() =>
  import('./features/moments/MomentsViewerPage').then((module) => ({
    default: module.MomentsViewerPage,
  })),
)
const ChallengesRankingPage = lazy(() =>
  import('./pages/challenges/ChallengesRankingPage').then((module) => ({
    default: module.ChallengesRankingPage,
  })),
)
const ReelsPage = lazy(() =>
  import('./features/reels/ReelsPage').then((module) => ({
    default: module.ReelsPage,
  })),
)
const VideoDetailPage = lazy(() =>
  import('./features/reels/VideoDetailPage').then((module) => ({
    default: module.VideoDetailPage,
  })),
)
const MessagesPage = lazy(() =>
  import('./features/messages/MessagesPage').then((module) => ({
    default: module.MessagesPage,
  })),
)
const NovvinPage = lazy(() =>
  import('./features/novvin/NovvinPage').then((module) => ({
    default: module.NovvinPage,
  })),
)
const ChatDetailPage = lazy(() =>
  import('./features/messages/ChatDetailPage').then((module) => ({
    default: module.ChatDetailPage,
  })),
)
const GroupChatDetailPage = lazy(() =>
  import('./features/messages/GroupChatDetailPage').then((module) => ({
    default: module.GroupChatDetailPage,
  })),
)
const CommunityChatDetailPage = lazy(() =>
  import('./features/messages/CommunityChatDetailPage').then((module) => ({
    default: module.CommunityChatDetailPage,
  })),
)
const VoiceRoomsPage = lazy(() =>
  import('./features/voice/VoiceRoomsPage').then((module) => ({
    default: module.VoiceRoomsPage,
  })),
)
const VoiceRoomPage = lazy(() =>
  import('./features/voice/VoiceRoomPage').then((module) => ({
    default: module.VoiceRoomPage,
  })),
)
const LiveHubPage = lazy(() =>
  import('./features/live/LiveHubPage').then((module) => ({
    default: module.LiveHubPage,
  })),
)
const LiveSessionPage = lazy(() =>
  import('./features/live/LiveSessionPage').then((module) => ({
    default: module.LiveSessionPage,
  })),
)
const ProximityPage = lazy(() =>
  import('./features/proximity/ProximityPage').then((module) => ({
    default: module.ProximityPage,
  })),
)
const GamesPage = lazy(() =>
  import('./features/games/GamesPage').then((module) => ({
    default: module.GamesPage,
  })),
)
const PlayerDetailPage = lazy(() =>
  import('./features/games/PlayerDetailPage').then((module) => ({
    default: module.PlayerDetailPage,
  })),
)
const MyReportsPage = lazy(() =>
  import('./features/reports/MyReportsPage').then((module) => ({
    default: module.MyReportsPage,
  })),
)
const HelpPage = lazy(() =>
  import('./features/help/HelpPage').then((module) => ({
    default: module.HelpPage,
  })),
)
const CreateSpacePage = lazy(() =>
  import('./features/spaces/CreateSpacePage').then((module) => ({
    default: module.CreateSpacePage,
  })),
)
const CallsPage = lazy(() =>
  import('./features/calls/CallsPage').then((module) => ({
    default: module.CallsPage,
  })),
)
const SearchPage = lazy(() =>
  import('./features/search/SearchPage').then((module) => ({
    default: module.SearchPage,
  })),
)
const NotificationsPage = lazy(() =>
  import('./features/notifications/NotificationsPage').then((module) => ({
    default: module.NotificationsPage,
  })),
)
const SocialPage = lazy(() =>
  import('./features/social/SocialPage').then((module) => ({
    default: module.SocialPage,
  })),
)
const FollowsPage = lazy(() =>
  import('./features/social/FollowsPage').then((module) => ({
    default: module.FollowsPage,
  })),
)
const FriendRequestsPage = lazy(() =>
  import('./features/social/FriendRequestsPage').then((module) => ({
    default: module.FriendRequestsPage,
  })),
)
const ProfilePage = lazy(() =>
  import('./features/profile/ProfilePage').then((module) => ({
    default: module.ProfilePage,
  })),
)
const PublicProfilePage = lazy(() =>
  import('./features/profile/PublicProfilePage').then((module) => ({
    default: module.PublicProfilePage,
  })),
)
const ProfileChatRedirectPage = lazy(() =>
  import('./features/profile/ProfileChatRedirectPage').then((module) => ({
    default: module.ProfileChatRedirectPage,
  })),
)
const AccountSettingsPage = lazy(() =>
  import('./features/profile/AccountSettingsPage').then((module) => ({
    default: module.AccountSettingsPage,
  })),
)
const MonetizationCenterPage = lazy(() =>
  import('./features/profile/MonetizationCenterPage').then((module) => ({
    default: module.MonetizationCenterPage,
  })),
)
const AdminModerationPage = lazy(() =>
  import('./pages/admin/AdminModerationPage').then((module) => ({
    default: module.AdminModerationPage,
  })),
)

function RouteSuspense({ children }: { children: ReactNode }) {
  return (
    <Suspense fallback={<StatusView title="Cargando módulo..." description="Espera un momento." />}>
      {children}
    </Suspense>
  )
}

function RootRoute() {
  const { loading, user } = useAuth()
  if (loading) return <PublicHome />
  if (user) return <Navigate to="/app/messages" replace />
  return <PublicHome />
}

function AppRouter() {
  return (
    <BrowserRouter>
      <PageMetadata />
      <Routes>
        <Route path="/" element={<RootRoute />} />
        <Route
          path="/explore/:kind?/:id?"
          element={
            <GuestOnlyRoute>
              <RouteSuspense>
                <ExplorePage />
              </RouteSuspense>
            </GuestOnlyRoute>
          }
        />
        <Route
          path="/forgot-password"
          element={
            <GuestOnlyRoute>
              <ForgotPasswordPage />
            </GuestOnlyRoute>
          }
        />
        <Route
          path="/login"
          element={
            <GuestOnlyRoute>
              <LoginPage />
            </GuestOnlyRoute>
          }
        />
        <Route
          path="/register"
          element={
            <GuestOnlyRoute>
              <RegisterPage />
            </GuestOnlyRoute>
          }
        />
        <Route
          path="/u/:profileId"
          element={
            <RouteSuspense>
              <PublicProfilePage />
            </RouteSuspense>
          }
        />
        <Route
          path="/profile/:profileId"
          element={
            <RouteSuspense>
              <PublicProfilePage />
            </RouteSuspense>
          }
        />
        <Route
          path="/chat/:profileId"
          element={
            <RouteSuspense>
              <ProfileChatRedirectPage />
            </RouteSuspense>
          }
        />
        <Route
          path="/app"
          element={
            <RequireAuth>
              <AppLayout />
            </RequireAuth>
          }
        >
          <Route index element={<Navigate to="messages" replace />} />
          <Route
            path="videos"
            element={
              <RouteSuspense>
                <ReelsPage />
              </RouteSuspense>
            }
          />
          <Route path="feed" element={<Navigate to="/app/social?tab=posts" replace />} />
          <Route
            path="feed/post/:postId"
            element={
              <RouteSuspense>
                <PostDetailPage />
              </RouteSuspense>
            }
          />
          <Route path="moments" element={<Navigate to="/app/social?tab=moments" replace />} />
          <Route
            path="moments/view/:momentId"
            element={
              <RouteSuspense>
                <MomentsViewerPage />
              </RouteSuspense>
            }
          />
          <Route path="challenges" element={<Navigate to="/app/social?tab=challenges" replace />} />
          <Route
            path="challenges/ranking"
            element={
              <RouteSuspense>
                <ChallengesRankingPage />
              </RouteSuspense>
            }
          />
          <Route path="reels" element={<Navigate to="/app/videos" replace />} />
          <Route
            path="reels/:videoId"
            element={
              <RouteSuspense>
                <VideoDetailPage />
              </RouteSuspense>
            }
          />
          <Route
            path="messages"
            element={
              <RouteSuspense>
                <MessagesPage />
              </RouteSuspense>
            }
          />
          <Route
            path="messages/novvin"
            element={
              <RouteSuspense>
                <NovvinPage />
              </RouteSuspense>
            }
          />
          <Route
            path="messages/:chatId"
            element={
              <RouteSuspense>
                <ChatDetailPage />
              </RouteSuspense>
            }
          />
          <Route
            path="messages/group/:groupId"
            element={
              <RouteSuspense>
                <GroupChatDetailPage />
              </RouteSuspense>
            }
          />
          <Route
            path="messages/community/:communityId"
            element={
              <RouteSuspense>
                <CommunityChatDetailPage />
              </RouteSuspense>
            }
          />
          <Route
            path="calls"
            element={
              <RouteSuspense>
                <CallsPage />
              </RouteSuspense>
            }
          />
          <Route
            path="voice"
            element={
              <RouteSuspense>
                <VoiceRoomsPage />
              </RouteSuspense>
            }
          />
          <Route
            path="voice/:roomId"
            element={
              <RouteSuspense>
                <VoiceRoomPage />
              </RouteSuspense>
            }
          />
          <Route
            path="live"
            element={
              <RouteSuspense>
                <LiveHubPage />
              </RouteSuspense>
            }
          />
          <Route
            path="live/:liveId"
            element={
              <RouteSuspense>
                <LiveSessionPage />
              </RouteSuspense>
            }
          />
          <Route
            path="proximity"
            element={
              <RouteSuspense>
                <ProximityPage />
              </RouteSuspense>
            }
          />
          <Route
            path="games"
            element={
              <RouteSuspense>
                <GamesPage />
              </RouteSuspense>
            }
          />
          <Route
            path="games/player/:playerId"
            element={
              <RouteSuspense>
                <PlayerDetailPage />
              </RouteSuspense>
            }
          />
          <Route
            path="reports"
            element={
              <RouteSuspense>
                <MyReportsPage />
              </RouteSuspense>
            }
          />
          <Route
            path="help"
            element={
              <RouteSuspense>
                <HelpPage />
              </RouteSuspense>
            }
          />
          <Route
            path="create-space"
            element={
              <RouteSuspense>
                <CreateSpacePage />
              </RouteSuspense>
            }
          />
          <Route
            path="search"
            element={
              <RouteSuspense>
                <SearchPage />
              </RouteSuspense>
            }
          />
          <Route
            path="notifications"
            element={
              <RouteSuspense>
                <NotificationsPage />
              </RouteSuspense>
            }
          />
          <Route
            path="social"
            element={
              <RouteSuspense>
                <SocialPage />
              </RouteSuspense>
            }
          />
          <Route
            path="follows"
            element={
              <RouteSuspense>
                <FollowsPage />
              </RouteSuspense>
            }
          />
          <Route
            path="friend-requests"
            element={
              <RouteSuspense>
                <FriendRequestsPage />
              </RouteSuspense>
            }
          />
          <Route
            path="profile"
            element={
              <RouteSuspense>
                <ProfilePage />
              </RouteSuspense>
            }
          />
          <Route
            path="profile/:profileId"
            element={
              <RouteSuspense>
                <PublicProfilePage />
              </RouteSuspense>
            }
          />
          <Route
            path="settings"
            element={
              <RouteSuspense>
                <AccountSettingsPage />
              </RouteSuspense>
            }
          />
          <Route
            path="monetization"
            element={
              <RouteSuspense>
                <MonetizationCenterPage />
              </RouteSuspense>
            }
          />
          <Route
            path="admin"
            element={
              <RouteSuspense>
                <AdminModerationPage />
              </RouteSuspense>
            }
          />
        </Route>
        <Route path="*" element={<Navigate to="/" replace />} />
      </Routes>
    </BrowserRouter>
  )
}

export default function App() {
  return (
    <AuthProvider>
      <SystemAnnouncementHost />
      <WebOutboxHost />
      <AppRouter />
    </AuthProvider>
  )
}
