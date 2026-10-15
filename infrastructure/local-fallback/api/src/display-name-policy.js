const MAX_DISPLAY_NAME_CHARACTERS=20
function normalizedDisplayName(value){return String(value??'').trim().normalize('NFC')}
function displayNameProblem(value){const normalized=normalizedDisplayName(value);return [...normalized].length<2||[...normalized].length>MAX_DISPLAY_NAME_CHARACTERS||/[\u0000-\u001f\u007f-\u009f]/u.test(normalized)?'DISPLAY_NAME_INVALID':null}
function validateDisplayNameChange(value,existing){const normalized=normalizedDisplayName(value);if(normalized===normalizedDisplayName(existing))return normalized;const problem=displayNameProblem(value);if(problem)throw Object.assign(new Error(problem),{status:400,code:problem});return normalized}
module.exports={MAX_DISPLAY_NAME_CHARACTERS,normalizedDisplayName,displayNameProblem,validateDisplayNameChange}
