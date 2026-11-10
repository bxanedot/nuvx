import { Link, NavLink, type LinkProps, type NavLinkProps } from 'react-router';
import { preloadRoute } from '@/pageRegistry';

function warm(to: LinkProps['to']) {
  preloadRoute(typeof to === 'string' ? to : (to.pathname ?? ''));
}

export function PrefetchLink({ to, onPointerEnter, onFocus, ...rest }: LinkProps) {
  return (
    <Link
      to={to}
      onPointerEnter={(e) => {
        warm(to);
        onPointerEnter?.(e);
      }}
      onFocus={(e) => {
        warm(to);
        onFocus?.(e);
      }}
      {...rest}
    />
  );
}

export function PrefetchNavLink({ to, onPointerEnter, onFocus, ...rest }: NavLinkProps) {
  return (
    <NavLink
      to={to}
      onPointerEnter={(e) => {
        warm(to);
        onPointerEnter?.(e);
      }}
      onFocus={(e) => {
        warm(to);
        onFocus?.(e);
      }}
      {...rest}
    />
  );
}
