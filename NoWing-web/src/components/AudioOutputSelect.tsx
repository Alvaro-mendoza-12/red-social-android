export function AudioOutputSelect({ devices, value, onChange }: { devices: MediaDeviceInfo[]; value: string; onChange: (id: string) => void }) {
  const supported = typeof HTMLMediaElement !== 'undefined' && 'setSinkId' in HTMLMediaElement.prototype
  if (!supported) return <small>La salida de audio se elige desde los ajustes de sonido de tu dispositivo.</small>
  return <label>Salida de audio<select aria-label="Salida de audio" className="text-input" value={value} onChange={event => onChange(event.target.value)}>
    <option value="">Salida predeterminada</option>
    {devices.filter(device => device.kind === 'audiooutput' && device.deviceId && device.deviceId !== 'default').map((device, index) => <option key={device.deviceId} value={device.deviceId}>{device.label || `Altavoz ${index + 1}`}</option>)}
  </select></label>
}
