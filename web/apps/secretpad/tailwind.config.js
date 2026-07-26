/** @type {import('tailwindcss').Config} */
export default {
  content: [
    './index.html',
    './src/**/*.{js,ts,jsx,tsx}',
    '../../packages/design-system/src/**/*.{js,ts,jsx,tsx}',
    '../../packages/dag-next/src/**/*.{js,ts,jsx,tsx}',
  ],
  darkMode: ['class', '[data-theme="dark"]'],
  theme: {
    extend: {
      colors: {
        brand: {
          50: '#e6f4ff',
          100: '#bae0ff',
          500: '#1677ff',
          600: '#0958d9',
          700: '#003eb3',
        },
      },
    },
  },
  plugins: [],
};
