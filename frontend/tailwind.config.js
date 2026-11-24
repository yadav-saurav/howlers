/** @type {import('tailwindcss').Config} */
const v = (name) => `rgb(var(--${name}) / <alpha-value>)`;
export default {
  darkMode: 'class',
  content: ['./index.html', './src/**/*.{ts,tsx}'],
  theme: {
    screens: { sm: '640px', md: '768px', lg: '1024px', xl: '1280px', '2xl': '1440px' },
    extend: {
      colors: {
        bg: v('bg'),
        surface: v('surface'),
        'surface-2': v('surface-2'),
        'surface-3': v('surface-3'),
        brand: v('brand'),
        'brand-fg': v('brand-fg'),
        border: v('border'),
        text: v('text'),
        muted: v('text-muted'),
        faint: v('text-faint'),
        accent: v('accent'),
        'accent-fg': v('accent-fg'),
        down: v('down'),
        danger: v('danger'),
        'danger-fg': v('danger-fg'),
        warning: v('warning'),
        info: v('info'),
      },
      fontFamily: {
        sans: ['"Inter Variable"', 'Inter', 'system-ui', 'sans-serif'],
        serif: ['"Source Serif 4 Variable"', 'Georgia', 'serif'],
        mono: ['"JetBrains Mono"', 'ui-monospace', 'monospace'],
        display: ['Unbounded', '"Inter Variable"', '"Arial Black"', 'sans-serif'],
      },
      fontSize: {
        xs: ['12px', '16px'],
        sm: ['13px', '18px'],
        base: ['15px', '23px'],
        lg: ['17px', '25px'],
        xl: ['20px', '28px'],
        '2xl': ['24px', '31px'],
        '3xl': ['30px', '36px'],
      },
      borderRadius: { sm: '4px', DEFAULT: '6px', md: '8px', lg: '12px', xl: '16px', '2xl': '20px' },
      boxShadow: { float: 'var(--shadow-float)' },
      keyframes: {
        shimmer: { '0%': { opacity: '0.55' }, '50%': { opacity: '1' }, '100%': { opacity: '0.55' } },
      },
      animation: { shimmer: 'shimmer 1.4s ease-in-out infinite' },
    },
  },
  plugins: [],
};
