import type { UpdateStatus } from '../api/update';

export function UpdateCheck(_props: { status: UpdateStatus | null; open: boolean }) {
  return null;
}

export const manualUpdates = (_status: UpdateStatus | null) => false;

export function UpdatePreferences(_props: { status: UpdateStatus | null; label: string }) {
  return null;
}

export function UpdateChannel(_props: { status: UpdateStatus | null; label: string }) {
  return null;
}
