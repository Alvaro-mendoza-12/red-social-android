import {outboxFailureMessage} from '../../core/outboxFailureMessage'
test('a rejected scan explains the action without exposing infrastructure',()=>expect(outboxFailureMessage(new Error('STORAGE_FILE_REJECTED'))).toContain('partes más pequeñas'))
test('the message video limit is understandable',()=>expect(outboxFailureMessage(new Error('MESSAGE_VIDEO_DURATION_TOO_LONG'))).toContain('10 minutos'))
test.each(['SQLSTATE 42804 https://internal.invalid','stack: PRIVATE_TOKEN','STORAGE_SCAN_POLICY_UNVERIFIED'])('does not expose technical error %s',message=>expect(outboxFailureMessage(new Error(message))).not.toMatch(/internal|TOKEN|SQLSTATE|stack|UNVERIFIED/))
