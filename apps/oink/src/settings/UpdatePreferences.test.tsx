// @vitest-environment jsdom
import { afterEach, expect, it } from 'vitest';
import { cleanup, render } from '@testing-library/react';
import { UpdateChannel, UpdateCheck, UpdatePreferences, manualUpdates } from './UpdatePreferences';
import type { UpdateStatus } from '../api/update';

afterEach(cleanup);

const status: UpdateStatus = {
  current_build: 30, version: '0.3.6', checks_enabled: false, channel: 'stable',
  update_channel: null, install_after_download: false, phase: 'disabled', candidate: null,
  checked_at: null, next_check: null, deferred_until: null, blockers: [], progress: null,
  error: null, failed_build: null, critical_prompted_build: null, scope: 'user', can_change_policy: false,
};

it('omits updater UI controls in local-only builds', () => {
  const { container } = render(<div>
    <UpdateCheck status={status} open />
    <UpdatePreferences status={status} label="Automatically check for updates" />
    <UpdateChannel status={status} label="Update channel" />
  </div>);
  expect(container.textContent).toBe('');
  expect(manualUpdates(status)).toBe(false);
});
