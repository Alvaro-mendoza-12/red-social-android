# Red Social Android - Setup Guide

## 🎯 Overview

This project now includes all necessary implementations for a production-ready social media application combining features from WhatsApp, TikTok, and Instagram.

## 📋 Features Implemented

### ✅ Authentication & Session Management
- ✅ Persistent session with Supabase
- ✅ Automatic session restoration on app restart
- ✅ Secure login/logout flow
- ✅ Registration with profile creation

### ✅ User Search & Messaging
- ✅ Search users by name (case-insensitive, partial match)
- ✅ Search users by numeric ID (exact match)
- ✅ Send text messages
- ✅ Send images, videos, audio files
- ✅ Message status indicators
- ✅ Real-time message updates

### ✅ Group Chats
- ✅ Create groups with custom names
- ✅ Add multiple participants
- ✅ Group messaging
- ✅ Display group info in chat list

### ✅ Video Feed (TikTok-style)
- ✅ "Para ti" feed (all public videos)
- ✅ "Siguiendo" feed (videos from followed users)
- ✅ Upload videos with title and hashtags
- ✅ Public/Private video privacy
- ✅ Videos appear in feed and profile immediately
- ✅ Automatic post_count increment

### ✅ User Profile
- ✅ Edit profile (name, bio, avatar)
- ✅ 7-day name change cooldown enforcement
- ✅ Dynamic counters (posts, followers, following)
- ✅ Follower/Following lists
- ✅ Video grid on profile
- ✅ Follow/Unfollow functionality

### ✅ Follow System
- ✅ Follow/Unfollow users
- ✅ Automatic counter updates
- ✅ View followers list
- ✅ View following list
- ✅ Check follow status

## 🗄️ Database Setup

### Step 1: Create Supabase Project

1. Go to [https://app.supabase.com](https://app.supabase.com)
2. Create a new project
3. Note the **Project URL** and **anon key**

### Step 2: Execute Database Schema

1. Open your Supabase project
2. Go to **SQL Editor**
3. Copy the entire contents of `database_schema.sql`
4. Paste and execute the SQL

This will create:
- All required tables with proper relationships
- Row Level Security (RLS) policies
- RPC functions for counter updates
- Indexes for optimal performance
- Triggers for automatic timestamp updates

### Step 3: Create Storage Buckets

Go to **Storage** in Supabase Dashboard and create these buckets:

| Bucket Name | Purpose | Public Access |
|------------|---------|---------------|
| `videos` | Video files | Yes |
| `thumbnails` | Video thumbnails | Yes |
| `avatars` | Profile pictures | Yes |
| `images` | General images | Yes |
| `chat-media` | Chat attachments | Yes |

**For each bucket**, set the policy to allow authenticated uploads:

```sql
-- Insert policy for authenticated users
CREATE POLICY "Authenticated users can upload"
ON storage.objects FOR INSERT
TO authenticated
WITH CHECK (bucket_id = 'videos');
```

Replace `videos` with each bucket name.

### Step 4: Configure App Secrets

1. Copy `secrets.properties.example` to `secrets.properties`
2. Add your Supabase credentials:

```properties
SUPABASE_URL=https://your-project.supabase.co
SUPABASE_ANON_KEY=your-anon-key-here
```

## 🚀 Running the App

### Prerequisites
- Android Studio Hedgehog (2023.1.1) or newer
- JDK 17
- Android SDK API 26-34

### Build & Run

1. Open project in Android Studio
2. Sync Gradle files
3. Run the app (Shift + F10)

## 🧪 Testing the Implementation

### Test Authentication
1. Launch app → Should check session first
2. If no session → Shows Login screen
3. Register new account
4. **Close and reopen app** → Should go directly to Home (sesión persistente ✅)
5. Logout → **Reopen app** → Should show Login screen

### Test User Search
1. Search for "test" → Should find users with "Test" in name
2. Search for numeric ID → Should find exact user

### Test Messaging
1. Start chat with a user
2. Send text message ✅
3. Send image ✅
4. Send video ✅
5. Send audio ✅

### Test Groups
1. Create group → Set name → Select participants
2. Group appears in Messages list
3. Send message in group
4. Verify all participants can see it (test with multiple accounts)

### Test Video Upload
1. Go to Videos tab
2. Tap upload button
3. Select video → Add title and hashtags
4. Set privacy (Public/Private)
5. Upload
6. **Video should appear immediately in:**
   - Feed ("Para ti")
   - Your profile
7. **Check profile** → "Publicaciones" counter should increment

### Test Profile & Follow
1. Go to Profile → Edit Profile
2. Change name → Should work first time
3. Try changing name again → Should show "Puedes cambiartun nombre en X días"
4. Visit another user's profile → Click Follow
5. **Check their profile** → "Seguidores" should increment
6. **Check your profile** → "Siguiendo" should increment
7. Click "Seguidores" → Should show follower list
8. Click "Siguiendo" → Should show following list

## 📱 Key Files Modified/Created

### New Files
- `FollowRepository.kt` - Follow/unfollow operations
- `database_schema.sql` - Complete database schema

### Modified Files
- `MainActivity.kt` - Session-aware navigation
- `UserRepository.kt` - 7-day name change restriction
- `VideoRepository.kt` - Post count increment
- `AuthService.kt` - Session persistence (already had it)

## 🔐 Security Notes

- All tables have Row Level Security (RLS) enabled
- Users can only modify their own content
- Follows and likes are tracked with unique constraints
- Counter updates use atomic RPC functions
- Private videos only visible to followers

## 🛠️ Troubleshooting

### App crashes on startup
- Check Logcat for errors
- Verify `secrets.properties` has correct Supabase credentials
- Ensure database schema is executed

### Videos don't upload
- Check Storage buckets are created
- Verify bucket policies allow authenticated uploads
- Check device permissions for file access

### Counters don't update
- Verify RPC functions are created in database
- Check Supabase logs for errors

### Search doesn't work
- Verify profiles table has data
- Check if display_name column exists

## 📚 Architecture

```
app/
├── data/
│   ├── model/          # Data models (@Serializable)
│   ├── repository/     # Data layer (Supabase integration)
│   └── supabase/       # Supabase services
├── presentation/
│   ├── auth/           # Login, Register
│   ├── messages/       # Chat, Groups
│   ├── video/          # Video Feed
│   ├── videos/         # Video Upload
│   └── profile/        # Profile, Settings
└── di/                 # Dependency Injection
```

## 🎉 Production Ready Checklist

- [x] Session persistence
- [x] User authentication
- [x] User search
- [x] Messaging (text + multimedia)
- [x] Group chats
- [x] Video upload & feed
- [x] Follow system
- [x] Profile editing with restrictions
- [x] Dynamic counters
- [x] RLS policies
- [x] Performance indexes
- [x] Error handling

## 🚀 Next Steps (Optional Enhancements)

- [ ] Push notifications (FCM)
- [ ] Video trimming in-app
- [ ] Story feature
- [ ] Live streaming
- [ ] Analytics dashboard
