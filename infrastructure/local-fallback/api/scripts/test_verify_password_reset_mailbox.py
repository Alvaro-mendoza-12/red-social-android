"""Pure parser checks; these tests never connect to a mailbox or an API."""
import importlib.util
from pathlib import Path
import unittest
from email.message import EmailMessage


spec = importlib.util.spec_from_file_location('mailbox_audit', Path(__file__).with_name('verify-password-reset-mailbox.py'))
audit = importlib.util.module_from_spec(spec)
spec.loader.exec_module(audit)


class RecoveryCodeParserTest(unittest.TestCase):
    sender = 'service@example.invalid'
    recipient = 'service+qa@example.invalid'

    def message(self, code='001234', **headers):
        message = EmailMessage()
        message['From'] = headers.get('sender', self.sender)
        message['To'] = headers.get('recipient', self.recipient)
        message['Subject'] = headers.get('subject', 'Novvin: recupera tu contraseña')
        message.set_content('Código de recuperación: ' + code)
        return message

    def extract(self, message):
        return audit.recovery_code(message.as_bytes(), self.recipient, self.sender)

    def test_leading_zeroes_are_preserved(self):
        self.assertEqual(self.extract(self.message()), '001234')

    def test_unrelated_recipients_are_ignored(self):
        self.assertIsNone(self.extract(self.message(recipient='other@example.invalid')))

    def test_other_senders_and_subjects_are_ignored(self):
        self.assertIsNone(self.extract(self.message(sender='other@example.invalid')))
        self.assertIsNone(self.extract(self.message(subject='Other mail')))

    def test_requires_exactly_six_digits(self):
        for code in ['12345', '1234567', 'abcdef', 'legacy-reset-token']:
            with self.subTest(code=code):
                self.assertIsNone(self.extract(self.message(code)))

    def test_does_not_read_codes_from_attachments(self):
        message = self.message('not-a-code')
        message.add_attachment('Código de recuperación: 123456', subtype='plain', filename='unrelated.txt')
        self.assertIsNone(self.extract(message))


if __name__ == '__main__':
    unittest.main()
