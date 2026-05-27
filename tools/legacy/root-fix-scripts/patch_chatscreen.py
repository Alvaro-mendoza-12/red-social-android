import re

file_path = r'd:\red social\app\src\main\java\conectate\com\presentation\messages\ChatScreen.kt'
with open(file_path, 'r', encoding='utf-8') as f:
    content = f.read()

# Pattern to match the chatName and chatImage derivation inside the Success state block
pattern = r'(val successState = chatState as\? ChatUiState\.Success\s+val chat = successState\?\.chat\s+val otherUser = successState\?\.otherUser\s+)\s+val chatName = if \(chat\?\.is_group == true\) chat\.group_name \?\: \"Grupo\" \s+else otherUser\?\.displayName \?\: otherUser\?\.username \?\: \"Usuario\"\s+val chatImage = if \(chat\?\.is_group == true\) chat\.group_image \s+else otherUser\?\.avatar_url'

replacement = r'\1\n                  // Use chat metadata as primary fallback to avoid "Usuario" flicker\n                  val chatName = if (chat?.is_group == true) chat.group_name ?: "Grupo" \n                                else (currentNickname ?: chat?.nickname ?: otherUser?.displayName ?: otherUser?.username ?: chat?.group_name ?: "Usuario")\n                  \n                  val chatImage = if (chat?.is_group == true) chat.group_image \n                                 else (otherUser?.avatar_url ?: chat?.group_image)'

new_content = re.sub(pattern, replacement, content, flags=re.MULTILINE)

if new_content != content:
    with open(file_path, 'w', encoding='utf-8') as f:
        f.write(new_content)
    print("SUCCESS: File updated.")
else:
    # Try a more relaxed pattern if the first one failed
    print("WARNING: Exact pattern not found. Trying relaxed pattern...")
    relaxed_pattern = r'val chatName = if \(chat\?\.is_group == true\) chat\.group_name \?\: \"Grupo\"\s+else otherUser\?\.displayName \?\: otherUser\?\.username \?\: \"Usuario\"'
    relaxed_replacement = r'val chatName = if (chat?.is_group == true) chat.group_name ?: "Grupo" \n                                else (currentNickname ?: chat?.nickname ?: otherUser?.displayName ?: otherUser?.username ?: chat?.group_name ?: "Usuario")'
    new_content = re.sub(relaxed_pattern, relaxed_replacement, content)
    
    if new_content != content:
        with open(file_path, 'w', encoding='utf-8') as f:
            f.write(new_content)
        print("SUCCESS: File updated with relaxed pattern.")
    else:
        print("FAILURE: Pattern not found.")
