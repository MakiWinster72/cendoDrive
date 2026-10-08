// @vitest-environment jsdom
import { afterEach, describe, expect, it } from 'vitest';
import { readFileSync } from 'node:fs';
const entryStyles = readFileSync('src/styles/chat-entry.css', 'utf8');

afterEach(() => {
  document.head.innerHTML = '';
  document.body.innerHTML = '';
});

describe('chat entry header layout', () => {
  it.each(['discovery-page', 'group-page'])('%s starts at the viewport top like other mobile pages', (pageClass) => {
    const style = document.createElement('style');
    style.textContent = entryStyles;
    document.head.append(style);
    document.body.innerHTML = `<main class="chat-page ${pageClass}"><form><header class="chat-header"></header></form></main>`;
    expect(parseFloat(getComputedStyle(document.querySelector('main')!).paddingTop)).toBe(0);
    const headerRule = Array.from(style.sheet!.cssRules).find(rule =>
      'selectorText' in rule && (rule as CSSStyleRule).selectorText.includes('.discovery-page .chat-header') && (rule as CSSStyleRule).style.getPropertyValue('height') !== ''
    ) as CSSStyleRule;
    expect(headerRule.style.getPropertyValue('height')).toBe('48px');
    expect(headerRule.style.getPropertyValue('min-height')).toBe('48px');
    if (pageClass === 'group-page') {
      expect(getComputedStyle(document.querySelector('form')!).minHeight).toBe('100dvh');
    }
  });
});
