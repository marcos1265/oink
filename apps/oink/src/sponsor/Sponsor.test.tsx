// @vitest-environment jsdom
import { afterEach, expect, it } from 'vitest';
import { cleanup, render } from '@testing-library/react';
import { PromoArea } from '../window/PromoArea';

afterEach(cleanup);

it('renders nothing in local-only builds regardless of sponsor prop', () => {
  const first = render(<PromoArea />);
  expect(first.container.firstChild).toBeNull();
  const second = render(<PromoArea sponsor={false} />);
  expect(second.container.firstChild).toBeNull();
});
