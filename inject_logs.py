import os
import re

def process_file(filepath, tag):
    if not os.path.exists(filepath):
        print(f'File not found: {filepath}')
        return
    with open(filepath, 'r', encoding='utf-8') as f:
        content = f.read()
    
    def replacer(match):
        method = match.group(1)
        tag_str = match.group(2)
        message_start = match.group(3)
        message_content = match.group(4)
        
        if tag in message_content:
            return match.group(0)
        
        if message_start in ('"', '"""'):
            new_message = f'{message_start}{tag} {message_content}'
            return f'{method}({tag_str}, {new_message}'
        return match.group(0)

    pattern = re.compile(r'(Log\.[deiwv])\(\s*([^,]+)\s*,\s*(\"|\"\"\")(.*?)(?=(?<!\\)\"|\"\"\")', re.DOTALL)
    new_content = pattern.sub(replacer, content)
    
    # Let's also add some hardcoded logs for key events if they don't exist
    if tag == '[VOICE_DEBUG]':
        # Add a log at the beginning of connect()
        if 'fun connect(' in new_content and '[VOICE_DEBUG] connect() called' not in new_content:
            new_content = new_content.replace('fun connect(', 'fun connect(\n        android.util.Log.d("VoiceDebug", "[VOICE_DEBUG] connect() called")')
        if 'fun joinRoom(' in new_content and '[VOICE_DEBUG] joinRoom() called' not in new_content:
            new_content = new_content.replace('fun joinRoom(', 'fun joinRoom(\n        android.util.Log.d("VoiceDebug", "[VOICE_DEBUG] joinRoom() called")')
    
    if tag == '[WEBRTC_DEBUG]':
        if 'private fun createOffer(' in new_content and '[WEBRTC_DEBUG] createOffer() called' not in new_content:
            new_content = new_content.replace('private fun createOffer(', 'private fun createOffer(\n        android.util.Log.d("WebRTCDebug", "[WEBRTC_DEBUG] createOffer() called")')
        if 'private fun createAnswer(' in new_content and '[WEBRTC_DEBUG] createAnswer() called' not in new_content:
            new_content = new_content.replace('private fun createAnswer(', 'private fun createAnswer(\n        android.util.Log.d("WebRTCDebug", "[WEBRTC_DEBUG] createAnswer() called")')

    with open(filepath, 'w', encoding='utf-8') as f:
        f.write(new_content)
    print(f'Processed {filepath}')

base_dir = r'd:\red social - copia\app\src\main\java\conectate\com'

process_file(base_dir + r'\data\repository\VoiceRoomRepository.kt', '[VOICE_DEBUG]')
process_file(base_dir + r'\data\webrtc\WebRTCManager.kt', '[WEBRTC_DEBUG]')
process_file(base_dir + r'\data\localfallback\UbuntuRealtimeClient.kt', '[REALTIME_DEBUG]')
process_file(base_dir + r'\data\repository\CallRepository.kt', '[CALL_DEBUG]')
