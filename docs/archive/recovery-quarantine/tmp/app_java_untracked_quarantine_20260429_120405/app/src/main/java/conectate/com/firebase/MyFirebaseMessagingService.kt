package conectate.com.firebase

import com.google.firebase.messaging.FirebaseMessagingService

@Deprecated("Use conectate.com.data.service.MyFirebaseMessagingService instead.")
class MyFirebaseMessagingService : FirebaseMessagingService() {
    override fun onNewToken(token: String) = Unit
}
