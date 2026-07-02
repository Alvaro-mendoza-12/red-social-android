import re

filepath = r'd:\red social - copia\signaling-server\index.js'
with open(filepath, 'r', encoding='utf-8') as f:
    content = f.read()

def replacer(match):
    method = match.group(1)
    message_start = match.group(2)
    message_content = match.group(3)
    
    if '[SOCKET_DEBUG]' in message_content:
        return match.group(0)
    
    if message_start in ("'", '"', '`'):
        new_message = f'{message_start}[SOCKET_DEBUG] {message_content}'
        return f'{method}({new_message}'
    return match.group(0)

# Replace console.log("something") or console.log('something') or console.log(`something`)
pattern = re.compile(r'(console\.(?:log|error|warn|info))\(\s*([\'\"\`])(.*?)(?=(?<!\\)[\'\"\`])', re.DOTALL)
new_content = pattern.sub(replacer, content)

with open(filepath, 'w', encoding='utf-8') as f:
    f.write(new_content)
print(f'Processed {filepath}')
