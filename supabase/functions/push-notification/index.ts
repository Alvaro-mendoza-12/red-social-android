import { serve } from "https://deno.land/std@0.168.0/http/server.ts"
import { createClient } from "https://esm.sh/@supabase/supabase-js@2"

/**
 * Edge Function triggered by Webhooks on multiple tables.
 */
serve(async (req) => {
    try {
        if (req.method !== 'POST') {
            return new Response(JSON.stringify({ error: 'METHOD_NOT_ALLOWED' }), {
                status: 405,
                headers: { 'Content-Type': 'application/json', 'Allow': 'POST' },
            })
        }

        const configuredWebhookSecret = Deno.env.get('PUSH_WEBHOOK_SECRET') ?? ''
        if (!configuredWebhookSecret) {
            console.error('[push-notification] PUSH_WEBHOOK_SECRET is not configured')
            return new Response(JSON.stringify({ error: 'SERVICE_NOT_CONFIGURED' }), {
                status: 503,
                headers: { 'Content-Type': 'application/json' },
            })
        }
        const authorization = req.headers.get('authorization') ?? ''
        const bearerSecret = authorization.toLowerCase().startsWith('bearer ')
            ? authorization.slice(7).trim()
            : ''
        const suppliedWebhookSecret = req.headers.get('x-novvin-webhook-secret') ?? bearerSecret
        if (!constantTimeEquals(configuredWebhookSecret, suppliedWebhookSecret)) {
            return new Response(JSON.stringify({ error: 'UNAUTHORIZED' }), {
                status: 401,
                headers: { 'Content-Type': 'application/json' },
            })
        }

        const declaredLength = Number(req.headers.get('content-length') || '0')
        if (Number.isFinite(declaredLength) && declaredLength > 256 * 1024) {
            return new Response(JSON.stringify({ error: 'PAYLOAD_TOO_LARGE' }), {
                status: 413,
                headers: { 'Content-Type': 'application/json' },
            })
        }
        const payload = await req.json()

        // Handle both formats:
        // 1) direct payload: { table, record }
        // 2) Supabase webhook: { type: "INSERT", table, schema, record }
        const eventType = (payload.type || payload.eventType || "").toString().toUpperCase()
        const table = (payload.table || payload.entity || "").toString().trim()
        const record = payload.record || payload.new_record || payload.new || payload

        if (!table || eventType !== "INSERT" || !record || typeof record !== 'object') {
            return new Response(JSON.stringify({ message: "Ignore payload format" }), { status: 200 })
        }

        console.log(JSON.stringify({
            event: 'push_webhook_received',
            table,
            eventType: eventType || 'UNKNOWN',
            recordId: String(record?.id || ''),
        }))

        const supabaseUrl = Deno.env.get('SUPABASE_URL') ?? ''
        const serviceRoleKey = Deno.env.get('SUPABASE_SERVICE_ROLE_KEY') ?? ''
        if (!supabaseUrl || !serviceRoleKey) {
            throw new Error('SUPABASE_EDGE_CONFIGURATION_MISSING')
        }
        const supabase = createClient(supabaseUrl, serviceRoleKey, {
            auth: { persistSession: false, autoRefreshToken: false },
        })

        let recipientIds: string[] = []
        let notificationTitle = "Novvin"
        let notificationBody = ""
        let fcmData: any = {}
        const getExtraDataValue = (key: string): string => {
            try {
                const extra = record?.extra_data
                if (!extra) return ""
                if (typeof extra === "string") {
                    const parsed = JSON.parse(extra)
                    return String(parsed?.[key] ?? "")
                }
                if (typeof extra === "object") {
                    return String(extra?.[key] ?? "")
                }
                return ""
            } catch {
                return ""
            }
        }

        if (table === 'messages') {
            const chatId = record.chat_id
            const senderId = record.sender_id
            const content = record.content || "Nuevo mensaje"

            const { data: chat, error: chatError } = await supabase
                .from('chats')
                .select('participants, is_group, group_name')
                .eq('id', chatId)
                .single()
            if (chatError) throw chatError

            const participants = Array.isArray(chat?.participants) ? chat.participants : []
            const cleanSenderId = String(senderId || '').trim().toLowerCase()
            recipientIds = participants
                .map((id: unknown) => String(id))
                .filter((id: string) => id.trim().toLowerCase() !== cleanSenderId)

            const { data: senderProfile, error: senderError } = await supabase
                .from('profiles')
                .select('full_name, avatar_url')
                .eq('id', senderId)
                .single()
            if (senderError) throw senderError

            const senderName = senderProfile?.full_name || "Usuario"
            const isGroup = chat?.is_group || false
            notificationTitle = isGroup ? (chat?.group_name || "Grupo") : senderName
            notificationBody = isGroup ? `${senderName}: ${content}` : content

            fcmData = {
                id: String(record.id),
                message_id: String(record.id),
                focus_message_id: String(record.id),
                target_id: String(chatId),
                chat_id: String(chatId),
                sender_id: String(senderId),
                sender_name: String(senderName),
                sender_avatar: String(senderProfile?.avatar_url || ""),
                is_group: String(isGroup),
                group_id: isGroup ? String(chatId) : "",
                group_name: String(chat.group_name || ""),
                target_type: isGroup ? "group" : "chat",
                type: "new_message",
                content: String(content),
                content_type: String(record.content_type || "text"),
                media_url: String(record.media_url || ""),
                reply_to_id: record.reply_to_id ? String(record.reply_to_id) : "",
                channel_id: isGroup ? "group_channel" : "chat_channel"
            }
        } else if (table === 'group_messages') {
            const groupId = record.group_id
            const senderId = record.sender_id
            const content = record.content || "Nuevo mensaje"

            // 1. Get recipients (all members except sender)
            const { data: members } = await supabase
                .from('group_members')
                .select('user_id')
                .eq('group_id', groupId)

            recipientIds = (members || [])
                .map((m: any) => m.user_id)
                .filter((id: string) => id.trim().toLowerCase() !== senderId.trim().toLowerCase())

            // 2. Get group and sender info
            const [{ data: group }, { data: senderProfile }] = await Promise.all([
                supabase.from('groups').select('group_name').eq('id', groupId).single(),
                supabase.from('profiles').select('full_name, avatar_url').eq('id', senderId).single()
            ])

            const senderName = senderProfile?.full_name || "Usuario"
            notificationTitle = group?.group_name || "Grupo"
            notificationBody = `${senderName}: ${content}`

            fcmData = {
                id: String(record.id),
                message_id: String(record.id),
                focus_message_id: String(record.id),
                target_id: String(groupId),
                chat_id: String(groupId),
                group_id: String(groupId),
                sender_id: String(senderId),
                sender_name: String(senderName),
                sender_avatar: String(senderProfile?.avatar_url || ""),
                is_group: "true",
                group_name: String(group?.group_name || ""),
                target_type: "group",
                type: "new_message",
                content: String(content),
                content_type: String(record.content_type || "text"),
                media_url: String(record.media_url || ""),
                channel_id: "group_channel"
            }
        } else if (table === 'community_messages') {
            const communityId = record.community_id
            const senderId = record.sender_id
            const content = record.content || "Nuevo mensaje"

            // 1. Get recipients (all members except sender)
            const { data: members } = await supabase
                .from('community_members')
                .select('user_id')
                .eq('community_id', communityId)

            recipientIds = (members || [])
                .map((m: any) => m.user_id)
                .filter((id: string) => id.trim().toLowerCase() !== senderId.trim().toLowerCase())

            // 2. Get community and sender info
            const [{ data: community }, { data: senderProfile }] = await Promise.all([
                supabase.from('communities').select('name').eq('id', communityId).single(),
                supabase.from('profiles').select('full_name, avatar_url').eq('id', senderId).single()
            ])

            const senderName = senderProfile?.full_name || "Usuario"
            notificationTitle = community?.name || "Comunidad"
            notificationBody = `${senderName}: ${content}`

            fcmData = {
                id: String(record.id),
                message_id: String(record.id),
                focus_message_id: String(record.id),
                target_id: String(communityId),
                chat_id: String(communityId),
                community_id: String(communityId),
                sender_id: String(senderId),
                sender_name: String(senderName),
                sender_avatar: String(senderProfile?.avatar_url || ""),
                is_community: "true",
                community_name: String(community?.name || ""),
                target_type: "community",
                type: "new_message",
                content: String(content),
                content_type: String(record.content_type || "text"),
                media_url: String(record.media_url || ""),
                channel_id: "group_channel"
            }
        } else if (table === 'notifications') {
            const recipientId = record.recipient_id
            const actorId = record.actor_id
            const type = record.type
            const content = record.content
            const commentId = getExtraDataValue("comment_id")
            const focusCommentId = getExtraDataValue("focus_comment_id")
            const parentId = getExtraDataValue("parent_id")
            const messageId = getExtraDataValue("message_id")
            const focusMessageId = getExtraDataValue("focus_message_id")
            const groupId = getExtraDataValue("group_id")
            const communityId = getExtraDataValue("community_id")
            const callId = getExtraDataValue("call_id") || String(record.target_id || "")
            const callContextType = getExtraDataValue("call_context_type") || getExtraDataValue("context_type")
            const callContextId = getExtraDataValue("call_context_id") || getExtraDataValue("context_id")
            const callTitle = getExtraDataValue("call_title") || ""
            const callAction = getExtraDataValue("call_action") || ""
            const callCreatedAt = getExtraDataValue("call_created_at") || ""
            const explicitPostId = getExtraDataValue("post_id")
            const explicitVideoId = getExtraDataValue("video_id")
            const targetType = String(record.target_type || "")
            const normalizedType = String(type || "").trim().toLowerCase()
            const normalizedTargetType = targetType.trim().toLowerCase()
            const resolvedCommentId = commentId || focusCommentId
            const resolvedMessageId = messageId || focusMessageId

            recipientIds = [recipientId]

            const { data: actorProfile } = await supabase
                .from('profiles')
                .select('full_name, avatar_url')
                .eq('id', actorId)
                .single()

            const actorName = actorProfile?.full_name || "Alguien"

            // Generate body based on type
            switch (type) {
                case 'like_video': notificationBody = `${actorName} le dio like a tu video`; break;
                case 'like_post': notificationBody = `${actorName} le dio like a tu publicación`; break;
                case 'like': notificationBody = `${actorName} le dio like a tu publicación`; break;
                case 'comment_video': notificationBody = `${actorName} comentó tu video`; break;
                case 'reply_video': notificationBody = `${actorName} respondió tu comentario`; break;
                case 'mention_video': notificationBody = `${actorName} te mencionó en un comentario de video`; break;
                case 'comment_post': notificationBody = `${actorName} comentó tu publicación`; break;
                case 'comment': notificationBody = `${actorName} comentó tu publicación`; break;
                case 'reply_post': notificationBody = `${actorName} respondió tu comentario`; break;
                case 'mention_post': notificationBody = `${actorName} te mencionó en un comentario`; break;
                case 'mention':
                    notificationBody = normalizedTargetType === "video"
                        ? `${actorName} te mencionó en un comentario de video`
                        : `${actorName} te mencionó en un comentario`
                    break;
                case 'follow': notificationBody = `${actorName} comenzó a seguirte`; break;
                case 'friend_request': notificationBody = `${actorName} te envió una solicitud de amistad`; break;
                case 'share_video': notificationBody = `${actorName} compartió tu video`; break;
                case 'share_post': notificationBody = `${actorName} compartió tu publicación`; break;
                case 'share': notificationBody = `${actorName} compartió tu publicación`; break;
                case 'admin_warning_video': notificationBody = `Tu video recibió una advertencia de moderación`; break;
                case 'call_invite': notificationBody = `${actorName} te está llamando`; break;
                case 'call_cancel':
                    notificationBody = callAction === "end"
                        ? `${actorName} finalizó la llamada`
                        : callAction === "missed"
                            ? `La llamada expiró antes de conectarse`
                            : `${actorName} canceló la llamada`
                    break;
                default: notificationBody = content || `Nueva actividad social de ${actorName}`;
            }

            const targetId = String(record.target_id || "")
            const postLikeType = normalizedType.includes("post") ||
                normalizedType === "like" ||
                normalizedType === "comment" ||
                normalizedType === "reply" ||
                normalizedType === "mention" ||
                normalizedType === "share"
            const videoLikeType = normalizedType.includes("video") || normalizedType === "admin_warning_video"
            const isMessageType = normalizedType === "new_message"
            const inferredTargetType = normalizedTargetType ||
                (videoLikeType ? "video" : (postLikeType ? "post" : (communityId ? "community" : (groupId ? "group" : (isMessageType ? "chat" : "")))))

            const resolvedPostId = explicitPostId ||
                ((inferredTargetType === "post" || (postLikeType && inferredTargetType !== "video")) ? targetId : "")
            const resolvedVideoId = explicitVideoId ||
                ((inferredTargetType === "video" || videoLikeType) ? targetId : "")
            const resolvedChatId = isMessageType ? (targetId || communityId || groupId) : ""

            fcmData = {
                id: String(record.id),
                type: String(type),
                sender_id: String(actorId),
                sender_name: String(actorName),
                sender_avatar: String(actorProfile?.avatar_url || ""),
                target_id: targetId,
                target_type: inferredTargetType,
                call_id: callId,
                call_context_type: callContextType,
                call_context_id: callContextId,
                call_title: callTitle,
                call_action: callAction,
                call_created_at: callCreatedAt,
                comment_id: resolvedCommentId,
                focus_comment_id: focusCommentId || resolvedCommentId,
                parent_id: parentId,
                message_id: resolvedMessageId,
                focus_message_id: focusMessageId || resolvedMessageId,
                chat_id: resolvedChatId,
                group_id: groupId,
                community_id: communityId,
                post_id: resolvedPostId,
                video_id: resolvedVideoId,
                body: String(notificationBody),
                channel_id: normalizedType === "call_invite" ? "call_channel" : "social_channel"
            }
        } else {
            return new Response(JSON.stringify({ message: "Ignore table" }), { status: 200 })
        }

        recipientIds = recipientIds
            .map((id) => String(id).trim().toLowerCase())
            .filter((id) => id.length > 0)
        recipientIds = [...new Set(recipientIds)]

        if (recipientIds.length === 0) {
            return new Response(JSON.stringify({ message: "No recipients" }), { status: 200 })
        }

        // 3. Get FCM tokens for those recipients
        const tokens = await loadFcmTokens(supabase, recipientIds)
        if (!tokens || tokens.length === 0) {
            return new Response(JSON.stringify({ message: "No tokens" }), { status: 200 })
        }

        // 5. Get FCM Access Token and Project ID from Service Account
        const serviceAccount = JSON.parse(Deno.env.get('FCM_SERVICE_ACCOUNT') || '{}')
        const projectId = serviceAccount.project_id
        const fcmAccessToken = await getFcmAccessToken(serviceAccount)

        if (!fcmAccessToken || !projectId) {
            throw new Error("Failed to initialize FCM authentication")
        }

        const FIREBASE_MESSAGING_URL = `https://fcm.googleapis.com/v1/projects/${projectId}/messages:send`

        // 6. Send notifications via FCM
        const sendOne = async (t: { token: string }) => {
            const isCallInvite = String(fcmData?.type || "").trim().toLowerCase() === "call_invite"
            const ttlValue = isCallInvite ? "45s" : "86400s"
            const normalizedData = Object.fromEntries(
                Object.entries(fcmData || {}).map(([key, value]) => [key, value == null ? "" : String(value)])
            )
            const messageBody = {
                message: {
                    token: t.token,
                    data: normalizedData,
                    android: {
                        priority: "high",
                        ttl: ttlValue,
                        direct_boot_ok: true
                    },
                    apns: {
                        headers: {
                            "apns-priority": "10", // High priority
                            "apns-expiration": `${Math.floor(Date.now() / 1000) + (isCallInvite ? 45 : 86400)}`
                        },
                        payload: {
                            aps: {
                                ...(isCallInvite ? {} : {
                                    alert: {
                                        title: notificationTitle,
                                        body: notificationBody,
                                    },
                                }),
                                "content-available": 1, // For background data refresh
                            },
                        },
                    },
                    fcm_options: {
                        analytics_label: "notification_sent",
                    },
                },
            }

            // Android uses data-only payloads so MyFirebaseMessagingService can build the final
            // local notification immediately with the app's MessagingStyle/actions.

            const res = await fetch(FIREBASE_MESSAGING_URL, {
                method: "POST",
                headers: {
                    "Content-Type": "application/json",
                    "Authorization": `Bearer ${fcmAccessToken}`
                },
                body: JSON.stringify(messageBody)
            })
            const responseText = await res.text()
            return {
                ok: res.ok,
                status: res.status,
                providerCode: safeFcmErrorCode(responseText),
            }
        }
        const sendResults = await mapWithConcurrency(tokens, 20, sendOne)

        const delivered = sendResults.filter((result) => result.ok).length
        return new Response(JSON.stringify({
            success: delivered === sendResults.length,
            attempted: sendResults.length,
            delivered,
            failed: sendResults.length - delivered,
        }), {
            status: delivered === sendResults.length ? 200 : 207,
            headers: { 'Content-Type': 'application/json' },
        })
    } catch (error) {
        const message = error instanceof Error ? error.message : 'UNKNOWN_ERROR'
        console.error('[push-notification] request failed:', message)
        return new Response(JSON.stringify({ error: 'PUSH_DELIVERY_FAILED' }), {
            status: 500,
            headers: { 'Content-Type': 'application/json' },
        })
    }
})

function constantTimeEquals(expected: string, supplied: string): boolean {
    const encoder = new TextEncoder()
    const left = encoder.encode(expected)
    const right = encoder.encode(supplied)
    const maxLength = Math.max(left.length, right.length)
    let difference = left.length ^ right.length
    for (let index = 0; index < maxLength; index++) {
        difference |= (left[index] ?? 0) ^ (right[index] ?? 0)
    }
    return difference === 0
}

async function loadFcmTokens(
    supabase: any,
    recipientIds: string[],
): Promise<Array<{ token: string }>> {
    const uniqueTokens = new Set<string>()
    for (let offset = 0; offset < recipientIds.length; offset += 100) {
        const batch = recipientIds.slice(offset, offset + 100)
        const { data, error } = await supabase
            .from('fcm_tokens')
            .select('token')
            .in('user_id', batch)
        if (error) throw error
        for (const row of data || []) {
            const token = String(row?.token || '').trim()
            if (token) uniqueTokens.add(token)
        }
    }
    return [...uniqueTokens].map((token) => ({ token }))
}

async function mapWithConcurrency<T, R>(
    values: T[],
    concurrency: number,
    transform: (value: T) => Promise<R>,
): Promise<R[]> {
    const results = new Array<R>(values.length)
    let nextIndex = 0
    const workers = Array.from(
        { length: Math.min(Math.max(concurrency, 1), values.length) },
        async () => {
            while (true) {
                const index = nextIndex++
                if (index >= values.length) return
                results[index] = await transform(values[index])
            }
        },
    )
    await Promise.all(workers)
    return results
}

function safeFcmErrorCode(responseText: string): string {
    try {
        const payload = JSON.parse(responseText)
        const code = String(payload?.error?.status || payload?.error?.details?.[0]?.errorCode || '')
        return /^[A-Z0-9_]{1,80}$/.test(code) ? code : ''
    } catch {
        return ''
    }
}

/**
 * Minimal implementation to generate a Google OAuth2 access token for FCM
 * using basic JWT signing (RS256) without heavy external libraries.
 */
async function getFcmAccessToken(serviceAccount: any) {
    const { client_email, private_key } = serviceAccount;
    if (!client_email || !private_key) return null;

    const header = { alg: "RS256", typ: "JWT" };
    const now = Math.floor(Date.now() / 1000);
    const payload = {
        iss: client_email,
        scope: "https://www.googleapis.com/auth/firebase.messaging",
        aud: "https://oauth2.googleapis.com/token",
        exp: now + 3600,
        iat: now,
    };

    const encodedHeader = base64UrlEncode(JSON.stringify(header));
    const encodedPayload = base64UrlEncode(JSON.stringify(payload));
    const unsignedToken = `${encodedHeader}.${encodedPayload}`;

    // Sign the token using the private key
    // In Deno, we use the Web Crypto API
    const pem = private_key.replace(/\\n/g, "\n");
    const binaryKey = str2ab(pem.replace(/-----(BEGIN|END) PRIVATE KEY-----/g, "").replace(/\s/g, ""));
    const key = await crypto.subtle.importKey(
        "pkcs8",
        binaryKey,
        { name: "RSASSA-PKCS1-v1_5", hash: "SHA-256" },
        false,
        ["sign"]
    );

    const signature = await crypto.subtle.sign(
        "RSASSA-PKCS1-v1_5",
        key,
        new TextEncoder().encode(unsignedToken)
    );

    const encodedSignature = base64UrlEncodeBytes(new Uint8Array(signature))

    const jwt = `${unsignedToken}.${encodedSignature}`;

    // Exchange JWT for Access Token
    const res = await fetch("https://oauth2.googleapis.com/token", {
        method: "POST",
        headers: { "Content-Type": "application/x-www-form-urlencoded" },
        body: `grant_type=urn:ietf:params:oauth:grant-type:jwt-bearer&assertion=${jwt}`,
    });

    const data = await res.json();
    return data.access_token;
}

function base64UrlEncode(value: string) {
    return btoa(value)
        .replace(/\+/g, "-")
        .replace(/\//g, "_")
        .replace(/=+$/, "");
}

function base64UrlEncodeBytes(bytes: Uint8Array) {
    return btoa(String.fromCharCode(...bytes))
        .replace(/\+/g, "-")
        .replace(/\//g, "_")
        .replace(/=+$/, "");
}

function str2ab(str: string) {
    const binary = atob(str);
    const buf = new ArrayBuffer(binary.length);
    const bufView = new Uint8Array(buf);
    for (let i = 0, strLen = binary.length; i < strLen; i++) {
        bufView[i] = binary.charCodeAt(i);
    }
    return buf;
}
