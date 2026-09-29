export const MODEL_PROFILES = [
  { value: 'ryks', label: 'RYKS' },
  { value: 't56', label: 'T56' },
  { value: 't99', label: 'T99' },
  { value: 'generic-radio', label: 'Generic radio' }
] as const;

export type ModelProfile = typeof MODEL_PROFILES[number]['value'];

export function validModelProfile(value: unknown): value is ModelProfile {
  return typeof value === 'string'
    && MODEL_PROFILES.some((profile) => profile.value === value);
}
