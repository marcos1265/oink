import type { Language } from '../i18n';

export interface LocalizedText {
  default: string;
  locales?: Partial<Record<'zh' | 'en' | 'ja' | 'ko', string>>;
}
export interface SponsorItem {
  id: string;
  name: LocalizedText;
  icon: string;
  action: { type: 'link'; url: string } | { type: 'qr'; image: string; caption?: LocalizedText | null };
}
export function localized(text: LocalizedText, language: Language): string {
  return text.locales?.[language === 'zh-Hans' ? 'zh' : language] ?? text.default;
}
export const fallbackItems: SponsorItem[] = [];
