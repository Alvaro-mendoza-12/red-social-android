import { PROFILE_EFFECT_CHOICES, PROFILE_THEME_CHOICES } from './ProfileCoverEffect'

export function ProfileAppearanceControls({ theme, effect, onTheme, onEffect }: {
  theme: string; effect: string; onTheme: (value: string) => void; onEffect: (value: string) => void
}) {
  return <>
    <fieldset className="profile-appearance-field"><legend>Color del perfil</legend>
      <div className="profile-theme-swatches">{PROFILE_THEME_CHOICES.map(choice => <button key={choice.id} type="button" className="profile-theme-swatch" aria-pressed={theme === choice.id} onClick={() => onTheme(choice.id)}>
        <span aria-hidden="true" style={{ backgroundColor: choice.color }}>{theme === choice.id ? '✓' : ''}</span><span>{choice.label}</span>
      </button>)}</div>
    </fieldset>
    <fieldset className="profile-appearance-field"><legend>Animación del fondo</legend>
      <div className="profile-effect-options">{PROFILE_EFFECT_CHOICES.map(([id, label]) => <button key={id} className="chip-btn" type="button" aria-pressed={effect === id} onClick={() => onEffect(id)}>{label}</button>)}</div>
    </fieldset>
  </>
}
