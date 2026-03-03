package conectate.com.util

object Constants {
    // Storage Configuration
    // Set to false if Firebase Storage is not configured (requires Blaze plan)
    const val STORAGE_ENABLED = false
    
    // Firebase Collections
    const val USERS_COLLECTION = "users"
    const val MESSAGES_COLLECTION = "messages"
    const val CONVERSATIONS_COLLECTION = "conversations"
    const val VIDEOS_COLLECTION = "videos"
    const val VIDEO_COMMENTS_COLLECTION = "videoComments"
    const val VOICE_ROOMS_COLLECTION = "voiceRooms"
    const val FOLLOWERS_COLLECTION = "followers"
    const val FOLLOWING_COLLECTION = "following"
    
    // Firebase Storage Paths
    const val STORAGE_USERS = "users"
    const val STORAGE_PROFILE = "profile"
    const val STORAGE_COVER = "cover"
    const val STORAGE_MESSAGES = "messages"
    const val STORAGE_IMAGES = "images"
    const val STORAGE_VIDEOS = "videos"
    const val STORAGE_AUDIO = "audio"
    const val STORAGE_THUMBNAILS = "thumbnails"
    
    // User ID Prefix
    const val USER_ID_PREFIX = "USER"
    
    // Message Status
    const val MESSAGE_STATUS_SENT = "sent"
    const val MESSAGE_STATUS_DELIVERED = "delivered"
    const val MESSAGE_STATUS_READ = "read"
    
    // Message Types
    const val MESSAGE_TYPE_TEXT = "text"
    const val MESSAGE_TYPE_IMAGE = "image"
    const val MESSAGE_TYPE_VIDEO = "video"
    const val MESSAGE_TYPE_AUDIO = "audio"
    
    // DataStore Keys
    const val DATASTORE_NAME = "red_social_prefs"
    const val KEY_USER_ID = "user_id"
    const val KEY_IS_LOGGED_IN = "is_logged_in"
    
    // Pagination
    const val VIDEOS_PAGE_SIZE = 20
    const val MESSAGES_PAGE_SIZE = 50
    
    // Image/Video Compression
    const val MAX_IMAGE_SIZE = 1920
    const val IMAGE_QUALITY = 80
    const val VIDEO_BITRATE = 2000000 // 2Mbps
}
