import pathlib
import re
import unittest
import xml.etree.ElementTree as ET
ROOT = pathlib.Path(__file__).resolve().parents[2]
RESOURCES = ROOT / 'app/src/main/res'
ANDROID_NAME = '{http://schemas.android.com/apk/res/android}name'

class AndroidAuditResourcesTest(unittest.TestCase):
    def test_all_referenced_new_strings_exist_in_every_supported_language(self):
        references = set()
        for path in (ROOT / 'app/src/main/java').rglob('*.kt'):
            references.update(re.findall(r'R\.string\.(audit_\w+)', path.read_text()))
        self.assertTrue(references)
        # Derive coverage from Android's declared languages, not a second list
        # that can accidentally omit the explicit Spanish locale.
        locales = [entry.attrib[ANDROID_NAME] for entry in
                   ET.parse(RESOURCES / 'xml/locales_config.xml').getroot()]
        self.assertTrue(locales)
        folders = ['values'] + [
            'values-b+' + locale.replace('-', '+') if '-' in locale
            else 'values-' + locale for locale in locales
        ]
        default_names = {entry.attrib['name'] for entry in
                         ET.parse(RESOURCES / 'values/audit_strings.xml').getroot()}
        for folder in folders:
            with self.subTest(folder=folder):
                path = RESOURCES / folder / 'audit_strings.xml'
                self.assertTrue(path.is_file(), path)
                entries = list(ET.parse(path).getroot())
                names = [entry.attrib['name'] for entry in entries]
                self.assertEqual(len(names), len(set(names)), path)
                self.assertEqual(default_names, set(names), path)
                self.assertFalse(references.difference(names), path)
                self.assertTrue(all(entry.text and entry.text.strip() for entry in entries), path)
                self.assertTrue(all(entry.get('translatable') != 'false' for entry in entries), path)

    def test_spanish_locale_matches_spanish_fallback(self):
        def strings(folder):
            return {entry.attrib['name']: entry.text for entry in
                    ET.parse(RESOURCES / folder / 'audit_strings.xml').getroot()}
        self.assertEqual(strings('values'), strings('values-es'))

    def test_audited_audio_actions_have_action_labels(self):
        code = (ROOT / 'app/src/main/java/conectate/com/presentation/social/SocialScreen.kt').read_text()
        code = code[code.index('fun AudioSubmissionDialog'):]
        self.assertNotRegex(code, r'Icon\(Icons\.Filled\.(Mic|Pause|Stop|Delete|RestartAlt), null')
        for key in ('record', 'pause_recording', 'stop_recording', 'resume_recording', 'delete_recording', 'pause_preview', 'play_preview'):
            self.assertIn('R.string.audit_' + key, code)
