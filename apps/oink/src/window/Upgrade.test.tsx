// @vitest-environment jsdom
import { afterEach, expect, it } from 'vitest';
import { cleanup, render, screen } from '@testing-library/react';
import { UpgradeButton, UpgradeDialog, closeUpdateDialog, openUpdateDialog, releaseNotesForLanguage, updatePhaseText } from './Upgrade';
import { setLanguage } from '../i18n';

afterEach(cleanup);

it('renders no upgrade button or dialog in local-only builds', () => {
  openUpdateDialog();
  const { container } = render(<div><UpgradeButton /><UpgradeDialog container={null} /></div>);
  expect(container.textContent).toBe('');
  expect(screen.queryByRole('dialog')).toBeNull();
  closeUpdateDialog();
});

it('resolves localized release notes and phase labels cleanly', () => {
  setLanguage('en');
  expect(releaseNotesForLanguage({ en: 'English notes', 'zh-Hans': '中文说明' }, 'zh_Hans_CN')).toBe('中文说明');
  expect(releaseNotesForLanguage({ en: 'English notes' }, 'fr')).toBe('English notes');
  expect(updatePhaseText('checking')).toBe('Checking');
});
