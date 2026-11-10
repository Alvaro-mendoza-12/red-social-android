import pathlib
import re
import unittest
import xml.etree.ElementTree as ET
ROOT = pathlib.Path(__file__).resolve().parents[2]

class AndroidAuditResourcesTest(unittest.TestCase):
    def test_all_referenced_new_strings_exist_in_every_supported_language(self):
        references = set()
        for path in (ROOT / 'app/src/main/java').rglob('*.kt'):
            references.update(re.findall(r'R\.string\.(audit_\w+)', path.read_text()))
        self.assertTrue(references)
        folders = ['values', 'values-en', 'values-pt', 'values-fr', 'values-de', 'values-ja', 'values-b+zh+Hans', 'values-b+zh+Hant']
        for folder in folders:
            path = ROOT / 'app/src/main/res' / folder / 'audit_strings.xml'
            entries = list(ET.parse(path).getroot())
            names = [entry.attrib['name'] for entry in entries]
            self.assertEqual(len(names), len(set(names)), path)
            self.assertFalse(references.difference(names), path)
            self.assertTrue(all(entry.text for entry in entries), path)

    def test_audited_audio_actions_have_action_labels(self):
        code = (ROOT / 'app/src/main/java/conectate/com/presentation/social/SocialScreen.kt').read_text()
        code = code[code.index('fun AudioSubmissionDialog'):]
        self.assertNotRegex(code, r'Icon\(Icons\.Filled\.(Mic|Pause|Stop|Delete|RestartAlt), null')
        for key in ('record', 'pause_recording', 'stop_recording', 'resume_recording', 'delete_recording', 'pause_preview', 'play_preview'):
            self.assertIn('R.string.audit_' + key, code)
