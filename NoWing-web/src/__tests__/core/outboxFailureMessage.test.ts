import {outboxFailureMessage,messageDeliveryFeedback} from '../../core/outboxFailureMessage'

test('saved failures from an older version use concise safe feedback',()=>{
 expect(messageDeliveryFeedback('No se pudo enviar este mensaje. Puedes volver a intentarlo o adjuntar otro archivo.')).toBe('No se envió. Reintenta o adjunta otra copia.')
 expect(messageDeliveryFeedback('SQLSTATE 400 https://private.invalid')).not.toContain('SQLSTATE')
})

test('a revoked media reference explains replacement rather than guessing connection failure',()=>expect(outboxFailureMessage(new Error('CHAT_MEDIA_REFERENCE_FORBIDDEN'))).toContain('ya no está disponible'))
test('a rejected scan explains the action without exposing infrastructure',()=>expect(outboxFailureMessage(new Error('STORAGE_FILE_REJECTED'))).toContain('otra copia'))
test('the message video limit is understandable',()=>expect(outboxFailureMessage(new Error('MESSAGE_VIDEO_DURATION_TOO_LONG'))).toContain('10 minutos'))
test('scan time exhaustion offers retry without treating it as a detected threat',()=>expect(outboxFailureMessage(new Error('STORAGE_SCAN_LIMIT_EXCEEDED'))).toContain('sin volver a adjuntarlo'))
test('encrypted archives have a specific actionable error',()=>expect(outboxFailureMessage(new Error('STORAGE_ARCHIVE_ENCRYPTED'))).toContain('sin contraseña'))
test.each(['SQLSTATE 42804 https://internal.invalid','stack: PRIVATE_TOKEN','STORAGE_SCAN_POLICY_UNVERIFIED'])('does not expose technical error %s',message=>expect(outboxFailureMessage(new Error(message))).not.toMatch(/internal|TOKEN|SQLSTATE|stack|UNVERIFIED/))
