const NON_REPLY_TARGETS = 'button,a,input,textarea,video,audio,details,summary,[role="button"],[contenteditable="true"]'

/** Avoid turning a double-click on a message control into an unintended reply. */
export function shouldReplyToMessageDoubleClick(target: EventTarget | null, selecting = false): boolean {
  if (selecting) return false
  return !(target instanceof Element && target.closest(NON_REPLY_TARGETS))
}
