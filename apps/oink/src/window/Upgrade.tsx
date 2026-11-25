import { tr } from '../i18n';
import type { MessageKey } from '../i18n';
import type { UpdatePhase } from '../api/update';
export { localPackagePath } from './PackageDrop';

/** Same lookup as ReleaseNotes: exact tag, parent tags, then English. */
export function releaseNotesForLanguage(notes: Record<string, string> & { en: string }, language: string): string {
  let tag = language.replaceAll('_', '-').toLowerCase();
  for (;;) {
    const key = Object.keys(notes).find(k => k.toLowerCase() === tag);
    if (key !== undefined) return notes[key];
    const dash = tag.lastIndexOf('-');
    if (dash < 0) break;
    tag = tag.slice(0, dash);
  }
  return notes.en;
}

export function updatePhaseText(phase: Exclude<UpdatePhase, 'idle'>): string {
  const text: Record<Exclude<UpdatePhase, 'idle'>, MessageKey> = {
    disabled: '手动更新',
    checking: '正在检查',
    available: '有可用更新',
    downloading: '正在下载',
    verifying: '正在验证',
    ready: '准备安装',
    quiescing: '正在准备更新',
    installing: '正在安装',
    awaiting_reboot: '等待系统重启',
    installer_busy: '安装程序正在运行',
    committed: '更新已安装',
    rolled_back: '已恢复到先前版本',
    repair_required: '需要修复安装',
    failed: '更新失败',
  };
  return tr(text[phase] ?? '更新状态未知');
}

export const openUpdateDialog = () => {};
export const closeUpdateDialog = () => {};

/** Title-bar indicator: disabled in local-only build. */
export function UpgradeButton() {
  return null;
}

/** Update dialog: disabled in local-only build. */
export function UpgradeDialog(_props: { container: HTMLElement | null }) {
  return null;
}
